package com.eduin.onboarding.ocr.config;

import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class TesseractConfig {

    @Value("${tesseract.datapath}")
    private String dataPath;

    @Value("${tesseract.ocrEngineMode}")
    private int ocrEngineMode;

    /**
     * Instancia base para documentos en español puro
     * (Colombia, Ecuador, Perú, Panamá).
     */
    @Bean
    @Primary
    public Tesseract tesseract() {
        return buildInstance("spa+eng", 3);
    }

    /**
     * Instancia para documentos españoles (DNI).
     * Incluye catalán por los campos bilingües APELLIDOS/COGNOMS, etc.
     */
    @Bean("tesseractEsp")
    public Tesseract tesseractEsp() {
        return buildInstance("spa+cat+eng", 3);
    }

    /**
     * Instancia para zona MRZ (modelo OCR-B dedicado + charset restringido).
     * Usa mrz.traineddata (Innovatrics/DoubangoTelecom) si está disponible,
     * con fallback a eng si no está instalado.
     */
    @Bean("tesseractMrz")
    public Tesseract tesseractMrz() {
        Tesseract t = new Tesseract();
        t.setDatapath(dataPath);
        // mrz.traineddata es el modelo entrenado específicamente en fuente OCR-B
        String lang = new java.io.File(dataPath, "mrz.traineddata").exists() ? "mrz" : "eng";
        t.setLanguage(lang);
        t.setOcrEngineMode(1);   // LSTM
        t.setPageSegMode(6);     // bloque uniforme de texto
        t.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<");
        return t;
    }

    /**
     * Crea una instancia Tesseract con la configuración estándar para documentos de identidad.
     */
    public Tesseract buildInstance(String language, int pageSegMode) {
        Tesseract t = new Tesseract();
        t.setDatapath(dataPath);
        t.setLanguage(language);
        t.setOcrEngineMode(ocrEngineMode);
        t.setPageSegMode(pageSegMode);
        // Caracteres esperados en documentos de identidad latinoamericanos y españoles
        t.setVariable("tessedit_char_whitelist",
                "ABCDEFGHIJKLMNÑOPQRSTUVWXYZabcdefghijklmnñopqrstuvwxyz" +
                "0123456789 -./()+ÁÉÍÓÚáéíóúÜüÀàÈèÌìÒòÙùÇç");
        return t;
    }
}
