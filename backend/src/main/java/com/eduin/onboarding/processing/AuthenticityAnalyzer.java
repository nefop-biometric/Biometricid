package com.eduin.onboarding.processing;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;

/**
 * Punto de extensión del módulo antifraude (OpenCV: detección de montaje, recorte,
 * rostro, anillo alrededor de la cabeza). La implementación real portará las reglas
 * del proyecto anterior: veto (crítico &lt;0.60 cards / &lt;0.35 pasaportes) y peor cara.
 */
public interface AuthenticityAnalyzer {

    AuthenticityResult analyze(byte[] image, DocumentTypeSpec type, DocumentSide side);
}
