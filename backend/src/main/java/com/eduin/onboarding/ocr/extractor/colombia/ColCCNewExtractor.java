package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para Cédula de Ciudadanía Colombiana (versión nueva / blanca, desde ~2000).
 *
 * LAYOUT REAL — label ANTES del valor (opuesto a la cédula antigua):
 *
 * Cara frontal:
 *   CÉDULA DE CIUDADANÍA        REPÚBLICA DE COLOMBIA
 *   [foto]
 *              Apellidos                    NUIP 1.049.603.644
 *              FIGUEROA VARGAS
 *              Nombres
 *              JOSE LEONARDO
 *              Nacionalidad    Estatura    Sexo
 *              COL             1.78        M
 *              Fecha de nacimiento    G.S.
 *              13 JUL 1986            O+
 *              Lugar de nacimiento
 *              TUNJA (BOYACA)
 *              Fecha y lugar de expedición
 *              22 JUL 2004, TUNJA
 *              Fecha de expiración
 *              16 ABR 2035
 *
 * Cara posterior:
 *   MRZ TD1 (3 líneas × 30 chars)
 *   ICCOL060379261207001<<<<<<<<<<
 *   8607139M3504163COL1049603644<7
 *   FIGUEROA<VARGAS<<JOSE<LEONARDO
 *
 * Nota sobre fechas: formato "DD MMM YYYY" con espacio y mes en español (JUL, AGO, ENE…)
 * Nota sobre NUIP: incluye puntos separadores (1.049.603.644 → 1049603644)
 */
@Component
public class ColCCNewExtractor extends BaseExtractor {

    // ── NUIP (Número Único de Identificación Personal) ────────────────────────
    // "NUIP 1.049.603.644"
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "NUIP\\s+([\\d.]{6,15})", Pattern.CASE_INSENSITIVE);

    // ── Apellidos — línea siguiente al NUIP o al label ────────────────────────
    // OCR real: "tpolidos NUIP 80.009.938\n- ORDOÑEZ PARRA . a."
    // El label "Apellidos" sale mangled (tpolidos, Apelidos) y comparte línea con NUIP.
    // El valor tiene junk al inicio ("- ") y al final (" . a.") — se limpia en Java.
    private static final Pattern APELLIDOS = Pattern.compile(
            "(?:NUIP[^\\n]*|\\w*l+idos[^\\n]*)\\n([^\\n]{3,60})",
            Pattern.CASE_INSENSITIVE);

    // ── Nombres — label ANTES del valor ──────────────────────────────────────
    private static final Pattern NOMBRES = Pattern.compile(
            "Nombres[^\\n]*\\n([^\\n]{3,60})",
            Pattern.CASE_INSENSITIVE);

    // ── Nacionalidad ──────────────────────────────────────────────────────────
    // OCR real: "Nacionalidad Estatura Sexo\nCOL 1.76 M" — labels juntos, valores juntos
    private static final Pattern NACIONALIDAD = Pattern.compile(
            "Nacionalidad[^\\n]*\\n\\W*([A-Z]{3})\\b",
            Pattern.CASE_INSENSITIVE);

    // ── Estatura ──────────────────────────────────────────────────────────────
    // "Estatura Sexo\nCOL 1.76 M" → salta el COL opcional
    private static final Pattern ESTATURA = Pattern.compile(
            "Estatura[^\\n]*\\n(?:[A-Z]{3}\\s+)?(\\d[.,]\\d{2})",
            Pattern.CASE_INSENSITIVE);

    // ── Sexo ──────────────────────────────────────────────────────────────────
    // "Sexo\nCOL 1.76 M" → salta COL y estatura opcionales
    private static final Pattern SEXO = Pattern.compile(
            "Sexo[^\\n]*\\n(?:[A-Z]{3}\\s+)?(?:\\d[.,]\\d{2}\\s+)?([MF])\\b",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha de nacimiento — formato "DD MMM YYYY" ───────────────────────────
    // OCR real: "Fecha de nacimiento\n\nGS.\n21 JUL 1979 A+" — hasta 2 líneas de junk
    private static final Pattern FECHA_NAC = Pattern.compile(
            "Fecha\\s+de\\s+nacimiento[\\s\\S]{0,20}?(\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // ── Grupo sanguíneo ───────────────────────────────────────────────────────
    // Label directo O al final de la línea de fecha: "21 JUL 1979 A+"
    private static final Pattern SANGRE = Pattern.compile(
            "G\\.?S\\.?\\s*\\n?([ABO]{1,2}[+-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SANGRE2 = Pattern.compile(
            "\\d{4}\\s+([ABO][ABO]?[+-])");

    // ── Lugar de nacimiento ───────────────────────────────────────────────────
    private static final Pattern LUGAR_NAC = Pattern.compile(
            "Lugar\\s+de\\s+nacimiento\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s()]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha y lugar de expedición ───────────────────────────────────────────
    // "Fecha y lugar de expedición\n22 JUL 2004, TUNJA"
    private static final Pattern FECHA_EXP = Pattern.compile(
            "Fecha\\s+y\\s+lugar\\s+de\\s+expedici[oó]n\\s*\\n(\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_EXP = Pattern.compile(
            "Fecha\\s+y\\s+lugar\\s+de\\s+expedici[oó]n\\s*\\n\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4},?\\s+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s.]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha de expiración ───────────────────────────────────────────────────
    // OCR real: "Firma Fecha de expiración. 5\nAA 23 SEPT 2032" — junk en ambas líneas
    private static final Pattern FECHA_EXP_DOC = Pattern.compile(
            "Fecha\\s+de\\s+expiraci[oó]n[^\\n]*\\n(?:[A-Z]{1,3}\\s+)?(\\d{2}\\s+[A-Z]{3,4}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // ── Código inferior (solo en algunas versiones) ───────────────────────────
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
        return DocumentType.COL_CC_NEW;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // NUIP: quitar puntos (1.049.603.644 → 1049603644)
        extract(ocrText, DOC_NUMBER).ifPresent(v ->
                fields.add(ExtractedField.of("documentNumber",
                        v.replaceAll("\\.", ""), 0.92)));

        // Apellidos/Nombres: capturan la línea completa; se extrae solo la
        // secuencia en mayúsculas (el OCR agrega junk como "- " o " . a.")
        fields.add(nameField("apellidos", ocrText, APELLIDOS, 0.89));
        fields.add(nameField("nombres",   ocrText, NOMBRES,   0.89));
        fields.add(field("nacionalidad",     ocrText, NACIONALIDAD, 0.88));
        fields.add(field("estatura",         ocrText, ESTATURA,     0.85));
        fields.add(field("sexo",             ocrText, SEXO,         0.92));

        // Grupo sanguíneo: label G.S. directo, o al final de la línea de fecha ("21 JUL 1979 A+")
        java.util.Optional<String> gsOpt = extract(ocrText, SANGRE);
        if (gsOpt.isEmpty()) gsOpt = extract(ocrText, SANGRE2);
        fields.add(gsOpt
                .map(v -> ExtractedField.of("grupoSanguineo", v, 0.88))
                .orElseGet(() -> ExtractedField.of("grupoSanguineo", null, 0.0)));
        fields.add(field("lugarNacimiento",  ocrText, LUGAR_NAC,    0.83));
        fields.add(field("fechaNacimiento",  ocrText, FECHA_NAC,    0.90));
        fields.add(field("fechaExpedicion",  ocrText, FECHA_EXP,    0.88));
        fields.add(field("lugarExpedicion",  ocrText, LUGAR_EXP,    0.82));
        fields.add(field("fechaExpiracion",  ocrText, FECHA_EXP_DOC,0.90));

        // Código inferior (refuerzo si existe)
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

        // Normalizar fechas
        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null
                      && !f.getFieldName().startsWith("alerta"))
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    /**
     * Extrae un nombre/apellido: captura la línea y conserva solo la secuencia
     * de palabras en MAYÚSCULAS (mín. 3 chars). "- ORDOÑEZ PARRA . a." → "ORDOÑEZ PARRA"
     */
    private ExtractedField nameField(String name, String text, Pattern pattern, double confidence) {
        java.util.Optional<String> raw = extract(text, pattern);
        if (raw.isPresent()) {
            java.util.regex.Matcher m = Pattern
                    .compile("([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ ]{2,})")
                    .matcher(raw.get());
            if (m.find()) {
                String clean = m.group(1).trim();
                if (clean.length() >= 3) return ExtractedField.of(name, clean, confidence);
            }
        }
        return ExtractedField.of(name, null, 0.0);
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "CEDULA DE CIUDADANIA", "CÉDULA DE CIUDADANÍA",
                "REPUBLICA DE COLOMBIA", "REPÚBLICA DE COLOMBIA",
                "NUIP", "Fecha de nacimiento", "Fecha de expiración",
                "Lugar de nacimiento", "Fecha y lugar de expedición");
        return Math.min(hits * 0.13, 1.0);
    }
}
