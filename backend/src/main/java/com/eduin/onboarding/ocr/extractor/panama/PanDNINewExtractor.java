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
 * Extractor para Cédula de Identidad Panameña (versión nueva — desde 2009).
 * Emitida por el Tribunal Electoral. Diseño tarjeta PVC con MRZ TD1 en reverso.
 *
 * LAYOUT REAL (confirmado con documentos reales):
 *
 *   REPÚBLICA DE PANAMÁ
 *   DOCUMENTO DE IDENTIDAD         [chip NFC]
 *
 *   ADRIANA CAROLINA               ← NOMBRES (primera línea, sin label)
 *   MIRANDA CISNEROS               ← APELLIDOS (segunda línea, sin label)
 *
 *   NOMBRE USUAL:
 *   FECHA DE NACIMIENTO: 10-sep-1998   ← mes en minúsculas (sep, ago, dic...)
 *   LUGAR DE NACIMIENTO: PANAMÁ
 *   SEXO: F      TIPO DE SANGRE: ♥   ← puede ser ícono o vacío
 *   EXPEDIDA: 02-ago-2023   EXPIRA: 31-ago-2027
 *   8-123-4567                     ← número en esquina inferior
 *
 * Reverso:
 *   [QR code × 2]
 *   MRZ TD1 (3 líneas × 30 chars)
 *
 * Diferencias vs la versión antigua:
 * - Dice "DOCUMENTO DE IDENTIDAD" (no "CÉDULA DE IDENTIDAD PERSONAL")
 * - Tiene MRZ TD1 en el reverso
 * - Meses en minúsculas (sep, ago, dic) → toUpperCase los normaliza
 */
@Component
public class PanDNINewExtractor extends BaseExtractor {

    // Número de cédula en esquina inferior: "8-123-4567"
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "\\b([0-9NE][A-Z0-9]*(?:-[A-Z0-9]+){1,3})\\b(?=\\s*\\n|\\s*$)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Nombres y apellidos en las primeras 2 líneas sin labels después del header
    private static final Pattern NOMBRES_APELLIDOS = Pattern.compile(
            "DOCUMENTO\\s+DE\\s+IDENTIDAD[\\s\\S]*?\\n([A-ZÁÉÍÓÚÑ][a-záéíóúñA-ZÁÉÍÓÚÑ\\s]+?)\\n([A-ZÁÉÍÓÚÑ][a-záéíóúñA-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // Fechas con meses en minúsculas o mayúsculas: "10-sep-1998", "02-ago-2023"
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO[:\\s]+(\\d{2}-[A-Za-z]{3,4}-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_NAC = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO[:\\s]+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s,]+?)(?=\\n)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEXO = Pattern.compile(
            "SEXO[:\\s]+([MF])", Pattern.CASE_INSENSITIVE);

    private static final Pattern SANGRE = Pattern.compile(
            "TIPO\\s+DE\\s+SANGRE[:\\s]+([ABO]{1,2}[+-])", Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_EXP = Pattern.compile(
            "EXPEDIDA[:\\s]+(\\d{2}-[A-Za-z]{3,4}-\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_VEN = Pattern.compile(
            "EXPIRA[:\\s]+(\\d{2}-[A-Za-z]{3,4}-\\d{4})", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.PAN_DNI_NEW;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("documentNumber",  ocrText, DOC_NUMBER,  0.92));

        // Nombres y apellidos (2 líneas sin labels)
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

        // normalizeDate convierte a mayúsculas internamente: "sep" → "SEP" → "09"
        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLICA DE PANAMA", "REPÚBLICA DE PANAMÁ",
                "TRIBUNAL ELECTORAL", "DOCUMENTO DE IDENTIDAD",
                "FECHA DE NACIMIENTO", "EXPEDIDA", "EXPIRA");
        boolean isNew = ocrText.toUpperCase().contains("DOCUMENTO DE IDENTIDAD");
        double score = hits * 0.15;
        if (isNew) score += 0.1;
        return Math.min(score, 1.0);
    }
}
