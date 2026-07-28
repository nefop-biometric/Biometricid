package com.eduin.onboarding.authenticity.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class VerificationResult {

    private String requestId;
    private Instant timestamp;
    private DocumentType documentType;

    // Score global de autenticidad (0.0 = falso, 1.0 = auténtico)
    private double authenticityScore;
    private String verdict;             // AUTHENTIC | SUSPICIOUS | FRAUDULENT
    private boolean authentic;

    // Detalle por cara del documento
    private SideResult frontResult;
    private SideResult backResult;

    // Datos extraídos por OCR
    private Map<String, String> extractedData;

    // MRZ solo para pasaportes
    private MrzResult mrzResult;

    @Data
    @Builder
    public static class SideResult {
        private DocumentSide side;
        private double score;
        private List<AnalysisDetail> analyses;
    }

    @Data
    @Builder
    public static class MrzResult {
        private boolean detected;
        private boolean valid;
        private String rawLine1;
        private String rawLine2;
        private String documentNumber;
        private String nationality;
        private String dateOfBirth;
        private String expiryDate;
        private String surname;
        private String givenNames;
        private boolean checkDigitsValid;
        private String checksumErrors;
        /** true si se detectó un MRZ pero de otro tipo de documento (ej: TD1 de cédula en un pasaporte). */
        private boolean wrongFormat;
    }
}
