package com.eduin.onboarding.ocr;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import com.eduin.onboarding.ocr.dto.OcrRequest;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import com.eduin.onboarding.ocr.model.MrzData;
import com.eduin.onboarding.ocr.service.DocumentClassifierService;
import com.eduin.onboarding.ocr.service.DocumentOcrService;
import com.eduin.onboarding.processing.ClassificationResult;
import com.eduin.onboarding.processing.OcrEngine;
import com.eduin.onboarding.processing.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapta el pipeline OCR portado del proyecto anterior (DocumentOcrService:
 * preprocesado → Tesseract → clasificación → extractor por tipo → barcode → MRZ
 * con gating por dígitos verificadores) a la interfaz OcrEngine del onboarding.
 *
 * También traduce los nombres de campo históricos en español al contrato v1
 * en inglés (docs/02-contrato-api.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TesseractOcrEngineAdapter implements OcrEngine {

    /** Umbral mínimo para aceptar que la imagen corresponde al tipo declarado. */
    private static final double SESSION_TYPE_MIN_SCORE = 0.30;
    /** Umbral mínimo para afirmar con seguridad que es OTRO tipo (mismatch). */
    private static final double OTHER_TYPE_MISMATCH_SCORE = 0.50;

    private final DocumentOcrService documentOcrService;
    private final DocumentClassifierService classifier;

    private static final Map<String, String> FIELD_NAME_MAP = Map.ofEntries(
            Map.entry("apellidos", "lastNames"),
            Map.entry("nombres", "firstNames"),
            Map.entry("nombre", "firstNames"),
            Map.entry("fechaNacimiento", "birthDate"),
            Map.entry("fechaVencimiento", "expiryDate"),
            Map.entry("fechaValidez", "expiryDate"),
            Map.entry("fechaVigencia", "expiryDate"),
            Map.entry("fechaExpedicion", "issueDate"),
            Map.entry("fechaEmision", "issueDate"),
            Map.entry("sexo", "sex"),
            Map.entry("nacionalidad", "nationality"),
            Map.entry("grupoSanguineo", "bloodType"),
            Map.entry("lugarNacimiento", "birthPlace"),
            Map.entry("ciudadNacimiento", "birthPlace"),
            Map.entry("paisNacimiento", "birthCountry"),
            Map.entry("lugarExpedicion", "issuePlace"),
            Map.entry("estatura", "height"),
            Map.entry("numSoporte", "supportNumber"),
            Map.entry("numeroPersonal", "personalNumber"),
            Map.entry("estadoCivil", "civilStatus"));

    @Override
    public SideOcrOutcome process(byte[] image, DocumentTypeSpec sessionType, DocumentSide side) {
        OcrRequest request = new OcrRequest();
        request.setDocumentType(DocumentType.valueOf(sessionType.code()));
        request.setSide(side == DocumentSide.BACK
                ? OcrRequest.DocumentSide.BACK
                : OcrRequest.DocumentSide.FRONT);

        com.eduin.onboarding.ocr.model.OcrResult raw =
                documentOcrService.process(image, request, null, null);

        return new SideOcrOutcome(classifyAgainstSession(raw, sessionType), toContractOcr(raw));
    }

    /**
     * El tipo de la sesión se usa como hint para la extracción (máxima precisión),
     * pero la coincidencia se verifica aparte re-clasificando el texto OCR ya obtenido
     * (sin segunda pasada de Tesseract). Política conservadora: solo se declara
     * mismatch cuando el tipo declarado puntúa bajo Y otro tipo puntúa alto.
     */
    private ClassificationResult classifyAgainstSession(com.eduin.onboarding.ocr.model.OcrResult raw,
                                                        DocumentTypeSpec sessionType) {
        String text = raw.getRawText();
        if (text == null || text.isBlank()) {
            return new ClassificationResult(sessionType.code(), true, 0.0);
        }

        double sessionScore = 0.0;
        DocumentType bestType = null;
        double bestScore = 0.0;
        for (Map.Entry<DocumentType, Double> entry : classifier.rankAll(text)) {
            if (entry.getKey().getCode().equals(sessionType.code())) {
                sessionScore = entry.getValue();
            }
            if (bestType == null) {
                bestType = entry.getKey();
                bestScore = entry.getValue();
            }
        }

        boolean matches = sessionScore >= SESSION_TYPE_MIN_SCORE
                || bestType == null
                || bestType.getCode().equals(sessionType.code())
                || bestScore < OTHER_TYPE_MISMATCH_SCORE;

        if (matches) {
            return new ClassificationResult(sessionType.code(), true, sessionScore);
        }
        log.info("Type mismatch: session={} (score {}) vs detected={} (score {})",
                sessionType.code(), sessionScore, bestType.getCode(), bestScore);
        return new ClassificationResult(bestType.getCode(), false, bestScore);
    }

    private OcrResult toContractOcr(com.eduin.onboarding.ocr.model.OcrResult raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();

        if (raw.getFields() != null) {
            for (ExtractedField f : raw.getFields()) {
                if (f.getValue() == null || f.getValue().isBlank()) {
                    continue;
                }
                String name = FIELD_NAME_MAP.getOrDefault(f.getFieldName(), f.getFieldName());
                Double previous = confidence.get(name);
                if (previous == null || f.getConfidence() > previous) {
                    fields.put(name, f.getValue());
                    confidence.put(name, f.getConfidence());
                }
            }
        }

        return new OcrResult(fields, confidence, toContractMrz(raw.getMrzData()));
    }

    private OcrResult.Mrz toContractMrz(MrzData mrz) {
        if (mrz == null) {
            return null;
        }

        List<String> rawLines = new ArrayList<>();
        if (mrz.getRawLine1() != null) rawLines.add(mrz.getRawLine1());
        if (mrz.getRawLine2() != null) rawLines.add(mrz.getRawLine2());
        if (mrz.getRawLine3() != null) rawLines.add(mrz.getRawLine3());

        // Misma regla que el gating de DocumentOcrService: el check digit de una fecha
        // solo cuenta si la fecha es plausible (evita "válidos" por coincidencia).
        int valid = (mrz.isDocumentNumberValid() ? 1 : 0)
                + (mrz.isDateOfBirthValid() && isPlausibleYymmdd(mrz.getDateOfBirth()) ? 1 : 0)
                + (mrz.isExpiryDateValid() && isPlausibleYymmdd(mrz.getExpiryDate()) ? 1 : 0);

        Map<String, String> mrzFields = new LinkedHashMap<>();
        putIfPresent(mrzFields, "documentNumber", mrz.getDocumentNumber());
        putIfPresent(mrzFields, "lastNames", mrz.getSurnames());
        putIfPresent(mrzFields, "firstNames", mrz.getGivenNames());
        putIfPresent(mrzFields, "birthDate", isoDate(mrz.getDateOfBirth()));
        putIfPresent(mrzFields, "expiryDate", isoDate(mrz.getExpiryDate()));
        putIfPresent(mrzFields, "sex", mrz.getSex());
        putIfPresent(mrzFields, "nationality", mrz.getNationality());
        putIfPresent(mrzFields, "issuingCountry", mrz.getIssuingCountry());
        putIfPresent(mrzFields, "documentCode", mrz.getDocumentCode());

        String format = mrz.getMrzType() == null ? null
                : mrz.getMrzType().startsWith("TD") ? mrz.getMrzType() : "TD" + mrz.getMrzType();

        return new OcrResult.Mrz(rawLines, format,
                new OcrResult.Mrz.CheckDigits(3, valid), mrzFields);
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** Fecha MRZ plausible: 6 dígitos con mes 01-12 y día 01-31. */
    private static boolean isPlausibleYymmdd(String s) {
        if (s == null || s.length() != 6 || !s.chars().allMatch(Character::isDigit)) return false;
        int mm = Integer.parseInt(s.substring(2, 4));
        int dd = Integer.parseInt(s.substring(4, 6));
        return mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31;
    }

    /** YYMMDD (MRZ) → ISO 8601. Ventana de siglo: >30 ⇒ 19xx (igual que el proyecto anterior). */
    private static String isoDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6 || !yymmdd.chars().allMatch(Character::isDigit)) {
            return yymmdd;
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int year = yy > 30 ? 1900 + yy : 2000 + yy;
        return year + "-" + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
    }
}
