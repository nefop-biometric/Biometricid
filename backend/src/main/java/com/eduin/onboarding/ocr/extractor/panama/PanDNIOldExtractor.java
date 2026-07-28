package com.eduin.onboarding.ocr.extractor.panama;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor para Cédula de Identidad Panameña (versión antigua — hasta ~2009).
 * Emitida por el Tribunal Electoral de Panamá.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 *   REPÚBLICA DE PANAMÁ
 *   TRIBUNAL ELECTORAL
 *
 *   [foto grande]   [foto pequeña]
 *   Oriel Delfino        ← NOMBRES (primera línea, sin label)
 *   Miller Harding       ← APELLIDOS (segunda línea, sin label)
 *
 *   NOMBRE USUAL:        ← etiqueta sin valor visible (opcional)
 *   FECHA DE NACIMIENTO: 28-JUN-1989
 *   LUGAR DE NACIMIENTO: PANAMÁ, PANAMÁ
 *   SEXO: M      TIPO DE SANGRE:
 *                               8-826-2464   ← número en esquina derecha
 *   EXPEDIDA: 01-DIC-2015   EXPIRA: 01-DIC-2025
 *
 * Características del layout:
 * - Los nombres y apellidos NO tienen labels explícitos.
 * - Aparecen como las primeras 2 líneas con texto en mayúsculas después del header.
 * - El número de cédula aparece aislado en la esquina derecha.
 * - Las fechas usan dd-MMM-yyyy con meses en español (DIC, JUN, etc.).
 */
@Component
public class PanDNIOldExtractor extends BaseExtractor {

    // Número de cédula en la esquina: "8-826-2464", "4-123-456", "N-E-12-3456"
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "\\b([0-9NE][A-Z0-9]*(?:-[A-Z0-9]+){1,3})\\b(?=\\s*\\n|\\s*$)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Los nombres aparecen en las 2 líneas inmediatamente ANTES de "NOMBRE USUAL"
    // (anclar en "TRIBUNAL ELECTORAL" no es confiable: el OCR a menudo lo destroza
    // por completo, p.ej. queda solo "CTORAL"; "NOMBRE USUAL" sobrevive mejor).
    private static final Pattern NOMBRES_APELLIDOS = Pattern.compile(
            "([A-Za-zÁÉÍÓÚÑáéíóúñ][a-záéíóúñA-ZÁÉÍÓÚÑ ]+?)\\n([A-Za-zÁÉÍÓÚÑáéíóúñ][a-záéíóúñA-ZÁÉÍÓÚÑ ]+?)\\n[\\s]*NOMBRE\\s+USUAL",
            Pattern.CASE_INSENSITIVE);

    // Fecha: "FECHA DE NACIMIENTO: 28-JUN-1989"
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO[:\\s]+(\\d{2}-[A-Z]{3}-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // Lugar: "LUGAR DE NACIMIENTO: PANAMÁ, PANAMÁ"
    private static final Pattern LUGAR_NAC = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s,]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "SEXO[:\\s]+([MF])", Pattern.CASE_INSENSITIVE);

    private static final Pattern SANGRE = Pattern.compile(
            "TIPO\\s+DE\\s+SANGRE[:\\s]+([ABO]{1,2}[+-])", Pattern.CASE_INSENSITIVE);

    // "EXPEDIDA: 01-DIC-2015"
    private static final Pattern FECHA_EXP = Pattern.compile(
            "EXPEDIDA[:\\s]+(\\d{2}-[A-Z]{3}-\\d{4})", Pattern.CASE_INSENSITIVE);

    // "EXPIRA: 01-DIC-2025"
    private static final Pattern FECHA_VEN = Pattern.compile(
            "EXPIRA[:\\s]+(\\d{2}-[A-Z]{3}-\\d{4})", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.PAN_DNI_OLD;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // Número de cédula (buscamos el patrón X-XXX-XXXX aislado)
        fields.add(field("documentNumber",  ocrText, DOC_NUMBER,  0.90));

        // Nombres y apellidos (2 líneas sin labels después del header)
        Matcher m = NOMBRES_APELLIDOS.matcher(ocrText);
        if (m.find()) {
            fields.add(ExtractedField.of("nombres",   m.group(1).trim(), 0.82));
            fields.add(ExtractedField.of("apellidos", m.group(2).trim(), 0.82));
        }

        fields.add(field("fechaNacimiento", ocrText, FECHA_NAC,   0.89));
        fields.add(field("lugarNacimiento", ocrText, LUGAR_NAC,   0.83));
        fields.add(field("sexo",            ocrText, SEXO,        0.93));
        fields.add(field("tipoSangre",      ocrText, SANGRE,      0.82));
        fields.add(field("fechaExpedicion", ocrText, FECHA_EXP,   0.88));
        fields.add(field("fechaVencimiento",ocrText, FECHA_VEN,   0.88));

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLICA DE PANAMA", "REPÚBLICA DE PANAMÁ",
                "TRIBUNAL ELECTORAL",
                "FECHA DE NACIMIENTO", "EXPEDIDA", "EXPIRA");
        // La nueva panameña tiene "DOCUMENTO DE IDENTIDAD" en lugar de "CÉDULA DE IDENTIDAD"
        boolean isNew = ocrText.toUpperCase().contains("DOCUMENTO DE IDENTIDAD")
                || ocrText.toUpperCase().contains("NOMBRE USUAL");
        double score = hits * 0.17;
        // Ambas versiones tienen NOMBRE USUAL — pero la antigua NO tiene NUIP ni chip
        return Math.min(Math.max(score, 0.0), 1.0);
    }
}
