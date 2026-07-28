package com.eduin.onboarding.ocr.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DocumentType {

    // Colombia
    COL_CC_OLD("COL_CC_OLD", "Cédula de Ciudadanía Colombiana (versión antigua)", "COL", false),
    COL_CC_NEW("COL_CC_NEW", "Cédula de Ciudadanía Colombiana (versión nueva/digital)", "COL", false),
    COL_PA("COL_PA",   "Pasaporte Colombiano", "COL", true),
    COL_TI("COL_TI",   "Tarjeta de Identidad Colombiana", "COL", false),
    COL_PPT("COL_PPT", "Permiso por Protección Temporal", "COL", false),
    COL_CE("COL_CE",   "Cédula de Extranjería", "COL", false),

    // España
    ESP_DNI_OLD("ESP_DNI_OLD", "DNI Español (versión antigua)", "ESP", false),
    ESP_DNI_NEW("ESP_DNI_NEW", "DNI Español (versión nueva)", "ESP", true),

    // Ecuador
    ECU_DNI_OLD("ECU_DNI_OLD", "Cédula Ecuatoriana (versión antigua)", "ECU", false),
    ECU_DNI_NEW("ECU_DNI_NEW", "Cédula Ecuatoriana (versión nueva)", "ECU", false),

    // Perú
    PER_DNI_OLD("PER_DNI_OLD", "DNI Peruano (versión antigua)", "PER", false),
    PER_DNI_NEW("PER_DNI_NEW", "DNI Peruano (versión nueva)", "PER", false),

    // Panamá
    PAN_DNI_OLD("PAN_DNI_OLD", "Cédula Panameña (versión antigua)", "PAN", false),
    PAN_DNI_NEW("PAN_DNI_NEW", "Cédula Panameña (versión nueva)", "PAN", false),

    UNKNOWN("UNKNOWN", "Documento desconocido", "UNK", false);

    private final String code;
    private final String description;
    private final String country;
    private final boolean hasMrz;
}
