package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.model.Pdf417Data;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodifica códigos de barras en imágenes de documentos de identidad.
 *
 * Estrategia:
 *   1. ZXing sobre imagen completa (original, invertida, grises)
 *   2. ZXing sobre franja inferior 30% recortada y ampliada ×2 (donde está PDF417 en CC Colombia)
 *   3. Fallback OCR: parsea el texto crudo del OCR buscando el patrón del código colombiano
 */
@Slf4j
@Service
public class BarcodeReaderService {

    // Patrón del código inferior CC Colombia antigua (completo):
    // A-1500113-45152074-M-0079108562-20060831
    private static final Pattern CC_COL_FULL = Pattern.compile(
            "([APEape]-\\d{7}-\\d{8}-[MFmf]-)(\\d{7,13})-(\\d{8}).*",
            Pattern.DOTALL);

    // Patrón relajado para extraer desde texto OCR fragmentado:
    // busca fragmentos clave aunque estén en líneas separadas
    private static final Pattern CC_COL_OCR_DOC = Pattern.compile(
            "[APEape]-\\d{5,7}[\\s\\-]*\\d{0,3}[\\s\\S]{0,30}?([MFmf])-[\\s]*(\\d{7,13})[\\s\\S]{0,20}?(\\d{8})",
            Pattern.CASE_INSENSITIVE);

    private static final BarcodeFormat[] PRIORITY_FORMATS = {
            BarcodeFormat.PDF_417,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.DATA_MATRIX
    };

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Intenta decodificar cualquier código de barras en la imagen usando ZXing.
     * Si ZXing falla, retorna null (usar {@link #decodeFromOcrText} como fallback).
     */
    public Pdf417Data decode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) return null;
            return decodeImage(image);
        } catch (IOException e) {
            log.debug("BarcodeReader: error leyendo imagen: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fallback: extrae datos del código de barras directamente desde el texto OCR.
     * Útil cuando ZXing no puede decodificar la imagen (baja calidad, hologramas).
     */
    public Pdf417Data decodeFromOcrText(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) return null;

        // Intento 1: patrón completo en una sola línea
        Matcher m = CC_COL_FULL.matcher(ocrText);
        if (m.find()) {
            log.info("BarcodeReader (OCR fallback): patrón CC completo encontrado");
            return buildFromOcrFull(m);
        }

        // Intento 2: patrón relajado — el OCR puede fragmentar el código en varias líneas
        Matcher m2 = CC_COL_OCR_DOC.matcher(ocrText);
        if (m2.find()) {
            String sexo   = m2.group(1);
            String docRaw = m2.group(2);
            String fecha  = m2.group(3);
            if (docRaw.length() >= 7 && fecha.length() == 8) {
                log.info("BarcodeReader (OCR fallback relajado): doc={} sexo={}", docRaw, sexo);
                return buildSimple(docRaw, sexo, fecha, ocrText);
            }
        }

        return null;
    }

    // ── ZXing ─────────────────────────────────────────────────────────────────

    private Pdf417Data decodeImage(BufferedImage image) {
        Map<DecodeHintType, Object> hints = buildHints();

        // Intento 1: imagen completa original
        Pdf417Data result = tryDecode(image, hints);
        if (result != null) return result;

        // Intento 2: escala de grises
        result = tryDecode(toGrayscale(image), hints);
        if (result != null) return result;

        // Intento 3: invertida
        result = tryDecode(invertImage(image), hints);
        if (result != null) return result;

        // Intentos 4-6: franja inferior recortada (donde está el PDF417 en CC Colombia)
        // En la CC vieja el código ocupa el ~25% inferior de la cara posterior.
        BufferedImage bottom = cropBottom(image, 0.28);
        result = tryDecode(bottom, hints);
        if (result != null) return result;

        result = tryDecode(upscale(bottom, 2.0), hints);
        if (result != null) return result;

        result = tryDecode(toGrayscale(upscale(bottom, 2.0)), hints);
        if (result != null) return result;

        log.debug("BarcodeReader (ZXing): no se detectó código de barras");
        return null;
    }

    private Pdf417Data tryDecode(BufferedImage image, Map<DecodeHintType, Object> hints) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(bitmap, hints);
            return buildPdf417Data(result);
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("BarcodeReader: excepción ZXing: {}", e.getMessage());
            return null;
        }
    }

    private Map<DecodeHintType, Object> buildHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(PRIORITY_FORMATS));
        return hints;
    }

    // ── Construcción del resultado ────────────────────────────────────────────

    private Pdf417Data buildPdf417Data(Result zxingResult) {
        if (zxingResult == null) return null;
        String raw = zxingResult.getText();
        String format = zxingResult.getBarcodeFormat().name();
        log.info("BarcodeReader (ZXing): [{}] {} chars — {}",
                format, raw.length(), raw.length() > 80 ? raw.substring(0, 80) + "…" : raw);

        Pdf417Data.Pdf417DataBuilder builder = Pdf417Data.builder()
                .rawValue(raw)
                .format(format);

        Matcher m = CC_COL_FULL.matcher(raw.trim());
        if (m.matches()) {
            parseColombianCC(builder, m);
        } else {
            builder.parsedColombianCC(false);
        }
        return builder.build();
    }

    private Pdf417Data buildFromOcrFull(Matcher m) {
        Pdf417Data.Pdf417DataBuilder builder = Pdf417Data.builder()
                .rawValue(m.group(0))
                .format("PDF_417_OCR");
        parseColombianCC(builder, m);
        return builder.build();
    }

    private Pdf417Data buildSimple(String docRaw, String sexo, String fechaRaw, String rawOcr) {
        String docNumber = docRaw.replaceFirst("^0+", "");
        if (docNumber.isEmpty()) docNumber = docRaw;
        String fecha = fechaRaw.length() == 8
                ? fechaRaw.substring(0, 4) + "-" + fechaRaw.substring(4, 6) + "-" + fechaRaw.substring(6, 8)
                : fechaRaw;
        return Pdf417Data.builder()
                .rawValue(rawOcr)
                .format("PDF_417_OCR_RELAXED")
                .documentNumber(docNumber)
                .sex(sexo.toUpperCase())
                .fechaExpedicion(fecha)
                .parsedColombianCC(true)
                .build();
    }

    private void parseColombianCC(Pdf417Data.Pdf417DataBuilder builder, Matcher m) {
        String prefix  = m.group(1);   // "A-1500113-45152074-M-"
        String docRaw  = m.group(2);   // "0079108562"
        String fechaRaw= m.group(3);   // "20060831"

        String[] parts = prefix.split("-");
        String serie       = parts[0] + "-" + parts[1];
        String consecutivo = parts.length > 2 ? parts[2] : "";
        String sexo        = parts.length > 3 ? parts[3] : "";

        String docNumber = docRaw.replaceFirst("^0+", "");
        if (docNumber.isEmpty()) docNumber = docRaw;

        String fecha = fechaRaw.length() == 8
                ? fechaRaw.substring(0, 4) + "-" + fechaRaw.substring(4, 6) + "-" + fechaRaw.substring(6, 8)
                : fechaRaw;

        builder.serieRegistraduria(serie)
               .consecutivo(consecutivo)
               .sex(sexo.toUpperCase())
               .documentNumber(docNumber)
               .fechaExpedicion(fecha)
               .parsedColombianCC(true);

        log.info("BarcodeReader: CC Colombia — doc={} sexo={} fechaExp={}", docNumber, sexo, fecha);
    }

    // ── Utilidades de imagen ──────────────────────────────────────────────────

    private BufferedImage cropBottom(BufferedImage src, double fraction) {
        int startY = (int) (src.getHeight() * (1.0 - fraction));
        int height = src.getHeight() - startY;
        return src.getSubimage(0, startY, src.getWidth(), height);
    }

    private BufferedImage upscale(BufferedImage src, double factor) {
        int w = (int) (src.getWidth() * factor);
        int h = (int) (src.getHeight() * factor);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private BufferedImage invertImage(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getType() == 0 ? BufferedImage.TYPE_INT_RGB : src.getType());
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int a = (rgb >> 24) & 0xff;
                int r = 255 - ((rgb >> 16) & 0xff);
                int g = 255 - ((rgb >> 8)  & 0xff);
                int b = 255 - (rgb         & 0xff);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private BufferedImage toGrayscale(BufferedImage src) {
        BufferedImage gray = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }
}
