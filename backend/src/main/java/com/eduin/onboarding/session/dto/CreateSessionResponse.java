package com.eduin.onboarding.session.dto;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.session.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateSessionResponse(
        UUID sessionId,
        String documentType,
        List<DocumentSide> requiredSides,
        SessionStatus status,
        Instant expiresAt) {
}
