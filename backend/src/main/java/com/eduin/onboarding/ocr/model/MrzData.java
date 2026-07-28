package com.eduin.onboarding.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MrzData {

    private String mrzType;         // TD1 (cedulas 3 lineas), TD3 (pasaportes 2 lineas)
    private String rawLine1;
    private String rawLine2;
    private String rawLine3;

    // Campos comunes MRZ
    private String documentCode;
    private String issuingCountry;
    private String surnames;
    private String givenNames;
    private String documentNumber;
    private String documentNumberCheckDigit;
    private boolean documentNumberValid;
    private String nationality;
    private String dateOfBirth;        // YYMMDD
    private String dateOfBirthCheckDigit;
    private boolean dateOfBirthValid;
    private String sex;
    private String expiryDate;         // YYMMDD
    private String expiryDateCheckDigit;
    private boolean expiryDateValid;
    private String optionalData1;
    private String optionalData2;
    private String compositeCheckDigit;
    private boolean compositeValid;
    private boolean allCheckDigitsValid;
}
