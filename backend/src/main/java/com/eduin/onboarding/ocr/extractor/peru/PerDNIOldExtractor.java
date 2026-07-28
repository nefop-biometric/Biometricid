package com.eduin.onboarding.ocr.extractor.peru;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para DNI Peruano (versión antigua — hasta 2013).
 * Emitido por RENIEC (Registro Nacional de Identificación y Estado Civil).
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 * Cara frontal:
 *   REPÚBLICA DEL PERÚ / RENIEC
 *   DOCUMENTO NACIONAL DE IDENTIDAD    DNI 48296902-5    CUI (esquina)
 *   Primer Apellido                    Fecha Inscripción
 *   TECCO                              18 07 2011
 *   Segundo Apellido                   Fecha Emisión
 *   FERRO                              06 08 2021
 *   Pre Nombres                        Fecha Caducidad
 *   MAYRA KATHERINE                    28 10 2027
 *   Nacimiento: Fecha y Ubigeo
 *   04 07 1992     130115
 *   Sexo    Estado Civil
 *   F       S
 *   MRZ TD1 (3 líneas × 30 chars):
 *   I<PER48296902<7<<<<<<<<<<<<<<
 *   9207046F2710283PER<<<<<<<<<<<7
 *   TECCO<<MAYRA<KATHERINE<<<<<<<<
 *
 * Nota: el DNI tiene dígito verificador separado por guión (48296902-5).
 *       Solo los 8 dígitos antes del guión son el número de documento.
 * Nota: fechas en formato "DD MM YYYY" con espacio.
 */
@Component
public class PerDNIOldExtractor extends BaseExtractor {

    // "DNI 48 296902+-5" (el OCR a veces inserta un espacio dentro del número
    // y confunde el guión del dígito verificador con "+-"). Se captura el bloque
    // completo de dígitos (con espacio interno opcional) y se limpia después.
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "DNI\\s+(\\d{1,2}\\s?\\d{6,8})[+-]*-?\\d?", Pattern.CASE_INSENSITIVE);

    // CUI en esquina: "CUI\n48296902-5" o inline
    private static final Pattern CUI = Pattern.compile(
            "CUI\\s*\\n?(\\d{8}-\\d)", Pattern.CASE_INSENSITIVE);

    // "Primer Apellido\nTECCO" — puede haber líneas intermedias de ruido OCR
    // (la fusión de las pasadas normal+invertida del motor OCR puede insertar
    // texto de otras zonas del documento entre el label y el valor real)
    private static final Pattern PRIMER_APELLIDO = Pattern.compile(
            "Primer\\s+Ap[ae]?[lt]?ido\\s*\\n(?:[^\\n]*\\n){0,2}?([A-ZÁÉÍÓÚÑ]{3,}(?:\\s[A-ZÁÉÍÓÚÑ]{3,})?)\\n",
            Pattern.CASE_INSENSITIVE);

    // "Segundo Apellido\nFERRO"
    private static final Pattern SEGUNDO_APELLIDO = Pattern.compile(
            "Segundo\\s+[A-Za-zÁÉÍÓÚáéíóú]*ido\\s*\\n(?:[^\\n]*\\n){0,2}?([A-ZÁÉÍÓÚÑ]{3,})\\n",
            Pattern.CASE_INSENSITIVE);

    // "Pre Nombres\nMAYRA KATHERINE"
    private static final Pattern NOMBRES = Pattern.compile(
            "Pre\\s+Nombres\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // "Nacimiento Fecha Y\n04 07 1992 130115" — la palabra "Ubigeo" a veces
    // se pierde en el OCR, así que se hace opcional
    private static final Pattern FECHA_NAC = Pattern.compile(
            "Nacimiento[:\\s]+Fecha\\s+[yY](?:\\s+Ubigeo)?\\s*\\n(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UBIGEO = Pattern.compile(
            "Nacimiento[:\\s]+Fecha\\s+[yY](?:\\s+Ubigeo)?\\s*\\n\\d{2}\\s+\\d{2}\\s+\\d{4}\\s+(\\d{6})",
            Pattern.CASE_INSENSITIVE);

    // "Sexo\nF" — tolera ruido entre el label y el valor
    private static final Pattern SEXO = Pattern.compile(
            "Sexo[^\\n]*\\n(?:[^\\n]*\\n){0,1}?.*?\\b([MF])\\b",
            Pattern.CASE_INSENSITIVE);

    // "Estado Civil\nS" (S=Soltero, C=Casado, D=Divorciado, V=Viudo)
    private static final Pattern ESTADO_CIVIL = Pattern.compile(
            "Estado\\s+Civil[^\\n]*\\n(?:[^\\n]*\\n){0,1}?.*?\\b([SCDV])\\b",
            Pattern.CASE_INSENSITIVE);

    // Fechas adicionales en columna derecha: "Fecha Inscripción\n[ruido]\n18 07 2011"
    // Se busca la fecha en cualquier punto dentro de una ventana corta de
    // caracteres tras el label (tolera ruido OCR intercalado, sin exigir que
    // la fecha empiece justo al inicio de una línea).
    private static final Pattern FECHA_INSCRIPCION = Pattern.compile(
            "Fecha\\s+Inscripci[oó]n[\\s\\S]{0,30}?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EMISION = Pattern.compile(
            "Fecha\\s+Emisi[oó]n[\\s\\S]{0,30}?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_CADUCIDAD = Pattern.compile(
            "Fecha\\s+Caducidad[\\s\\S]{0,30}?(\\d{2}\\s+\\d{2}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.PER_DNI_OLD;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // El número puede traer un espacio interno por ruido OCR ("48 296902") — se quita
        extract(ocrText, DOC_NUMBER).ifPresent(v ->
                fields.add(ExtractedField.of("documentNumber", v.replaceAll("\\s", ""), 0.90)));
        fields.add(field("cui",              ocrText, CUI,              0.90));
        fields.add(field("primerApellido",   ocrText, PRIMER_APELLIDO,  0.88));
        fields.add(field("segundoApellido",  ocrText, SEGUNDO_APELLIDO, 0.88));
        fields.add(field("nombres",          ocrText, NOMBRES,          0.88));
        fields.add(field("sexo",             ocrText, SEXO,             0.93));
        fields.add(field("estadoCivil",      ocrText, ESTADO_CIVIL,     0.83));
        fields.add(field("fechaNacimiento",  ocrText, FECHA_NAC,        0.90));
        fields.add(field("ubigeo",           ocrText, UBIGEO,           0.85));
        fields.add(field("fechaInscripcion", ocrText, FECHA_INSCRIPCION,0.85));
        fields.add(field("fechaEmision",     ocrText, FECHA_EMISION,    0.88));
        fields.add(field("fechaCaducidad",   ocrText, FECHA_CADUCIDAD,  0.90));

        // Apellidos compuestos
        String p1 = fields.stream().filter(f -> "primerApellido".equals(f.getFieldName()))
                .map(ExtractedField::getValue).filter(v -> v != null).findFirst().orElse(null);
        String p2 = fields.stream().filter(f -> "segundoApellido".equals(f.getFieldName()))
                .map(ExtractedField::getValue).filter(v -> v != null).findFirst().orElse(null);
        if (p1 != null) {
            String apellidos = p2 != null ? p1 + " " + p2 : p1;
            fields.add(ExtractedField.of("apellidos", apellidos, 0.88));
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
                "Primer Apellido", "Segundo Apellido", "Pre Nombres",
                "Nacimiento: Fecha y Ubigeo");
        return Math.min(hits * 0.13, 1.0);
    }
}
