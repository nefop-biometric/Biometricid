package com.eduin.onboarding.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedField {

    private String fieldName;
    private String value;
    private double confidence;    // 0.0 - 1.0
    private String rawValue;      // valor tal cual salió del OCR, antes de normalización
    private boolean fromMrz;

    public static ExtractedField of(String fieldName, String value, double confidence) {
        return ExtractedField.builder()
                .fieldName(fieldName)
                .value(value)
                .rawValue(value)
                .confidence(confidence)
                .fromMrz(false)
                .build();
    }

    public static ExtractedField fromMrz(String fieldName, String value) {
        return ExtractedField.builder()
                .fieldName(fieldName)
                .value(value)
                .rawValue(value)
                .confidence(0.98)
                .fromMrz(true)
                .build();
    }
}
