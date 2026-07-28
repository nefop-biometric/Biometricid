package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.extractor.DocumentExtractor;
import com.eduin.onboarding.ocr.model.Country;
import com.eduin.onboarding.ocr.model.DocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentClassifierService {

    private final List<DocumentExtractor> extractors;

    public record ClassificationResult(
            DocumentType documentType,
            Country country,
            double confidence) {}

    /**
     * Clasificación completamente automática — evalúa todos los extractores.
     */
    public ClassificationResult classify(String ocrText) {
        return classifyFiltered(ocrText, null);
    }

    /**
     * Clasificación restringida a un país — más rápida y precisa.
     * Solo evalúa los tipos de documento del país indicado.
     */
    public ClassificationResult classifyByCountry(String ocrText, Country country) {
        return classifyFiltered(ocrText, country);
    }

    /**
     * Tipo forzado explícitamente por el cliente — confianza máxima, sin clasificación.
     */
    public ClassificationResult forceType(DocumentType documentType) {
        Country country = Country.fromDocumentType(documentType);
        return new ClassificationResult(documentType, country, 1.0);
    }

    private ClassificationResult classifyFiltered(String ocrText, Country countryFilter) {
        List<DocumentExtractor> candidates = extractors.stream()
                .filter(e -> countryFilter == null
                        || countryFilter.getDocumentTypeList().contains(e.getDocumentType()))
                .collect(Collectors.toList());

        int total = extractors.size();
        int filtered = candidates.size();
        if (countryFilter != null) {
            log.info("Classification restricted to country={} → evaluating {}/{} extractors",
                    countryFilter, filtered, total);
        }

        Map<DocumentType, Double> scores = candidates.stream()
                .collect(Collectors.toMap(
                        DocumentExtractor::getDocumentType,
                        e -> {
                            try {
                                return e.classifyConfidence(ocrText);
                            } catch (Exception ex) {
                                log.warn("Score error [{}]: {}", e.getClass().getSimpleName(), ex.getMessage());
                                return 0.0;
                            }
                        }
                ));

        scores.forEach((type, score) ->
                log.debug("  {} → {:.3f}", type.getCode(), score));

        return scores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(e -> new ClassificationResult(
                        e.getKey(),
                        Country.fromDocumentType(e.getKey()),
                        e.getValue()))
                .orElse(new ClassificationResult(DocumentType.UNKNOWN, null, 0.0));
    }

    /** Ranking completo de todos los tipos (útil para debug). */
    public List<Map.Entry<DocumentType, Double>> rankAll(String ocrText) {
        return extractors.stream()
                .map(e -> Map.entry(e.getDocumentType(), e.classifyConfidence(ocrText)))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }
}
