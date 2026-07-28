package com.eduin.onboarding.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank(message = "documentType es obligatorio") String documentType,
        @Valid Metadata metadata) {

    public record Metadata(
            @Valid Geolocation geolocation,
            @Size(max = 512) String userAgent) {
    }

    public record Geolocation(
            @NotNull @Min(-90) @Max(90) Double lat,
            @NotNull @Min(-180) @Max(180) Double lng) {
    }
}
