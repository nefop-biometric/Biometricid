package com.eduin.onboarding.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        UUID sessionId,
        Map<String, Object> details) {

    public static ApiErrorResponse of(ErrorCode code, String message, UUID sessionId, Map<String, Object> details) {
        return new ApiErrorResponse(Instant.now(), code.status().value(), code.name(), message, sessionId, details);
    }
}
