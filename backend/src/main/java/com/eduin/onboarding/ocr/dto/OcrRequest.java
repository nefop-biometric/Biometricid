package com.eduin.onboarding.ocr.dto;

import com.eduin.onboarding.ocr.model.Country;
import com.eduin.onboarding.ocr.model.DocumentType;
import lombok.Data;

/**
 * Parámetros del request de OCR.
 *
 * Modos de operación:
 *
 * 1. Totalmente automático (no se envía nada):
 *    → El sistema detecta país y tipo de documento.
 *
 * 2. País conocido (se envía country):
 *    → El sistema solo evalúa los tipos de ese país (más rápido y preciso).
 *
 * 3. Tipo conocido (se envía documentType):
 *    → El sistema va directo al extractor correcto (máxima precisión).
 *
 * 4. Completo (se envía country + documentType):
 *    → Sin clasificación, OCR configurado específicamente para ese documento.
 */
@Data
public class OcrRequest {

    /**
     * País del documento.
     * Opcional — si se provee, restringe la clasificación a ese país.
     */
    private Country country;

    /**
     * Tipo de documento específico.
     * Opcional — si se provee, se omite la clasificación automática.
     */
    private DocumentType documentType;

    /**
     * Cara del documento que se está enviando.
     * Algunos documentos (cédula antigua COL) tienen datos en ambas caras.
     * Por defecto se intenta procesar como cara frontal.
     */
    private DocumentSide side = DocumentSide.AUTO;

    public enum DocumentSide {
        FRONT,   // Solo cara frontal
        BACK,    // Solo cara posterior
        AUTO     // Detectar automáticamente o procesar ambas caras
    }
}
