package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Permiso por Protección Temporal (PPT) colombiano.
 * Emitido por Migración Colombia para regularizar venezolanos.
 * Documento tipo tarjeta, diseño moderno — label ANTES del valor.
 *
 * LAYOUT REAL:
 *   PERMISO POR PROTECCIÓN TEMPORAL
 *   APELLIDOS:    GARCIA PEREZ
 *   NOMBRES:      LUIS ANTONIO
 *   FECHA DE NACIMIENTO:  15/03/1990
 *   NACIONALIDAD:         VENEZOLANA
 *   SEXO:                 M
 *   N° PPT:               123456789
 *   FECHA DE EXPEDICIÓN:  01/01/2022
 *   VIGENTE HASTA:        31/12/2023
 *   DOCUMENTO DE ORIGEN:  V-12345678 (cédula venezolana)
 */
@Component
public class ColPPTExtractor extends BaseExtractor {

    private static final Pattern DOC_NUMBER = Pattern.compile(
            "(?:N[°º\\.]*\\s*PPT|PERMISO.*?N[°º\\.]*)[:\\s]*(\\d{6,12})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS?[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n|NOMBRES?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOMBRES = Pattern.compile(
            "NOMBRES?[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n|FECHA|N[°º])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO[:\\s]+(\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "NACIONALIDAD[:\\s]+([A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "SEXO[:\\s]+([MF])", Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EXP = Pattern.compile(
            "FECHA\\s+DE\\s+EXPEDICI[OÓ]N[:\\s]+(\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VIGENCIA = Pattern.compile(
            "(?:VIGENTE\\s+HASTA|VÁLIDO\\s+HASTA|VIGENCIA)[:\\s]+(\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // Cédula o pasaporte venezolano de origen (V-12345678, E-87654321, PA-AB123456)
    private static final Pattern DOC_ORIGEN = Pattern.compile(
            "(?:DOCUMENTO\\s+DE\\s+ORIGEN|PASAPORTE|CÉDULA)[:\\s]+([VEJ]-?\\d{6,9}|[A-Z]{2}\\d{6,8})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.COL_PPT;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("documentNumber",  ocrText, DOC_NUMBER,   0.92));
        fields.add(field("apellidos",       ocrText, APELLIDOS,    0.87));
        fields.add(field("nombres",         ocrText, NOMBRES,      0.87));
        fields.add(field("fechaNacimiento", ocrText, FECHA_NAC,    0.89));
        fields.add(field("nacionalidad",    ocrText, NACIONALIDAD, 0.85));
        fields.add(field("sexo",            ocrText, SEXO,         0.92));
        fields.add(field("fechaExpedicion", ocrText, FECHA_EXP,    0.88));
        fields.add(field("vigencia",        ocrText, VIGENCIA,     0.88));
        fields.add(field("documentoOrigen", ocrText, DOC_ORIGEN,   0.80));

        fields.stream()
              .filter(f -> (f.getFieldName().startsWith("fecha")
                      || f.getFieldName().equals("vigencia")) && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "PERMISO POR PROTECCION TEMPORAL",
                "PERMISO POR PROTECCIÓN TEMPORAL",
                "PPT", "ESTATUTO TEMPORAL",
                "MIGRACION COLOMBIA", "MIGRACIÓN COLOMBIA",
                "VENEZUELA", "VENEZOLAN");
        return Math.min(hits * 0.15, 1.0);
    }
}
