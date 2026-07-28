package com.eduin.onboarding.processing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrResult(
        Map<String, String> fields,
        Map<String, Double> fieldConfidence,
        Mrz mrz) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mrz(
            List<String> raw,
            String format,
            CheckDigits checkDigits,
            Map<String, String> fields) {

        public record CheckDigits(int total, int valid) {
        }
    }
}
