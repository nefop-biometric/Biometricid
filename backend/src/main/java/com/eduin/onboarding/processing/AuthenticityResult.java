package com.eduin.onboarding.processing;

import java.util.List;

public record AuthenticityResult(
        double score,
        boolean veto,
        List<Check> checks) {

    public record Check(String name, double score, boolean passed) {
    }
}
