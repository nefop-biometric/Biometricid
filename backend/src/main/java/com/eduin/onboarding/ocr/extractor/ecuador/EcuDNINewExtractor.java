package com.eduin.onboarding.ocr.extractor.ecuador;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Cédula de Identidad Ecuatoriana (versión nueva — desde 2009).
 * Diseño tarjeta PVC azul con chip, QR y MRZ TD1 en el reverso.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 * Cara frontal:
 *   CÉDULA DE IDENTIDAD        REPÚBLICA DEL ECUADOR
 *   APELLIDOS        CONDICIÓN CIUDADANIA
 *   CARVAJAL
 *   DEL ROSARIO                ← dos líneas de apellidos
 *   NOMBRES
 *   DAVID SEBASTIAN
 *   NACIONALIDAD
 *   ECUATORIANA
 *   FECHA DE NACIMIENTO        SEXO
 *   10 NOV 2004                HOMBRE        ← formato DD MMM YYYY, sexo textual
 *   LUGAR DE NACIMIENTO
 *   PICHINCHA QUITO
 *   LA VICENTINA
 *   No. DOCUMENTO              FECHA DE VENCIMIENTO
 *   066425107                  28 DIC 2032
 *   NUI.1722067590             ← Número Único Identificador
 *
 * Cara posterior:
 *   APELLIDOS Y NOMBRES DEL PADRE:  CARVAJAL VERA FRANCISCO LEONARDO
 *   APELLIDOS Y NOMBRES DE LA MADRE: DEL ROSARIO BALON ANTONIA MARIBEL
 *   ESTADO CIVIL:  SOLTERO
 *   LUGAR Y FECHA DE EMISIÓN:  QUITO 28 DIC 2022 - DUPLICADO V
 *   CÓDIGO DACTILAR:  V3333V1222
 *   TIPO SANGRE:  O+
 *   DONANTE:  Si
 *   MRZ TD1 (3 líneas × 30 chars)
 */
@Component
public class EcuDNINewExtractor extends BaseExtractor {

    // NUI (Número Único Identificador): "NUI.1722067590" o "NUI 1722067590." (espacio en vez de punto)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "NUI[.\\s]+(\\d{10})", Pattern.CASE_INSENSITIVE);

    // "No. DOCUMENTO\n066425107" — número de documento impreso (diferente del NUI)
    // Tolera texto de otra columna fusionado por el OCR antes del número real
    private static final Pattern NUM_DOCUMENTO = Pattern.compile(
            "No\\.?\\s+DOCUMENTO[^\\n]*\\n[^\\n]*?(\\d{6,10})\\b", Pattern.CASE_INSENSITIVE);

    // Apellidos: label "APELLIDOS" (puede tener texto de otra columna en la misma línea,
    // p.ej. "APELLIDOS CONDICIÓN CIUDADANIA") luego 1 o 2 líneas hasta "NOMBRES".
    // Captura todo el bloque crudo (puede tener ruido OCR como "- " o "0." entre
    // líneas) y se limpia línea por línea en el código que lo consume.
    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS[^\\n]*\\n([\\s\\S]{1,80}?)NOMBRES",
            Pattern.CASE_INSENSITIVE);

    // Nombres — tolera un punto final que a veces añade el OCR ("DAVID SEBASTIAN.")
    private static final Pattern NOMBRES = Pattern.compile(
            "NOMBRES\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\.?(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "NACIONALIDAD\\s*\\n([A-ZÁÉÍÓÚÑ]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // "10 NOV 2004" — formato DD MMM YYYY con espacio
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO\\s*\\n(\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // Sexo textual HOMBRE/MUJER en la misma fila o siguiente a la fecha
    private static final Pattern SEXO = Pattern.compile(
            "SEXO\\s*\\n?(HOMBRE|MUJER|M|F)(?=\\s|\\n|$)",
            Pattern.CASE_INSENSITIVE);

    // Lugar de nacimiento: hasta 2 líneas
    private static final Pattern LUGAR_NAC = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // "28 DIC 2032" — tolera texto de otra columna fusionado antes de la fecha
    private static final Pattern FECHA_VEN = Pattern.compile(
            "FECHA\\s+DE\\s+VENCIMIENTO[^\\n]*\\n[^\\n]*?(\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // NAT/CAN (código para renovación)
    private static final Pattern NAT_CAN = Pattern.compile(
            "NAT/CAN\\s*\\n?(\\d{6})", Pattern.CASE_INSENSITIVE);

    // Reverso
    private static final Pattern PADRE = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s+DEL\\s+PADRE[:\\s]+\\n?([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MADRE = Pattern.compile(
            "APELLIDOS\\s+Y\\s+NOMBRES\\s+DE\\s+LA\\s+MADRE[:\\s]+\\n?([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ESTADO_CIVIL = Pattern.compile(
            "ESTADO\\s+CIVIL\\s*\\n?([A-ZÁÉÍÓÚÑ]+?)(?=\\n)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TIPO_SANGRE = Pattern.compile(
            "TIPO\\s+SANGRE\\s+([ABO]{1,2}[+-])", Pattern.CASE_INSENSITIVE);

    private static final Pattern COD_DACTILAR = Pattern.compile(
            "([VIC]\\d{4}[VIC]\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final Pattern DONANTE = Pattern.compile(
            "DONANTE\\s+(S[Ii]|NO)", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.ECU_DNI_NEW;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // NUI como número de documento principal
        fields.add(field("documentNumber", ocrText, DOC_NUMBER,    0.94));
        fields.add(field("numDocumento",   ocrText, NUM_DOCUMENTO, 0.88));

        // Apellidos: el bloque crudo puede traer ruido OCR entre líneas (p.ej. "- ", "0."
        // o la línea completa "NUI 1722067590" que a veces queda intercalada).
        // Se limpia línea por línea: descarta líneas con dígitos (nunca son apellidos)
        // y quita lo que no sea letra/espacio del resto.
        extract(ocrText, APELLIDOS).ifPresent(v -> {
            String norm = java.util.Arrays.stream(v.split("\\n"))
                    .filter(line -> !line.matches(".*\\d.*"))
                    .map(line -> line.replaceAll("[^A-ZÁÉÍÓÚÑa-záéíóúñ\\s]", "").trim())
                    .filter(line -> line.length() >= 2 && !line.equalsIgnoreCase("NUI"))
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
            if (!norm.isBlank()) fields.add(ExtractedField.of("apellidos", norm, 0.85));
        });

        fields.add(field("nombres",          ocrText, NOMBRES,      0.88));
        fields.add(field("nacionalidad",     ocrText, NACIONALIDAD, 0.85));
        fields.add(field("lugarNacimiento",  ocrText, LUGAR_NAC,    0.83));
        fields.add(field("fechaNacimiento",  ocrText, FECHA_NAC,    0.90));
        fields.add(field("fechaVencimiento", ocrText, FECHA_VEN,    0.90));
        fields.add(field("natCan",           ocrText, NAT_CAN,      0.82));
        fields.add(field("padre",            ocrText, PADRE,        0.82));
        fields.add(field("madre",            ocrText, MADRE,        0.82));
        fields.add(field("estadoCivil",      ocrText, ESTADO_CIVIL, 0.84));
        fields.add(field("tipoSangre",       ocrText, TIPO_SANGRE,  0.87));
        fields.add(field("codigoDactilar",   ocrText, COD_DACTILAR, 0.85));
        fields.add(field("donante",          ocrText, DONANTE,      0.84));

        // Sexo: HOMBRE → M, MUJER → F
        extract(ocrText, SEXO).ifPresent(v -> {
            String norm = v.toUpperCase().startsWith("H") ? "M"
                        : v.toUpperCase().startsWith("MU") ? "F" : v.toUpperCase();
            fields.add(ExtractedField.of("sexo", norm, 0.90));
        });

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        // Validar NUI con módulo 10
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
                "CEDULA DE IDENTIDAD", "CÉDULA DE IDENTIDAD",
                "NUI", "FECHA DE VENCIMIENTO", "NAT/CAN",
                "CONDICION CIUDADANIA", "LUGAR Y FECHA DE EMISION");
        return Math.min(hits * 0.13, 1.0);
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
