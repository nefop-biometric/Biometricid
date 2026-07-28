package com.eduin.onboarding.ocr.extractor;

import com.eduin.onboarding.ocr.dto.OcrRequest;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;

import java.util.List;

public interface DocumentExtractor {

    DocumentType getDocumentType();

    List<ExtractedField> extract(String ocrText);

    /**
     * Extrae campos filtrando por lado del documento.
     * Por defecto delega a extract(ocrText) sin filtro — los extractores que
     * diferencian frente/reverso deben sobreescribir este método.
     */
    default List<ExtractedField> extract(String ocrText, OcrRequest.DocumentSide side) {
        return extract(ocrText);
    }

    /**
     * Score de 0.0 a 1.0 indicando qué tan probable es que el texto dado pertenezca a este tipo de documento.
     * Usado por el clasificador automático.
     */
    double classifyConfidence(String ocrText);
}
