package com.eduin.onboarding.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Especificación de un tipo de documento soportado.
 * mrzFormat: "TD1" | "TD3" | null (sin MRZ o por confirmar con imágenes reales).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentTypeSpec(
        String code,
        String country,
        String name,
        List<DocumentSide> sides,
        boolean hasMrz,
        String mrzFormat,
        boolean supportsNfc,
        boolean expires) {
}
