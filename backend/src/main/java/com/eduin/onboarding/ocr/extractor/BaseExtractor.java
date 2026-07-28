package com.eduin.onboarding.ocr.extractor;

import com.eduin.onboarding.ocr.model.ExtractedField;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidades comunes para todos los extractores.
 */
public abstract class BaseExtractor implements DocumentExtractor {

    protected Optional<String> extract(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }

    protected ExtractedField field(String name, String text, Pattern pattern, double baseConfidence) {
        Optional<String> value = extract(text, pattern);
        return value.map(v -> ExtractedField.of(name, normalize(v), baseConfidence))
                .orElse(ExtractedField.of(name, null, 0.0));
    }

    protected String normalize(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s+", " ").trim();
    }

    protected int countKeywords(String text, String... keywords) {
        int count = 0;
        String upper = text.toUpperCase();
        for (String kw : keywords) {
            if (upper.contains(kw.toUpperCase())) count++;
        }
        return count;
    }

    // Meses en español abreviados (3 o 4 letras) usados en documentos latinoamericanos
    private static final Map<String, String> MONTH_ES = Map.ofEntries(
            Map.entry("ENE",  "01"), Map.entry("ENER", "01"),
            Map.entry("FEB",  "02"),
            Map.entry("MAR",  "03"),
            Map.entry("ABR",  "04"),
            Map.entry("MAY",  "05"),
            Map.entry("JUN",  "06"),
            Map.entry("JUL",  "07"),
            Map.entry("AGO",  "08"),
            Map.entry("SEP",  "09"), Map.entry("SEPT", "09"),
            Map.entry("OCT",  "10"),
            Map.entry("NOV",  "11"),
            Map.entry("DIC",  "12")
    );

    /**
     * Normaliza fechas al formato ISO YYYY-MM-DD desde múltiples formatos de entrada.
     *
     * Formatos soportados:
     *   YYMMDD          → MRZ         (140817 → 2014-08-17)
     *   dd/mm/yyyy      → CE antigua  (06/08/1994 → 1994-08-06)
     *   dd-mm-yyyy      → genérico    (06-08-1994 → 1994-08-06)
     *   dd-MMM-yyyy     → CC/TI old   (24-NOV-1979 → 1979-11-24)
     *   DD MMM YYYY     → CC/ECU new  (13 JUL 1986 → 1986-07-13)
     *   DD MM YYYY      → DNIe ESP    (17 08 2014 → 2014-08-17)
     *   YYYY/MM/DD      → CE nueva    (1994/08/06 → 1994-08-06)
     *   YYYY-MM-DD      → ECU old     (ya es ISO, se devuelve tal cual)
     */
    protected String normalizeDate(String raw) {
        if (raw == null) return null;
        raw = raw.trim().toUpperCase();

        // YYMMDD (MRZ)
        if (raw.matches("\\d{6}")) {
            int yy = Integer.parseInt(raw.substring(0, 2));
            int year = yy > 30 ? 1900 + yy : 2000 + yy;
            return year + "-" + raw.substring(2, 4) + "-" + raw.substring(4, 6);
        }

        // YYYY-MM-DD (ISO — ECU old, pasar tal cual)
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return raw;
        }

        // YYYY/MM/DD (CE colombiana nueva: 1994/08/06)
        if (raw.matches("\\d{4}/\\d{2}/\\d{2}")) {
            String[] p = raw.split("/");
            return p[0] + "-" + p[1] + "-" + p[2];
        }

        // dd/mm/yyyy o dd-mm-yyyy (numérico)
        if (raw.matches("\\d{2}[/\\-]\\d{2}[/\\-]\\d{4}")) {
            String[] parts = raw.split("[/\\-]");
            return parts[2] + "-" + parts[1] + "-" + parts[0];
        }

        // dd-MMM-yyyy  (ej: 24-NOV-1979, 01-FEB-1999, 28-JUN-1989)
        if (raw.matches("\\d{2}-[A-Z]{3,4}-\\d{4}")) {
            String[] parts = raw.split("-");
            String month = MONTH_ES.getOrDefault(parts[1], parts[1]);
            return parts[2] + "-" + month + "-" + parts[0];
        }

        // DD MMM YYYY con espacio (ej: 13 JUL 1986, 10 NOV 2004, 05 SEPT 2023)
        if (raw.matches("\\d{2}\\s[A-Z]{3,4}\\s\\d{4}")) {
            String[] parts = raw.split("\\s+");
            String month = MONTH_ES.getOrDefault(parts[1], parts[1]);
            return parts[2] + "-" + month + "-" + parts[0];
        }

        // DD MM YYYY con espacio (ej: 17 08 2014 — DNIe España)
        if (raw.matches("\\d{2}\\s\\d{2}\\s\\d{4}")) {
            String[] p = raw.split("\\s");
            return p[2] + "-" + p[1] + "-" + p[0];
        }

        return raw;
    }
}
