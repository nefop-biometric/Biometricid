package com.eduin.onboarding.processing;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;

/**
 * Punto de extensión del módulo OCR: una sola pasada que clasifica el tipo
 * y extrae los campos (correr OCR es costoso — no se separa en dos llamadas).
 *
 * Restricción conocida: Tesseract NO soporta llamadas concurrentes — la
 * implementación serializa el acceso al motor nativo internamente.
 */
public interface OcrEngine {

    SideOcrOutcome process(byte[] image, DocumentTypeSpec sessionType, DocumentSide side);

    record SideOcrOutcome(ClassificationResult classification, OcrResult ocr) {
    }
}
