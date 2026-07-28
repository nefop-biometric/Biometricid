package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extractor para Pasaporte Colombiano (con o sin chip NFC).
 * MRZ TD3 — 2 líneas × 44 caracteres.
 *
 * LAYOUT REAL — label ANTES del valor (estándar ICAO):
 *
 *   REPUBLIC OF COLOMBIA / REPÚBLICA DE COLOMBIA
 *   PASSPORT / PASAPORTE
 *   Surname / Apellidos:   MARTINEZ LOPEZ
 *   Given names / Nombres: JUAN CARLOS
 *   Nationality:           COLOMBIAN / COLOMBIANA
 *   Date of birth:         15 MAY 1990
 *   Sex:                   M
 *   Place of birth:        BOGOTA D.C.
 *   Date of issue:         10 JAN 2020
 *   Date of expiry:        09 JAN 2030
 *   Passport No:           AB123456
 *
 *   MRZ (TD3 — 2 líneas × 44):
 *   P<COLMARTINEZ<<JUAN<CARLOS<<<<<<<<<<<<<<<<<<
 *   AB1234567COL9005158M3001098<<<<<<<<<<<<<<2
 *
 * NOTA: Los campos bilingües varían según la versión del pasaporte.
 *       El MRZ tiene la mayor fiabilidad — siempre se prioriza.
 */
@Component
public class ColPAExtractor extends BaseExtractor {

    // ── Número de pasaporte: BA713812 ──────────────────────────────────────
    // OCR: "COL BA713812" o "P COL BA713812" — buscamos cerca del contexto "Passport/COL"
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "(?:Passport\\s*N|\\bCOL)\\s+([A-Z]{2}\\d{6})", Pattern.CASE_INSENSITIVE);

    // ── Apellidos ────────────────────────────────────────────────────────
    // OCR: "PASSPORT ORDOÑEZ PARRA" o "PASSPORT ORDONEZ PARRA 7"
    // Captura palabras mayúsculas (incluyendo Ñ/acentos) después de PASSPORT, hasta fin de línea.
    // Luego limpia trailing dígitos/junk en el código Java.
    private static final Pattern APELLIDOS = Pattern.compile(
            "^PASSPO\\w*\\s+(.+?)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    // Fallback: "PASS ORDONEZ PARRA" (invertido a veces dice PASS en vez de PASSPORT)
    private static final Pattern APELLIDOS2 = Pattern.compile(
            "^PASS\\s+([A-ZÁÉÍÓÚÑ].+?)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Nombres ──────────────────────────────────────────────────────────
    // OCR: "Given names BA743812\nEDUIN FABIAN -" o "Given names\nEDUIN FABIAN"
    // Captura la línea siguiente al label, limpia trailing junk
    private static final Pattern NOMBRES = Pattern.compile(
            "(?:Given\\s+names?|NOMBRES)[^\\n]*\\n([A-ZÁÉÍÓÚÑ].+?)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Nacionalidad ─────────────────────────────────────────────────────
    private static final Pattern NACIONALIDAD = Pattern.compile(
            "(?:NATIONALITY|NACIONALIDAD)[^\\n]*\\n+([A-ZÁÉÍÓÚÑ]{4,})",
            Pattern.CASE_INSENSITIVE);

    // ── Fechas: "21 JUL/JUL 1979" — captura 3 grupos (dd, MES, yyyy) ───
    private static final Pattern FECHA_NAC = Pattern.compile(
            "(?:DATE\\s+OF\\s+BIRTH|FECHA\\s+DE\\s+NACIMIENTO)[^\\n]*\\n(\\d{2})\\s+([A-Z]{3})(?:/[A-Z]{3})?\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // OCR: "Sexo / Sex Lugar de nacimiento...\nM SAN JOSE..."
    private static final Pattern SEXO = Pattern.compile(
            "SEXO?\\s*/\\s*SEX[^\\n]*\\n\\s*([MFX])\\b",
            Pattern.CASE_INSENSITIVE);

    // OCR: "Place.of\nM SAN JOSE DEL PALMAR COL"
    private static final Pattern LUGAR_NAC = Pattern.compile(
            "(?:PLACE.OF|LUGAR\\s+DE\\s+NACIMIENTO)[^\\n]*\\n+(?:[MFX]\\s+)?([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s.]{3,}?)\\s+COL\\b",
            Pattern.CASE_INSENSITIVE);

    // OCR produce variantes: "Date of issue", "Date ofíssue", "Date of íssue"
    private static final Pattern FECHA_EMISION = Pattern.compile(
            "(?:DATE\\s+OF\\s*[ÍI]?SSU|FECHA\\s+DE\\s+EXPEDICI)[^\\n]*\\n(\\d{2})\\s+([A-Z]{3})(?:/[A-Z]{3})?\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FECHA_VENCE = Pattern.compile(
            "(?:DATE\\s+OF\\s+EXPIRY|FECHA\\s+DE\\s+VENCIMIENTO)[^\\n]*\\n(\\d{2})\\s+([A-Z]{3})(?:/[A-Z]{3})?\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // ── Número personal (CC): CC80009938 ─────────────────────────────────
    private static final Pattern NUM_PERSONAL = Pattern.compile(
            "CC(\\d{6,10})\\b");

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.COL_PA;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("documentNumber",  ocrText, DOC_NUMBER,    0.93));

        // Apellidos: captura línea con PASSPORT, luego limpia trailing junk
        Optional<String> apOpt = extract(ocrText, APELLIDOS);
        if (apOpt.isEmpty()) apOpt = extract(ocrText, APELLIDOS2);
        apOpt.ifPresentOrElse(
                v -> fields.add(ExtractedField.of("apellidos", cleanName(v), 0.88)),
                () -> fields.add(ExtractedField.of("apellidos", null, 0.0)));

        // Nombres: captura línea después de "Given names", limpia trailing junk
        Optional<String> nomOpt = extract(ocrText, NOMBRES);
        nomOpt.ifPresentOrElse(
                v -> fields.add(ExtractedField.of("nombres", cleanName(v), 0.88)),
                () -> fields.add(ExtractedField.of("nombres", null, 0.0)));
        fields.add(field("nacionalidad",    ocrText, NACIONALIDAD,  0.85));
        fields.add(field("sexo",            ocrText, SEXO,          0.93));
        fields.add(field("lugarNacimiento", ocrText, LUGAR_NAC,     0.80));

        // Fechas con formato "dd MES/MES yyyy" → captura 3 grupos
        extractDate(fields, "fechaNacimiento",  ocrText, FECHA_NAC,     0.90);
        extractDate(fields, "fechaEmision",     ocrText, FECHA_EMISION, 0.88);
        extractDate(fields, "fechaVencimiento", ocrText, FECHA_VENCE,   0.90);

        // Número personal (cédula asociada)
        extract(ocrText, NUM_PERSONAL).ifPresent(v ->
                fields.add(ExtractedField.of("numeroPersonal", v, 0.85)));

        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    private void extractDate(List<ExtractedField> fields, String name,
                             String text, Pattern pattern, double confidence) {
        java.util.regex.Matcher m = pattern.matcher(text);
        if (m.find()) {
            String date = m.group(1) + " " + m.group(2) + " " + m.group(3);
            fields.add(ExtractedField.of(name, date, confidence));
        } else {
            fields.add(ExtractedField.of(name, null, 0.0));
        }
    }

    /**
     * Limpia un nombre/apellido capturado del OCR: quita dígitos, guiones trailing,
     * caracteres sueltos, y palabras que son labels (Nombres, Given, etc.)
     */
    private String cleanName(String raw) {
        if (raw == null) return null;
        // Quitar trailing dígitos, guiones, puntos, caracteres sueltos de 1 char
        String clean = raw.replaceAll("[\\d\\-.,;:!?]+$", "").trim();
        // Quitar palabras que son labels del documento
        clean = clean.replaceAll("(?i)\\b(Nombres|Given|names|Surname|Apellidos|Nationality|Nacionalidad)\\b.*", "").trim();
        // Quitar caracteres no-alfabéticos sueltos al final
        clean = clean.replaceAll("[^A-ZÁÉÍÓÚÑa-záéíóúñ]+$", "").trim();
        return clean.isEmpty() ? null : clean;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLIC OF COLOMBIA", "REPUBLICA DE COLOMBIA",
                "PASAPORTE", "PASSPORT",
                "DATE OF BIRTH", "DATE OF EXPIRY",
                "NATIONALITY", "PLACE OF BIRTH");
        return Math.min(hits * 0.13, 1.0);
    }
}
