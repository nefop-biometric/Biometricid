package com.eduin.onboarding.ocr.extractor.peru;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para DNI Peruano (versión nueva — desde 2013).
 * Nuevo diseño con chip, PDF417 en reverso y MRZ TD1.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 * Cara frontal:
 *   REPÚBLICA DEL PERÚ / RENIEC                 CUI 09300084-1
 *   DOCUMENTO NACIONAL DE IDENTIDAD
 *   Primer Apellido
 *   BERASTAIN
 *   Segundo Apellido
 *   MATEO
 *   Prenombres
 *   GUILLERMO ALFONSO
 *   [huella] [firma]            Sexo   Nacionalidad   Fecha de Nacimiento
 *                               M      PER            10 09 1967
 *                               Estado Civil          Fecha de Emisión
 *                               DIVORCIADO            06 01 2022
 *                               N° de Tarjeta         Fecha de Caducidad
 *                               0200631393            06 01 2030
 *
 * Cara posterior:
 *   [Constancias de sufragio]
 *   Ubigeo de Nacimiento:  140115
 *   Donación de Órganos:   Si
 *   Dirección:             JR. PABLO USANDIZAGA 283
 *   Departamento/Provincia/Distrito: LIMA/LIMA/SAN BORJA
 *   MRZ TD1 (3 líneas × 30 chars)
 *
 * Nota: el CUI tiene dígito verificador separado por guión (09300084-1).
 */
@Component
public class PerDNINewExtractor extends BaseExtractor {

    // CUI "09300084-1" → captura los 8 dígitos (número de documento)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "CUI\\s*(\\d{8})-\\d", Pattern.CASE_INSENSITIVE);

    // Fallback: "DNI 09300084" sin dígito verificador en MRZ o texto
    private static final Pattern DOC_NUMBER_FALLBACK = Pattern.compile(
            "DNI\\s+(\\d{8})(?:-\\d)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIMER_APELLIDO = Pattern.compile(
            "Primer\\s+Apellido\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEGUNDO_APELLIDO = Pattern.compile(
            "Segundo\\s+Apellido\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // "Prenombres" (nueva) o "Pre Nombres" (antigua)
    private static final Pattern NOMBRES = Pattern.compile(
            "Prenombres\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "Sexo\\s*\\n?([MF])(?=\\s|\\n)", Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "Nacionalidad\\s*\\n?([A-Z]{3})(?=\\s|\\n)", Pattern.CASE_INSENSITIVE);

    // Fechas en formato "DD MM YYYY" con espacio
    private static final Pattern FECHA_NAC = Pattern.compile(
            "Fecha\\s+de\\s+Nacimiento\\s*\\n?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ESTADO_CIVIL = Pattern.compile(
            "Estado\\s+Civil\\s*\\n([A-ZÁÉÍÓÚÑ]+?)(?=\\n|Fecha)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EMISION = Pattern.compile(
            "Fecha\\s+de\\s+Emisi[oó]n\\s*\\n?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_CADUCIDAD = Pattern.compile(
            "Fecha\\s+de\\s+Caducidad\\s*\\n?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // "N° de Tarjeta\n0200631393"
    private static final Pattern NUM_TARJETA = Pattern.compile(
            "N[°º]\\.?\\s+de\\s+Tarjeta\\s*\\n?(\\d{8,12})", Pattern.CASE_INSENSITIVE);

    // Reverso
    private static final Pattern UBIGEO = Pattern.compile(
            "Ubigeo\\s+de\\s+Nacimiento[:\\s]*(\\d{6})", Pattern.CASE_INSENSITIVE);

    private static final Pattern DONACION = Pattern.compile(
            "Donaci[oó]n\\s+de\\s+[OÓ]rganos[:\\s]+(S[Ii]|NO)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIRECCION = Pattern.compile(
            "Direcci[oó]n[:\\s]+([A-ZÁÉÍÓÚÑ0-9][^\\n]+?)(?=\\n)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPTO_PROV_DIST = Pattern.compile(
            "Departamento/Provincia/Distrito[:\\s]+([A-ZÁÉÍÓÚÑ/]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.PER_DNI_NEW;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // Número de documento desde CUI
        boolean docFound = extract(ocrText, DOC_NUMBER).map(v -> {
            fields.add(ExtractedField.of("documentNumber", v, 0.94));
            return true;
        }).orElse(false);

        if (!docFound) {
            fields.add(field("documentNumber", ocrText, DOC_NUMBER_FALLBACK, 0.88));
        }

        fields.add(field("primerApellido",       ocrText, PRIMER_APELLIDO,  0.88));
        fields.add(field("segundoApellido",      ocrText, SEGUNDO_APELLIDO, 0.88));
        fields.add(field("nombres",              ocrText, NOMBRES,          0.88));
        fields.add(field("sexo",                 ocrText, SEXO,             0.93));
        fields.add(field("nacionalidad",         ocrText, NACIONALIDAD,     0.88));
        fields.add(field("estadoCivil",          ocrText, ESTADO_CIVIL,     0.84));
        fields.add(field("fechaNacimiento",      ocrText, FECHA_NAC,        0.90));
        fields.add(field("fechaEmision",         ocrText, FECHA_EMISION,    0.88));
        fields.add(field("fechaCaducidad",       ocrText, FECHA_CADUCIDAD,  0.90));
        fields.add(field("numeroDeTarjeta",      ocrText, NUM_TARJETA,      0.85));
        fields.add(field("ubigeoNacimiento",     ocrText, UBIGEO,           0.85));
        fields.add(field("donacionOrganos",      ocrText, DONACION,         0.83));
        fields.add(field("direccion",            ocrText, DIRECCION,        0.80));
        fields.add(field("deptoProviDist",       ocrText, DEPTO_PROV_DIST,  0.82));

        // Apellidos compuestos
        String p1 = fields.stream().filter(f -> "primerApellido".equals(f.getFieldName()))
                .map(ExtractedField::getValue).filter(v -> v != null).findFirst().orElse(null);
        String p2 = fields.stream().filter(f -> "segundoApellido".equals(f.getFieldName()))
                .map(ExtractedField::getValue).filter(v -> v != null).findFirst().orElse(null);
        if (p1 != null) {
            fields.add(ExtractedField.of("apellidos", p2 != null ? p1 + " " + p2 : p1, 0.88));
        }

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLICA DEL PERU", "REPÚBLICA DEL PERÚ",
                "RENIEC", "DOCUMENTO NACIONAL DE IDENTIDAD",
                "Primer Apellido", "Prenombres", "CUI",
                "Donación de Órganos", "N° de Tarjeta");
        return Math.min(hits * 0.12, 1.0);
    }
}
