package com.eduin.onboarding.processing;

public record ClassificationResult(
        String detectedType,
        boolean matchesSession,
        double confidence) {
}
