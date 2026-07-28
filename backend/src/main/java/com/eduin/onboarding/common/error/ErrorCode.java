package com.eduin.onboarding.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    SESSION_EXPIRED(HttpStatus.GONE),
    SESSION_ALREADY_COMPLETED(HttpStatus.CONFLICT),
    SIDE_ALREADY_PROCESSED(HttpStatus.CONFLICT),
    UNSUPPORTED_DOCUMENT_TYPE(HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_SIDE(HttpStatus.UNPROCESSABLE_ENTITY),
    IMAGE_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY),
    DOCUMENT_TYPE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    IMAGE_QUALITY_TOO_LOW(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
