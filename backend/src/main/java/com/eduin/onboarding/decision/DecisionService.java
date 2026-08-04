package com.eduin.onboarding.decision;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import com.eduin.onboarding.processing.AuthenticityResult;
import com.eduin.onboarding.processing.OcrResult;
import com.eduin.onboarding.session.DocumentCapture;
import com.eduin.onboarding.session.dto.SessionDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidación de resultados por cara y decisión final de la sesión.
 * Reglas (en orden, la primera que aplique gana) según docs/02-contrato-api.md:
 *   1. Veto antifraude en cualquier cara            → REJECTED
 *   2. Documento vencido                            → REJECTED
 *   3. Incoherencia frente/reverso (Levenshtein > 2) → REVIEW
 *   4. Autenticidad baja/no disponible o OCR débil  → REVIEW
 *   5. Todo lo demás                                → APPROVED
 *
 * Regla de peor cara (heredada): el score consolidado es la mezcla 0.55/0.45,
 * pero si una cara reprueba el umbral, ella manda — el fraude suele estar en
 * una sola cara y la cara legítima no debe diluirlo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    /** Distancia Levenshtein máxima tolerada entre caras (ruido OCR normal). */
    private static final int MAX_CROSS_DISTANCE = 2;
    /** Confianza OCR mínima del número de documento para aprobar sin revisión. */
    private static final double MIN_KEY_FIELD_CONFIDENCE = 0.60;
    /** Zona gris de autenticidad: por debajo de esto (sin veto) → REVIEW. */
    private static final double REVIEW_SCORE = 0.70;

    private final ObjectMapper objectMapper;

    @Value("${app.authenticity.threshold:0.65}")
    private double authenticityThreshold;

    public record Evaluation(
            Map<String, String> fields,
            List<SessionDetailResponse.Consolidated.CrossCheck> crossChecks,
            Double authenticityScore,
            String outcome,
            List<String> reasons) {
    }

    public Evaluation evaluate(DocumentTypeSpec spec, List<DocumentCapture> captures) {
        Map<DocumentSide, OcrResult> ocrBySide = new EnumMap<>(DocumentSide.class);
        for (DocumentCapture c : captures) {
            OcrResult ocr = parseOcr(c.getOcrJson());
            if (ocr != null) {
                ocrBySide.put(c.getSide(), ocr);
            }
        }

        Map<String, String> fields = consolidateFields(ocrBySide);
        List<SessionDetailResponse.Consolidated.CrossCheck> crossChecks =
                buildCrossChecks(spec, ocrBySide, fields);
        Double score = worstFaceScore(captures);

        List<String> reasons = new ArrayList<>();
        String outcome = decide(spec, captures, crossChecks, fields, score, reasons);
        return new Evaluation(fields, crossChecks, score, outcome, reasons);
    }

    // ── Consolidación ────────────────────────────────────────────────────────

    /** Fusiona los campos de todas las caras; ante duplicado gana la mayor confianza. */
    private Map<String, String> consolidateFields(Map<DocumentSide, OcrResult> ocrBySide) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        // FRONT primero para que, a igual confianza, prevalezca el frente
        for (DocumentSide side : DocumentSide.values()) {
            OcrResult ocr = ocrBySide.get(side);
            if (ocr == null || ocr.fields() == null) {
                continue;
            }
            for (Map.Entry<String, String> e : ocr.fields().entrySet()) {
                double conf = ocr.fieldConfidence() != null
                        ? ocr.fieldConfidence().getOrDefault(e.getKey(), 0.0) : 0.0;
                Double prev = confidence.get(e.getKey());
                if (prev == null || conf > prev) {
                    fields.put(e.getKey(), e.getValue());
                    confidence.put(e.getKey(), conf);
                }
            }
        }
        return fields;
    }

    private List<SessionDetailResponse.Consolidated.CrossCheck> buildCrossChecks(
            DocumentTypeSpec spec, Map<DocumentSide, OcrResult> ocrBySide, Map<String, String> fields) {

        List<SessionDetailResponse.Consolidated.CrossCheck> checks = new ArrayList<>();

        OcrResult front = ocrBySide.get(DocumentSide.FRONT);
        OcrResult back = ocrBySide.get(DocumentSide.BACK);
        if (front != null && back != null) {
            addFrontBackCheck(checks, "FRONT_BACK_NUMBER_MATCH", front, back, "documentNumber");
            addFrontBackCheck(checks, "FRONT_BACK_LASTNAMES_MATCH", front, back, "lastNames");
        }

        if (spec.expires()) {
            LocalDate expiry = parseIsoDate(fields.get("expiryDate"));
            if (expiry != null) {
                checks.add(new SessionDetailResponse.Consolidated.CrossCheck(
                        "EXPIRY_VALID", !expiry.isBefore(LocalDate.now()), null));
            }
        }
        return checks;
    }

    private void addFrontBackCheck(List<SessionDetailResponse.Consolidated.CrossCheck> checks,
                                   String name, OcrResult front, OcrResult back, String field) {
        String f = normalize(front.fields().get(field));
        String b = normalize(back.fields().get(field));
        if (f == null || b == null) {
            return;
        }
        int distance = levenshtein(f, b);
        checks.add(new SessionDetailResponse.Consolidated.CrossCheck(
                name, distance <= MAX_CROSS_DISTANCE, distance));
    }

    /**
     * Regla de peor cara: mezcla 0.55 (frente) / 0.45 (reverso), pero si la peor
     * cara reprueba el umbral de autenticidad, su score manda sobre la mezcla.
     * Devuelve null si ninguna cara tiene análisis de autenticidad.
     */
    private Double worstFaceScore(List<DocumentCapture> captures) {
        Double front = null, back = null;
        for (DocumentCapture c : captures) {
            if (c.getAuthenticityScore() == null) {
                continue;
            }
            if (c.getSide() == DocumentSide.FRONT) front = c.getAuthenticityScore();
            else back = c.getAuthenticityScore();
        }
        if (front == null && back == null) return null;
        if (front == null) return back;
        if (back == null) return front;

        double blended = front * 0.55 + back * 0.45;
        double worst = Math.min(front, back);
        return round(worst < authenticityThreshold ? Math.min(blended, worst) : blended);
    }

    // ── Decisión ─────────────────────────────────────────────────────────────

    private String decide(DocumentTypeSpec spec, List<DocumentCapture> captures,
                          List<SessionDetailResponse.Consolidated.CrossCheck> crossChecks,
                          Map<String, String> fields, Double score, List<String> reasons) {

        boolean veto = captures.stream().anyMatch(c -> Boolean.TRUE.equals(c.getVeto()));
        if (veto) {
            reasons.add("AUTHENTICITY_VETO");
            return "REJECTED";
        }

        boolean expired = crossChecks.stream()
                .anyMatch(c -> "EXPIRY_VALID".equals(c.name()) && !c.passed());
        if (expired) {
            reasons.add("DOCUMENT_EXPIRED");
            return "REJECTED";
        }

        // Un chequeo reprobado de foto sobrepuesta (clasificador ML) fuerza REVIEW
        // explícitamente: es un hallazgo puntual y NO debe diluirse en el promedio
        // de scores con la otra cara (0.65 del frente + 0.96 del reverso > umbral).
        boolean photoSubstitution = captures.stream()
                .map(c -> parseAuthenticity(c.getAuthenticityJson()))
                .filter(a -> a != null && a.checks() != null)
                .flatMap(a -> a.checks().stream())
                .anyMatch(ch -> "PHOTO_SUBSTITUTION_ML".equals(ch.name()) && !ch.passed());
        if (photoSubstitution) {
            reasons.add("PHOTO_SUBSTITUTION_SUSPECT");
        }

        boolean crossMismatch = crossChecks.stream()
                .anyMatch(c -> c.name().startsWith("FRONT_BACK") && !c.passed());
        if (crossMismatch) {
            reasons.add("FRONT_BACK_MISMATCH");
        }

        if (score == null) {
            reasons.add("AUTHENTICITY_UNAVAILABLE");
        } else if (score < REVIEW_SCORE) {
            reasons.add("LOW_AUTHENTICITY_SCORE");
        }

        if (fields.get("documentNumber") == null) {
            reasons.add("DOCUMENT_NUMBER_NOT_READ");
        }

        return reasons.isEmpty() ? "APPROVED" : "REVIEW";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AuthenticityResult parseAuthenticity(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AuthenticityResult.class);
        } catch (Exception e) {
            log.warn("No se pudo parsear authenticity_json almacenado: {}", e.getMessage());
            return null;
        }
    }

    private OcrResult parseOcr(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OcrResult.class);
        } catch (Exception e) {
            log.warn("No se pudo parsear ocr_json almacenado: {}", e.getMessage());
            return null;
        }
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String n = s.toUpperCase().replaceAll("[^A-Z0-9ÑÁÉÍÓÚ ]", "").trim();
        return n.isBlank() ? null : n;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private static LocalDate parseIsoDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static double round(double val) {
        return Math.round(val * 1000.0) / 1000.0;
    }
}
