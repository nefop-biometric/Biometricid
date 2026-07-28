package com.eduin.onboarding.ocr.extractor.ecuador;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Cédula de Identidad Ecuatoriana (versión antigua — hasta 2009).
 * Emitida por la Dirección General de Registro Civil, Identificación y Cedulación.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 * Cara frontal:
 *   REPÚBLICA DEL ECUADOR
 *   DIRECCIÓN GENERAL DE REGISTRO CIVIL, IDENTIFICACIÓN Y CEDULACIÓN
 *   CÉDULA DE CIUDADANO           No. 0703509653
 *   APELLIDOS Y NOMBRES           ← sección única (no separados)
 *   VARGAS SALVATIERRA            ← apellidos (primera línea)
 *   LUIS PAUL                     ← nombres (segunda línea)
 *   LUGAR DE NACIMIENTO
 *   EL ORO / MACHALA / MACHALA   ← provincia / cantón / parroquia
 *   FECHA DE NACIMIENTO 1976-05-05   ← formato YYYY-MM-DD (ISO)
 *   NACIONALIDAD ECUATORIANA
 *   SEXO HOMBRE                  ← "HOMBRE" o "MUJER" (no M/F)
 *   ESTADO CIVIL CASADO
 *
 * Cara posterior:
 *   INSTRUCCIÓN      PROFESIÓN/OCUPACIÓN
 *   BACHILLERATO     EMPLEADO PUBLICO
 *   APELLIDOS Y NOMBRES DEL PADRE:  VARGAS LUIS
 *   APELLIDOS Y NOMBRES DE LA MADRE: SALVATIERRA ELSA
 *   LUGAR Y FECHA DE EXPEDICIÓN:   LOJA / 2016-10-31
 *   FECHA DE EXPIRACIÓN:           2026-10-31
 */
@Component
public class EcuDNIOldExtractor extends BaseExtractor {

    // "No. 0703509653" (10 dígitos)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "No\\.?\\s+(\\d{10})", Pattern.CASE_INSENSITIVE);

    // Sección "APELLIDOS Y NOMBRES" — primera línea = apellidos
    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // Segunda línea después de "APELLIDOS Y NOMBRES" = nombres
    private static final Pattern NOMBRES = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s*\\n[A-ZÁÉÍÓÚÑ][^\\n]+\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // Lugar de nacimiento: tres líneas (provincia / cantón / parroquia)
    private static final Pattern LUGAR_NAC_PROV = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_NAC_CANTON = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO\\s*\\n[^\\n]+\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // Fecha en formato YYYY-MM-DD (ISO — ya es correcto)
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO\\s+(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "NACIONALIDAD\\s+([A-ZÁÉÍÓÚÑ]+?)(?=\\n|SEXO)",
            Pattern.CASE_INSENSITIVE);

    // "SEXO HOMBRE" o "SEXO MUJER" → normalizar a M/F
    private static final Pattern SEXO = Pattern.compile(
            "SEXO\\s+(HOMBRE|MUJER|M|F)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ESTADO_CIVIL = Pattern.compile(
            "ESTADO\\s+CIVIL\\s+([A-ZÁÉÍÓÚÑ]+?)(?=\\n)", Pattern.CASE_INSENSITIVE);

    // Reverso
    private static final Pattern INSTRUCCION = Pattern.compile(
            "^INSTRUCCI[OÓ]N\\s*\\n([A-ZÁÉÍÓÚÑ]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern PROFESION = Pattern.compile(
            "PROFESI[OÓ]N\\s*/\\s*OCUPACI[OÓ]N\\s*\\n([A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PADRE = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s+DEL\\s+PADRE[:\\s]+\\n?([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MADRE = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s+DE\\s+LA\\s+MADRE[:\\s]+\\n?([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_EXP = Pattern.compile(
            "LUGAR\\s+Y\\s+FECHA\\s+DE\\s+EXPEDICI[OÓ]N[:\\s]*\\n([A-ZÁÉÍÓÚÑ]+)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EXP = Pattern.compile(
            "LUGAR\\s+Y\\s+FECHA\\s+DE\\s+EXPEDICI[OÓ]N[:\\s]*\\n[A-ZÁÉÍÓÚÑ]+\\n(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EXP_DOC = Pattern.compile(
            "FECHA\\s+DE\\s+EXPIRACI[OÓ]N[:\\s]*(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);

    // Código dactilar
    private static final Pattern COD_DACTILAR = Pattern.compile(
            "([VIC]\\d{4}[VIC]\\d{4})", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.ECU_DNI_OLD;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("documentNumber",   ocrText, DOC_NUMBER,      0.93));
        fields.add(field("apellidos",        ocrText, APELLIDOS,       0.87));
        fields.add(field("nombres",          ocrText, NOMBRES,         0.87));
        fields.add(field("provincia",        ocrText, LUGAR_NAC_PROV,  0.82));
        fields.add(field("canton",           ocrText, LUGAR_NAC_CANTON,0.82));
        fields.add(field("fechaNacimiento",  ocrText, FECHA_NAC,       0.90));
        fields.add(field("nacionalidad",     ocrText, NACIONALIDAD,    0.85));
        fields.add(field("estadoCivil",      ocrText, ESTADO_CIVIL,    0.84));
        fields.add(field("instruccion",      ocrText, INSTRUCCION,     0.80));
        fields.add(field("profesion",        ocrText, PROFESION,       0.78));
        fields.add(field("padre",            ocrText, PADRE,           0.82));
        fields.add(field("madre",            ocrText, MADRE,           0.82));
        fields.add(field("lugarExpedicion",  ocrText, LUGAR_EXP,       0.82));
        fields.add(field("fechaExpedicion",  ocrText, FECHA_EXP,       0.88));
        fields.add(field("fechaExpiracion",  ocrText, FECHA_EXP_DOC,   0.90));
        fields.add(field("codigoDactilar",   ocrText, COD_DACTILAR,    0.85));

        // Sexo: HOMBRE → M, MUJER → F
        extract(ocrText, SEXO).ifPresent(v -> {
            String norm = v.toUpperCase().startsWith("H") ? "M"
                        : v.toUpperCase().startsWith("MU") ? "F" : v.toUpperCase();
            fields.add(ExtractedField.of("sexo", norm, 0.90));
        });

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        // Validar cédula ecuatoriana (módulo 10)
        fields.stream()
              .filter(f -> "documentNumber".equals(f.getFieldName()) && f.getValue() != null)
              .findFirst().ifPresent(f -> {
                  if (!validarCedula(f.getValue()))
                      fields.add(ExtractedField.of("alertaDocumento",
                              "Dígito verificador inválido: " + f.getValue(), 1.0));
              });

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLICA DEL ECUADOR", "REPÚBLICA DEL ECUADOR",
                "REGISTRO CIVIL", "CEDULA DE CIUDADANO",
                "CÉDULA DE CIUDADANO", "ESTADO CIVIL",
                "APELLIDOS Y NOMBRES", "LUGAR DE NACIMIENTO");
        boolean isNew = ocrText.toUpperCase().contains("VENCIMIENTO")
                || ocrText.toUpperCase().contains("NUI");
        double score = hits * 0.13;
        if (isNew) score -= 0.2;
        return Math.min(Math.max(score, 0.0), 1.0);
    }

    private boolean validarCedula(String cedula) {
        if (cedula == null || !cedula.matches("\\d{10}")) return false;
        int[] coef = {2,1,2,1,2,1,2,1,2};
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int val = Character.getNumericValue(cedula.charAt(i)) * coef[i];
            if (val >= 10) val -= 9;
            suma += val;
        }
        int calc = suma % 10 == 0 ? 0 : 10 - (suma % 10);
        return calc == Character.getNumericValue(cedula.charAt(9));
    }
}
