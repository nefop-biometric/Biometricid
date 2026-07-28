package com.eduin.onboarding.processing;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import org.springframework.stereotype.Service;

/**
 * Implementación temporal mientras se porta el módulo antifraude real.
 * No emite score ni veto (null = análisis no disponible todavía).
 */
@Service
public class NoOpAuthenticityAnalyzer implements AuthenticityAnalyzer {

    @Override
    public AuthenticityResult analyze(byte[] image, DocumentTypeSpec type, DocumentSide side) {
        return null;
    }
}
