package com.eduin.onboarding.session.dto;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.session.SessionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDetailResponse(
        UUID sessionId,
        String documentType,
        SessionStatus status,
        Instant createdAt,
        Instant expiresAt,
        Map<DocumentSide, SideSummary> sides,
        Consolidated consolidated,
        Decision decision) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SideSummary(
            String detectedType,
            Double classificationConfidence,
            Double authenticityScore,
            Boolean veto,
            Instant capturedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consolidated(
            Map<String, String> fields,
            List<CrossCheck> crossChecks,
            Double authenticityScore) {

        public record CrossCheck(String name, boolean passed, Integer distance) {
        }
    }

    public record Decision(String outcome, List<String> reasons) {
    }
}
