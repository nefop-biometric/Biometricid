package com.eduin.onboarding.session.dto;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.processing.AuthenticityResult;
import com.eduin.onboarding.processing.ClassificationResult;
import com.eduin.onboarding.processing.OcrResult;
import com.eduin.onboarding.session.SessionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SideResultResponse(
        DocumentSide side,
        ClassificationResult classification,
        OcrResult ocr,
        AuthenticityResult authenticity,
        SessionStatus sessionStatus) {
}
