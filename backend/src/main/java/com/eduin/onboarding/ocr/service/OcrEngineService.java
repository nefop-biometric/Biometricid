package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.config.TesseractConfig;
import com.eduin.onboarding.ocr.model.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrEngineService {

    private final Tesseract tesseract;                       // spa+eng (default)

    @Qualifier("tesseractEsp")
    private final Tesseract tesseractEsp;                    // spa+cat+eng (España)

    @Qualifier("tesseractMrz")
    private final Tesseract tesseractMrz;                    // OCR-B restringido

    private final TesseractConfig tesseractConfig;

    /**
     * OCR general — selecciona el motor adecuado según el país del documento.
     * Si country es null, usa el motor por defecto (spa+eng).
     */
    public String extractText(BufferedImage image, Country country) {
        Tesseract engine = resolveEngine(country);
        // Las instancias de Tesseract (Tess4J/JNA) NO son thread-safe: el motor nativo
        // no soporta llamadas concurrentes sobre el mismo TessBaseAPI handle. Dos
        // requests simultáneos sobre la misma instancia producen corrupción de memoria
        // nativa ("Invalid memory access") que tumba la JVM completa, no solo el request.
        // Se sincroniza por instancia de motor para serializar el acceso sin bloquear
        // entre motores distintos (tesseract / tesseractEsp / tesseractMrz).
        synchronized (engine) {
            try {
                String normal = engine.doOCR(image);

                // Segunda pasada con la imagen invertida: algunos documentos de identidad
                // combinan zonas de texto claro-sobre-oscuro (banners, ribbons de color)
                // con zonas texto oscuro-sobre-claro en el mismo carnet. Un único umbral/
                // polaridad no captura ambas — se concatena el resultado de ambas pasadas
                // para que los extractores (regex) puedan matchear contra cualquiera de
                // las dos sin tener que rediseñar el preprocesamiento por región.
                String inverted = "";
                try {
                    inverted = engine.doOCR(invert(image));
                } catch (TesseractException e) {
                    log.debug("Inverted OCR pass failed (non-fatal): {}", e.getMessage());
                }

                String result = inverted.isBlank() ? normal : normal + "\n" + inverted;
                log.debug("OCR completed [{} chars normal + {} chars inverted] using lang={}",
                        normal.length(), inverted.length(),
                        country != null ? country.getTesseractLanguage() : "default");
                return result;
            } catch (TesseractException e) {
                log.error("Tesseract error: {}", e.getMessage());
                throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
            }
        }
    }

    /** Invierte los valores de gris/color de la imagen (negativo fotográfico). */
    private BufferedImage invert(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType() == 0
                ? BufferedImage.TYPE_INT_RGB : src.getType());
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int a = (rgb >> 24) & 0xff;
                int r = 255 - ((rgb >> 16) & 0xff);
                int g = 255 - ((rgb >> 8) & 0xff);
                int b = 255 - (rgb & 0xff);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    /** Sobrecarga sin país (usa motor por defecto). */
    public String extractText(BufferedImage image) {
        return extractText(image, null);
    }

    /**
     * OCR optimizado para zona MRZ.
     */
    public String extractMrzText(BufferedImage mrzImage) {
        synchronized (tesseractMrz) {
            try {
                String result = tesseractMrz.doOCR(mrzImage);
                return result.toUpperCase()
                             .replaceAll("[^A-Z0-9<\\n]", "")
                             .trim();
            } catch (TesseractException e) {
                log.warn("MRZ OCR failed, using fallback: {}", e.getMessage());
                return extractText(mrzImage)
                        .toUpperCase()
                        .replaceAll("[^A-Z0-9<\\n]", "")
                        .trim();
            }
        }
    }

    /**
     * OCR con idioma explícito (para casos donde el country no está disponible
     * pero se conoce el idioma Tesseract).
     */
    public String extractTextWithLanguage(BufferedImage image, String tesseractLanguage) {
        try {
            Tesseract custom = tesseractConfig.buildInstance(tesseractLanguage, 3);
            return custom.doOCR(image);
        } catch (TesseractException e) {
            log.error("Tesseract error with lang {}: {}", tesseractLanguage, e.getMessage());
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    private Tesseract resolveEngine(Country country) {
        if (country == null) return tesseract;
        return switch (country) {
            case ESP -> tesseractEsp;               // spa+cat+eng
            default  -> tesseract;                  // spa+eng
        };
    }
}
