package com.eduin.onboarding.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResult {

    private String scanId;
    private DocumentType documentType;
    private double classificationConfidence;  // confianza de la clasificación del tipo de doc
    private String rawText;                   // texto crudo del OCR
    private List<ExtractedField> fields;
    private MrzData mrzData;
    private Pdf417Data pdf417Data;
    private LocalDateTime processedAt;
    private long processingTimeMs;
    private String errorMessage;
    private boolean success;

    public Optional<ExtractedField> getField(String fieldName) {
        if (fields == null) return Optional.empty();
        return fields.stream()
                .filter(f -> f.getFieldName().equalsIgnoreCase(fieldName))
                .findFirst();
    }

    public Map<String, Object> toFieldMap() {
        if (fields == null) return Map.of();
        return fields.stream().collect(
                java.util.stream.Collectors.toMap(
                        ExtractedField::getFieldName,
                        f -> Map.of(
                                "value", f.getValue() != null ? f.getValue() : "",
                                "confidence", f.getConfidence(),
                                "fromMrz", f.isFromMrz()
                        )
                )
        );
    }
}
