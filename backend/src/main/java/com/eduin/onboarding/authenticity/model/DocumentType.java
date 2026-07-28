package com.eduin.onboarding.authenticity.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DocumentType {

    // Colombia
    COL_CC_OLD("COL_CC_OLD", "Cédula de Ciudadanía Colombiana (versión antigua)", "COL"),
    COL_CC_NEW("COL_CC_NEW", "Cédula de Ciudadanía Colombiana (versión nueva)", "COL"),
    COL_PA("COL_PA",   "Pasaporte Colombiano", "COL"),
    COL_TI("COL_TI",   "Tarjeta de Identidad Colombiana", "COL"),
    COL_PPT("COL_PPT", "Permiso por Protección Temporal (Colombia)", "COL"),
    COL_CE("COL_CE",   "Cédula de Extranjería (Colombia)", "COL"),

    // España
    ESP_DNI_OLD("ESP_DNI_OLD", "DNI Español (versión antigua)", "ESP"),
    ESP_DNI_NEW("ESP_DNI_NEW", "DNI Español (versión nueva)", "ESP"),

    // Ecuador
    ECU_DNI_OLD("ECU_DNI_OLD", "DNI Ecuatoriano (versión antigua)", "ECU"),
    ECU_DNI_NEW("ECU_DNI_NEW", "DNI Ecuatoriano (versión nueva)", "ECU"),

    // Perú
    PER_DNI_OLD("PER_DNI_OLD", "DNI Peruano (versión antigua)", "PER"),
    PER_DNI_NEW("PER_DNI_NEW", "DNI Peruano (versión nueva)", "PER"),

    // Panamá
    PAN_DNI_OLD("PAN_DNI_OLD", "DNI Panameño (versión antigua)", "PAN"),
    PAN_DNI_NEW("PAN_DNI_NEW", "DNI Panameño (versión nueva)", "PAN");

    private final String code;
    private final String description;
    private final String countryCode;

    DocumentType(String code, String description, String countryCode) {
        this.code = code;
        this.description = description;
        this.countryCode = countryCode;
    }

    @JsonValue
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public String getCountryCode() { return countryCode; }

    public boolean isPassport() {
        return this == COL_PA;
    }

    public boolean hasNfc() {
        return this == COL_PA;
    }

    public boolean isTwoSided() {
        return this != COL_PA;
    }
}
