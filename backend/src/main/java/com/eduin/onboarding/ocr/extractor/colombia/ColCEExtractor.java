package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Cédula de Extranjería colombiana.
 * Emitida por Migración Colombia. Diseño moderno — label ANTES del valor.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 *   COL    REPÚBLICA DE COLOMBIA — Cédula de Extranjería
 *   RESIDENTE (o MIGRANTE)          No. 1432161
 *   APELLIDOS:  MEJIAS LINARES
 *   NOMBRES:    JESSEBELL CRISTINA
 *   NACIONALIDAD:  VEN
 *   FECHA DE NACIMIENTO:  1994/08/06   ← formato YYYY/MM/DD
 *   SEXO:  F     RH:  A+
 *   F. EXPEDICIÓN:  2025/02/27
 *   VENCE:  2030/02/24
 *
 * Reverso: MRZ TD1 (3 líneas × 30 chars)
 */
@Component
public class ColCEExtractor extends BaseExtractor {

    // Tipo de residente (RESIDENTE, MIGRANTE, TEMPORAL, PERMANENTE)
    private static final Pattern TIPO_RESIDENTE = Pattern.compile(
            "^(RESIDENTE|MIGRANTE|TEMPORAL|PERMANENTE)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // "No. 1432161" o "N°: 8182435"
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "N[o°º\\.][°º\\.]*[:\\s]*(\\d{4,12})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS?[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n|NOMBRES?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOMBRES = Pattern.compile(
            "NOMBRES?[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n|FECHA|N[o°º]|SEXO|NACION)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "NACIONALIDAD[:\\s]+([A-Z]{2,3})",
            Pattern.CASE_INSENSITIVE);

    // Fecha YYYY/MM/DD (formato real en CE colombiana)
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO[:\\s]+(\\d{4}/\\d{2}/\\d{2}|\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "SEXO[:\\s]+([MF])", Pattern.CASE_INSENSITIVE);

    private static final Pattern RH = Pattern.compile(
            "RH[:\\s]+([ABO]{1,2}[+-])", Pattern.CASE_INSENSITIVE);

    // "F. EXPEDICIÓN: 2025/02/27"
    private static final Pattern FECHA_EXP = Pattern.compile(
            "F\\.?\\s*EXPEDICI[OÓ]N[:\\s]+(\\d{4}/\\d{2}/\\d{2}|\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // "VENCE: 2030/02/24" o "F. VENCIMIENTO: 2027/09/11"
    private static final Pattern VIGENCIA = Pattern.compile(
            "(?:VENCE|F\\.?\\s*VENCIMIENTO)[:\\s]+(\\d{4}/\\d{2}/\\d{2}|\\d{2}[/\\-]\\d{2}[/\\-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.COL_CE;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("tipoResidente",   ocrText, TIPO_RESIDENTE, 0.92));
        fields.add(field("documentNumber",  ocrText, DOC_NUMBER,     0.92));
        fields.add(field("apellidos",       ocrText, APELLIDOS,      0.87));
        fields.add(field("nombres",         ocrText, NOMBRES,        0.87));
        fields.add(field("nacionalidad",    ocrText, NACIONALIDAD,   0.88));
        fields.add(field("fechaNacimiento", ocrText, FECHA_NAC,      0.89));
        fields.add(field("sexo",            ocrText, SEXO,           0.92));
        fields.add(field("rh",              ocrText, RH,             0.88));
        fields.add(field("fechaExpedicion", ocrText, FECHA_EXP,      0.88));
        fields.add(field("fechaVencimiento",ocrText, VIGENCIA,        0.88));

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "CEDULA DE EXTRANJERIA", "CÉDULA DE EXTRANJERÍA",
                "MIGRACION COLOMBIA", "MIGRACIÓN COLOMBIA",
                "RESIDENTE", "MIGRANTE");
        return Math.min(hits * 0.18, 1.0);
    }
}
