package com.eduin.onboarding.common.error;

import java.util.Map;
import java.util.UUID;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final UUID sessionId;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ApiException(ErrorCode code, String message, UUID sessionId) {
        this(code, message, sessionId, null);
    }

    public ApiException(ErrorCode code, String message, UUID sessionId, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.sessionId = sessionId;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public Map<String, Object> details() {
        return details;
    }
}
