package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Tarjeta de Identidad Colombiana (menores de 14 a 17 años).
 *
 * LAYOUT REAL — valor ANTES del label (igual que CC antigua):
 *
 * Cara frontal:
 *   REPUBLICA DE COLOMBIA / TARJETA DE IDENTIDAD
 *   NUMERO  961121-14740      ← formato yymmdd-secuencia (TI antigua)
 *   NUMERO  1.062.300.177     ← formato con puntos (TI moderna, igual que CC)
 *   MEDINA TOLOSA             ← apellidos
 *   APELLIDOS
 *   ADRIAN LEONIDAS           ← nombres
 *   NOMBRES
 *
 * Cara posterior:
 *   FECHA DE NACIMIENTO   21-NOV-1996
 *   ARCABUCO
 *   (BOYACA)
 *   LUGAR DE NACIMIENTO
 *   21-NOV-2014   B+   M      ← FECHA DE VENCIMIENTO + sangre + sexo en una fila
 *   FECHA DE VENCIMIENTO   G.S.RH   SEXO
 *   06-DIC-2010  ARCABUCO
 *   FECHA Y LUGAR DE EXPEDICION
 */
@Component
public class ColTIExtractor extends BaseExtractor {

    // Acepta "961121-14740" (yymmdd-seq) y "1.062.300.177" (puntos)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "NUMERO\\s+([\\d.]{6,15}|\\d{6}-\\d{4,5})", Pattern.CASE_INSENSITIVE);

    private static final Pattern APELLIDOS = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\s*\\n\\s*APELLIDOS",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern NOMBRES = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\s*\\n\\s*NOMBRES",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO\\s+(\\d{2}-[A-Z]{3}-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_NAC = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\s*\\n\\s*\\([^)]+\\)\\s*\\n\\s*LUGAR\\s+DE\\s+NACIMIENTO",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPTO_NAC = Pattern.compile(
            "\\(([A-ZÁÉÍÓÚÑ]+)\\)\\s*\\n\\s*LUGAR\\s+DE\\s+NACIMIENTO",
            Pattern.CASE_INSENSITIVE);

    // Fila "21-NOV-2014  B+  M" con "FECHA DE VENCIMIENTO  G.S.RH  SEXO" debajo
    private static final Pattern FECHA_VEN = Pattern.compile(
            "(\\d{2}-[A-Z]{3}-\\d{4})\\s+[ABO]{1,2}[+-]\\s+[MF]\\s*\\n\\s*FECHA\\s+DE\\s+VENCIMIENTO",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SANGRE = Pattern.compile(
            "\\d{2}-[A-Z]{3}-\\d{4}\\s+([ABO]{1,2}[+-])\\s+[MF]\\s*\\n\\s*FECHA\\s+DE\\s+VENCIMIENTO",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "\\d{2}-[A-Z]{3}-\\d{4}\\s+[ABO]{1,2}[+-]\\s+([MF])\\s*\\n\\s*FECHA\\s+DE\\s+VENCIMIENTO",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EXP = Pattern.compile(
            "(\\d{2}-[A-Z]{3}-\\d{4})\\s+[A-ZÁÉÍÓÚÑ]+\\s*\\n\\s*FECHA\\s+Y\\s+LUGAR\\s+DE\\s+EXPEDICION",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_EXP = Pattern.compile(
            "\\d{2}-[A-Z]{3}-\\d{4}\\s+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\s*\\n\\s*FECHA\\s+Y\\s+LUGAR\\s+DE\\s+EXPEDICION",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CODIGO_INF   = Pattern.compile(
            "([APE]-\\d{7}-\\d{8}-[MF]-\\d{7,13}-\\d{8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOC_FROM_COD = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-[MF]-(\\d{7,13})-\\d{8}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEX_FROM_COD = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-([MF])-\\d{7,13}-\\d{8}", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEXP_FROM_COD = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-[MF]-\\d{7,13}-(\\d{8})", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.COL_TI;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // Número: quitar puntos si existen
        extract(ocrText, DOC_NUMBER).ifPresent(v ->
                fields.add(ExtractedField.of("documentNumber",
                        v.replaceAll("\\.", ""), 0.92)));

        fields.add(field("apellidos",        ocrText, APELLIDOS,  0.87));
        fields.add(field("nombres",          ocrText, NOMBRES,    0.87));
        fields.add(field("fechaNacimiento",  ocrText, FECHA_NAC,  0.90));
        fields.add(field("lugarNacimiento",  ocrText, LUGAR_NAC,  0.82));
        fields.add(field("departamento",     ocrText, DEPTO_NAC,  0.80));
        fields.add(field("fechaVencimiento", ocrText, FECHA_VEN,  0.90));
        fields.add(field("grupoSanguineo",   ocrText, SANGRE,     0.88));
        fields.add(field("sexo",             ocrText, SEXO,       0.92));
        fields.add(field("fechaExpedicion",  ocrText, FECHA_EXP,  0.88));
        fields.add(field("lugarExpedicion",  ocrText, LUGAR_EXP,  0.80));

        extract(ocrText, CODIGO_INF).ifPresent(codigo -> {
            fields.add(ExtractedField.of("codigoInferior", codigo, 0.95));
            extract(ocrText, DOC_FROM_COD).ifPresent(n -> {
                String clean = n.replaceFirst("^0+", "");
                fields.stream().filter(f -> "documentNumber".equals(f.getFieldName()) && f.getValue() == null)
                      .findFirst().ifPresent(f -> { f.setValue(clean); f.setConfidence(0.97); });
            });
            extract(ocrText, SEX_FROM_COD).ifPresent(s ->
                    fields.stream().filter(f -> "sexo".equals(f.getFieldName()) && f.getValue() == null)
                          .findFirst().ifPresent(f -> { f.setValue(s); f.setConfidence(0.97); }));
            extract(ocrText, FEXP_FROM_COD).ifPresent(fc -> {
                if (fc.length() == 8) {
                    String n = fc.substring(0,4)+"-"+fc.substring(4,6)+"-"+fc.substring(6,8);
                    fields.add(ExtractedField.of("fechaGeneracionCodigo", n, 0.96));
                }
            });
        });

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null
                      && !f.getFieldName().startsWith("alerta"))
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "TARJETA DE IDENTIDAD", "REPUBLICA DE COLOMBIA",
                "IDENTIFICACION PERSONAL", "REGISTRADURIA",
                "REGISTRADOR NACIONAL", "INDICE DERECHO",
                "FECHA Y LUGAR DE EXPEDICION", "FECHA DE VENCIMIENTO");
        return Math.min(hits * 0.14, 1.0);
    }
}
