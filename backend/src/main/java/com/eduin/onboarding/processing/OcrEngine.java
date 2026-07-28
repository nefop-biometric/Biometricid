package com.eduin.onboarding.processing;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;

/**
 * Punto de extensión del módulo OCR (clasificación de tipo + extracción de campos).
 * La implementación real (Tess4J) reemplazará a NoOpOcrEngine.
 *
 * Restricción conocida: Tesseract NO soporta llamadas concurrentes — la implementación
 * real debe serializar internamente (semáforo).
 */
public interface OcrEngine {

    ClassificationResult classify(byte[] image, DocumentTypeSpec sessionType, DocumentSide side);

    OcrResult extract(byte[] image, DocumentTypeSpec type, DocumentSide side);
}
