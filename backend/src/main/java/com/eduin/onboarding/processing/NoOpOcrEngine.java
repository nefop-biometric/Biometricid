package com.eduin.onboarding.processing;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import org.springframework.stereotype.Service;

/**
 * Implementación temporal mientras se construye el módulo OCR real.
 * classify() confía en el tipo declarado por la sesión; extract() no devuelve campos.
 */
@Service
public class NoOpOcrEngine implements OcrEngine {

    @Override
    public ClassificationResult classify(byte[] image, DocumentTypeSpec sessionType, DocumentSide side) {
        return new ClassificationResult(sessionType.code(), true, 0.0);
    }

    @Override
    public OcrResult extract(byte[] image, DocumentTypeSpec type, DocumentSide side) {
        return null;
    }
}
