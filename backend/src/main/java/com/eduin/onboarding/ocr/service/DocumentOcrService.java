package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.dto.OcrRequest;
import com.eduin.onboarding.ocr.extractor.DocumentExtractor;
import com.eduin.onboarding.ocr.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentOcrService {

    private final ImagePreprocessorService preprocessor;
    private final OcrEngineService ocrEngine;
    private final DocumentClassifierService classifier;
    private final MrzParserService mrzParser;
    private final BarcodeReaderService barcodeReader;
    private final List<DocumentExtractor> extractors;
    // Nota: la persistencia y el guardado de imágenes que hacía el proyecto anterior
    // aquí ahora son responsabilidad del módulo session (SessionService/ImageStorageService).

    /**
     * Procesa un documento de identidad.
     *
     * Flujo según los hints recibidos:
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │ Sin hints          → Auto-clasificación con todos los extractores   │
     * │ Solo country       → Clasificación restringida al país              │
     * │ Solo documentType  → Sin clasificación, idioma del país inferido    │
     * │ Country + type     → Sin clasificación, idioma óptimo del país      │
     * └─────────────────────────────────────────────────────────────────────┘
     */
    public OcrResult process(byte[] imageBytes, OcrRequest request,
                             String originalFilename, String clientIp) {

        long startMs = System.currentTimeMillis();
        String scanId = UUID.randomUUID().toString();

        // Extraer hints del request
        Country hintCountry     = request != null ? request.getCountry()      : null;
        DocumentType hintType   = request != null ? request.getDocumentType() : null;
        OcrRequest.DocumentSide side = request != null ? request.getSide()    : OcrRequest.DocumentSide.AUTO;

        // Si se indica el tipo, inferir el país si no se dio
        if (hintType != null && hintCountry == null) {
            hintCountry = Country.fromDocumentType(hintType);
        }

        final Country effectiveCountry = hintCountry;

        log.info("[{}] Processing — country={}, type={}, side={}",
                scanId, effectiveCountry, hintType, side);

        try {
            // ── 1. Preprocesar imagen ────────────────────────────────────────
            BufferedImage processedImage = preprocessor.preprocess(imageBytes);

            // ── 2. OCR con motor del país correcto ───────────────────────────
            String rawText = ocrEngine.extractText(processedImage, effectiveCountry);

            // ── 3. Clasificar tipo de documento ──────────────────────────────
            DocumentClassifierService.ClassificationResult classification;

            if (hintType != null) {
                // Tipo conocido → sin clasificación
                classification = classifier.forceType(hintType);
                log.info("[{}] Type forced to {} (confidence: 1.0)", scanId, hintType);

            } else if (hintCountry != null) {
                // País conocido → clasificación restringida
                classification = classifier.classifyByCountry(rawText, hintCountry);
                log.info("[{}] Classified as {} within country={} (confidence: {:.2f})",
                        scanId, classification.documentType(), hintCountry, classification.confidence());

            } else {
                // Sin hints → clasificación completa
                classification = classifier.classify(rawText);
                log.info("[{}] Auto-classified as {} (confidence: {:.2f})",
                        scanId, classification.documentType(), classification.confidence());
            }

            DocumentType docType    = classification.documentType();
            Country docCountry      = classification.country();
            double classConfidence  = classification.confidence();

            // ── 4. Re-procesar con OCR óptimo si el país fue inferido ahora ─
            String finalText = rawText;
            if (hintCountry == null && docCountry != null
                    && docCountry != effectiveCountry
                    && docCountry.getTesseractLanguage() != null) {
                log.info("[{}] Re-running OCR with lang={} (inferred from {})",
                        scanId, docCountry.getTesseractLanguage(), docType);
                finalText = ocrEngine.extractText(processedImage, docCountry);
            }

            // ── 5. Extraer campos ────────────────────────────────────────────
            DocumentExtractor extractor = findExtractor(docType);
            List<ExtractedField> fields = extractor != null
                    ? extractor.extract(finalText, side)
                    : new ArrayList<>();

            // ── 6. Decodificar código de barras (PDF417 / QR) ────────────────
            Pdf417Data pdf417Data = null;
            try {
                // Intento 1: ZXing sobre la imagen
                pdf417Data = barcodeReader.decode(imageBytes);

                // Intento 2: fallback desde texto OCR (cuando el barcode no es legible por ZXing)
                if (pdf417Data == null) {
                    pdf417Data = barcodeReader.decodeFromOcrText(finalText);
                    if (pdf417Data != null) {
                        log.info("[{}] Barcode from OCR fallback [{}]", scanId, pdf417Data.getFormat());
                    }
                }

                if (pdf417Data != null) {
                    fields = enrichFromBarcode(fields, pdf417Data);
                    log.info("[{}] Barcode enriched: parsedCC={} doc={}",
                            scanId, pdf417Data.isParsedColombianCC(), pdf417Data.getDocumentNumber());
                }
            } catch (Exception e) {
                log.debug("[{}] Barcode decoding skipped: {}", scanId, e.getMessage());
            }

            // ── 7. Parsear MRZ si aplica ──────────────────────────────────────
            MrzData mrzData = null;
            boolean couldHaveMrz = docType.isHasMrz()
                    || finalText.matches("(?s).*[A-Z0-9<]{26,48}.*");

            if (couldHaveMrz) {
                // Intento 1: parsear MRZ directamente del texto OCR normal
                try {
                    mrzData = mrzParser.parse(finalText);
                    if (mrzData != null) {
                        log.info("[{}] MRZ parsed from raw OCR text (TD{})", scanId, mrzData.getMrzType());
                    }
                } catch (Exception e) {
                    log.debug("[{}] MRZ from raw text failed: {}", scanId, e.getMessage());
                }

                // Intento 2: pre-proceso MRZ específico + OCR dedicado
                if (mrzData == null) {
                    try {
                        BufferedImage mrzImage = preprocessor.preprocessMrz(imageBytes);
                        String mrzText = ocrEngine.extractMrzText(mrzImage);
                        log.debug("[{}] MRZ OCR text: {}", scanId, mrzText.replace("\n", " | "));
                        mrzData = mrzParser.parse(mrzText);
                        if (mrzData != null) {
                            log.info("[{}] MRZ parsed from preprocessed image (TD{})", scanId, mrzData.getMrzType());
                        }
                    } catch (Exception e) {
                        log.warn("[{}] MRZ processing skipped: {}", scanId, e.getMessage());
                    }
                }

                if (mrzData != null) {
                    // Un dígito verificador puede pasar por pura coincidencia sobre texto
                    // revuelto (visto con MRZ mal segmentada: "fecha" 926120 → mes 61).
                    // El check digit solo cuenta si el campo además es plausible.
                    int validCount = (mrzData.isDocumentNumberValid() ? 1 : 0)
                                   + (mrzData.isDateOfBirthValid()
                                        && isPlausibleYymmdd(mrzData.getDateOfBirth()) ? 1 : 0)
                                   + (mrzData.isExpiryDateValid()
                                        && isPlausibleYymmdd(mrzData.getExpiryDate()) ? 1 : 0);
                    if (validCount == 0) {
                        // Ningún dígito verificador pasa → es basura (texto normal
                        // malinterpretado como MRZ). Se descarta por completo.
                        log.warn("[{}] MRZ discarded: 0/3 check digits valid", scanId);
                        mrzData = null;
                    } else if (validCount >= 2) {
                        fields = enrichFromMrz(fields, mrzData);
                        log.info("[{}] MRZ enriched fields ({}/3 check digits valid)", scanId, validCount);
                    } else {
                        // 1/3 válido: se muestra el MRZ pero no sobrescribe el OCR
                        log.info("[{}] MRZ kept for display only (1/3 check digits valid)", scanId);
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;

            OcrResult result = OcrResult.builder()
                    .scanId(scanId)
                    .documentType(docType)
                    .classificationConfidence(classConfidence)
                    .rawText(finalText)
                    .fields(fields)
                    .mrzData(mrzData)
                    .pdf417Data(pdf417Data)
                    .processedAt(LocalDateTime.now())
                    .processingTimeMs(elapsed)
                    .success(true)
                    .build();

            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[{}] Processing failed: {}", scanId, e.getMessage(), e);

            return OcrResult.builder()
                    .scanId(scanId)
                    .documentType(DocumentType.UNKNOWN)
                    .classificationConfidence(0.0)
                    .processedAt(LocalDateTime.now())
                    .processingTimeMs(elapsed)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    // ── Retrocompatibilidad ──────────────────────────────────────────────────

    public OcrResult process(byte[] imageBytes, String originalFilename, String clientIp) {
        return process(imageBytes, (DocumentType) null, originalFilename, clientIp);
    }

    public OcrResult process(byte[] imageBytes, DocumentType hintType,
                             String originalFilename, String clientIp) {
        OcrRequest req = new OcrRequest();
        req.setDocumentType(hintType);
        return process(imageBytes, req, originalFilename, clientIp);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Enriquece los campos extraídos por OCR con los datos del código de barras.
     * El barcode tiene fiabilidad 1.0 (decodificación digital exacta) — sobreescribe
     * siempre el valor OCR si el campo ya existe con baja confianza.
     */
    private List<ExtractedField> enrichFromBarcode(List<ExtractedField> fields, Pdf417Data barcode) {
        if (!barcode.isParsedColombianCC()) {
            // Para barcodes no estructurados solo guardamos el rawValue
            fields.add(ExtractedField.of("barcodeRaw", barcode.getRawValue(), 1.0));
            return fields;
        }

        List<ExtractedField> enriched = new ArrayList<>(fields);

        // documentNumber — fuente de máxima fiabilidad
        overwriteOrAdd(enriched, "documentNumber", barcode.getDocumentNumber(), 1.0, true);
        overwriteOrAdd(enriched, "sexo",            barcode.getSex(),            1.0, true);
        overwriteOrAdd(enriched, "fechaExpedicion", barcode.getFechaExpedicion(), 1.0, true);
        enriched.add(ExtractedField.of("codigoBarras", barcode.getRawValue(), 1.0));

        return enriched;
    }

    private void overwriteOrAdd(List<ExtractedField> fields, String name, String value,
                                double confidence, boolean forceOverwrite) {
        if (value == null || value.isBlank()) return;
        for (ExtractedField f : fields) {
            if (name.equals(f.getFieldName())) {
                if (forceOverwrite || f.getValue() == null || f.getConfidence() < confidence) {
                    f.setValue(value);
                    f.setRawValue(value);
                    f.setConfidence(confidence);
                }
                return;
            }
        }
        fields.add(ExtractedField.of(name, value, confidence));
    }

    private List<ExtractedField> enrichFromMrz(List<ExtractedField> fields, MrzData mrz) {
        Map<String, String> mrzFields = new java.util.HashMap<>();
        mrzFields.put("apellidos",       mrz.getSurnames());
        mrzFields.put("nombres",         mrz.getGivenNames());
        mrzFields.put("documentNumber",  mrz.getDocumentNumber());
        mrzFields.put("fechaNacimiento", normalizeDate(mrz.getDateOfBirth()));
        mrzFields.put("fechaVencimiento",normalizeDate(mrz.getExpiryDate()));
        mrzFields.put("sexo",            mrz.getSex());
        mrzFields.put("nacionalidad",    mrz.getNationality());

        List<ExtractedField> enriched = new ArrayList<>(fields);
        mrzFields.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            boolean found = false;
            for (ExtractedField f : enriched) {
                if (f.getFieldName().equals(key)) {
                    if (f.getValue() == null || f.getConfidence() < 0.95) {
                        f.setValue(value);
                        f.setRawValue(value);
                        f.setConfidence(0.97);
                        f.setFromMrz(true);
                    }
                    found = true;
                    break;
                }
            }
            if (!found) enriched.add(ExtractedField.fromMrz(key, value));
        });
        return enriched;
    }

    /** Fecha MRZ plausible: 6 dígitos con mes 01-12 y día 01-31. */
    private static boolean isPlausibleYymmdd(String s) {
        if (s == null || s.length() != 6 || !s.chars().allMatch(Character::isDigit)) return false;
        int mm = Integer.parseInt(s.substring(2, 4));
        int dd = Integer.parseInt(s.substring(4, 6));
        return mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31;
    }

    private String normalizeDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) return yymmdd;
        try {
            int yy = Integer.parseInt(yymmdd.substring(0, 2));
            int year = yy > 30 ? 1900 + yy : 2000 + yy;
            return year + "-" + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
        } catch (NumberFormatException e) {
            return yymmdd;
        }
    }

    private DocumentExtractor findExtractor(DocumentType type) {
        return extractors.stream()
                .filter(e -> e.getDocumentType() == type)
                .findFirst().orElse(null);
    }

}
