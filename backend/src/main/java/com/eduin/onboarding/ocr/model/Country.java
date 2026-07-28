package com.eduin.onboarding.ocr.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum Country {

    COL("Colombia", "spa",
            DocumentType.COL_CC_OLD, DocumentType.COL_CC_NEW,
            DocumentType.COL_PA, DocumentType.COL_TI,
            DocumentType.COL_PPT, DocumentType.COL_CE),

    ESP("España", "spa+cat",   // catalán para campos bilingües
            DocumentType.ESP_DNI_OLD, DocumentType.ESP_DNI_NEW),

    ECU("Ecuador", "spa",
            DocumentType.ECU_DNI_OLD, DocumentType.ECU_DNI_NEW),

    PER("Perú", "spa",
            DocumentType.PER_DNI_OLD, DocumentType.PER_DNI_NEW),

    PAN("Panamá", "spa",
            DocumentType.PAN_DNI_OLD, DocumentType.PAN_DNI_NEW);

    /** Nombre legible del país */
    private final String displayName;

    /** Idioma(s) Tesseract a usar para documentos de este país */
    private final String tesseractLanguage;

    /** Tipos de documento que emite este país */
    private final DocumentType[] documentTypes;

    Country(String displayName, String tesseractLanguage, DocumentType... documentTypes) {
        this.displayName = displayName;
        this.tesseractLanguage = tesseractLanguage;
        this.documentTypes = documentTypes;
    }

    public List<DocumentType> getDocumentTypeList() {
        return Arrays.asList(documentTypes);
    }

    /** Busca el país al que pertenece un DocumentType */
    public static Country fromDocumentType(DocumentType docType) {
        for (Country c : values()) {
            for (DocumentType dt : c.documentTypes) {
                if (dt == docType) return c;
            }
        }
        return null;
    }
}
