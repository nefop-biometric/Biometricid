package com.eduin.onboarding.ocr.extractor.spain;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extractor para DNI Español (versión antigua — laminado, sin chip, anterior al DNIe).
 *
 * LAYOUT REAL (confirmado con OCR real de documento físico):
 *
 *   ESPAÑA              DOCUMENTO NACIONAL DE IDENTIDAD
 *   APELLIDOS
 *   CARRERAS
 *   BARBA
 *   NOMBRE
 *   FRANCISCO JAVIER
 *   SEXO  NACIONALIDAD
 *   M     ESP
 *   FECHA DE NACIMIENTO
 *   11 02 1956
 *   NUM SOPORTE
 *   BJG158697          13 02 2029     ← num soporte + fecha validez en la misma línea
 *   05346191W                         ← DNI (8 dígitos + letra NIF)
 *
 * Cara posterior:
 *   C. NTRA SRA ALMUDENA 21
 *   MORALZARZAL
 *   MADRID
 *   LUGAR DE NACIMIENTO
 *   CARACAS
 *   VENEZUELA
 *   FRANCISCO / ISABEL    ← hijo de (padre / madre)
 *   MRZ TD1 (3 líneas × 30 chars)
 *
 * Nota: mismo layout que ESP_DNI_NEW pero monolingüe (sin "/COGNOMS", "/NOM")
 *       y sin campo de chip "EQUIPO". El OCR real introduce ruido entre el
 *       label y el salto de línea (p.ej. "APELLIDOS ne\n"), por lo que los
 *       patrones toleran texto basura tras el label con [^\n]*.
 */
@Component
public class EspDNIOldExtractor extends BaseExtractor {

    // DNI: 8 dígitos + letra NIF (excluye I, O, U, Ñ) — buscado en cualquier parte del texto.
    // Sin \b de cierre: el OCR a veces añade una letra extra pegada justo después
    // (p.ej. "05346191Ww") que rompería el límite de palabra.
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "\\b(\\d{8}[A-HJ-NP-TV-Z])", Pattern.CASE_INSENSITIVE);

    // "APELLIDOS [ruido]\nCARRERAS\n\nBARBA\nNOMBRE" — captura 1-2 líneas.
    // No se exige "NOMBRE" como ancla de cierre: esa etiqueta a veces no sobrevive
    // el OCR; en su lugar se acota por cantidad de líneas (máx 3 cortas).
    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS[^\\n]*\\n" +
            "((?:[A-ZÁÉÍÓÚÑ]{2,}(?:\\s[A-ZÁÉÍÓÚÑ]{2,})?\\n\\s*){1,2})",
            Pattern.CASE_INSENSITIVE);

    // "NOMBRE [ruido]\nFRANCISCO JAVIER" — si el label "NOMBRE" no sobrevive el OCR,
    // se usa como respaldo la primera línea de 2 palabras tras los apellidos
    // (resuelto en el código de extracción, no aquí).
    private static final Pattern NOMBRE = Pattern.compile(
            "NOMBRE[^\\n]*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)(?=\\n|\\.)",
            Pattern.CASE_INSENSITIVE);

    // Respaldo: si el label "NOMBRE" no sobrevivió el OCR, el nombre es la línea
    // de 2 palabras que aparece justo antes de "SEXO" (label que sí sobrevive).
    private static final Pattern NOMBRE_FALLBACK = Pattern.compile(
            "\\n([A-ZÁÉÍÓÚÑ]{2,}\\s[A-ZÁÉÍÓÚÑ]{2,})\\n\\s*SEXO",
            Pattern.CASE_INSENSITIVE);

    // "SEXO NACIONALIDAD [ruido]\nM ESP [ruido]" — sexo y nacionalidad en la misma línea
    private static final Pattern SEXO = Pattern.compile(
            "SEXO[^\\n]*NACIONALIDAD[^\\n]*\\n\\s*([MF])\\s+[A-Z]{3}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NACIONALIDAD = Pattern.compile(
            "SEXO[^\\n]*NACIONALIDAD[^\\n]*\\n\\s*[MF]\\s+([A-Z]{3})",
            Pattern.CASE_INSENSITIVE);

    // "FECHA DE NACIMIENTO [ruido]\n11 02 1956 [ruido]" — formato DD MM YYYY con espacios
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACIMIENTO[^\\n]*\\n\\s*(\\d{2}\\s\\d{2}\\s\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // "NUM SOPORT[E] [ruido]\n[ruido] BJG158697 13 02 2029" — num soporte + fecha validez
    private static final Pattern NUM_SOPORTE = Pattern.compile(
            "NUM\\s+SOPORT[E]?[^\\n]*\\n[^\\n]*?([A-Z]{2,3}\\d{6,9})\\s+(\\d{2}\\s\\d{2}\\s\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // Reverso: domicilio (primeras líneas tras el header, antes de LUGAR DE NACIMIENTO)
    private static final Pattern DOMICILIO_CALLE = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑ0-9][^\\n]{4,40})\\n(?=[A-ZÁÉÍÓÚÑ])",
            Pattern.MULTILINE);

    private static final Pattern LUGAR_NAC_CIUDAD = Pattern.compile(
            "LUGAR\\s+(?:CE|DE)\\s+NACIMIENTO\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_NAC_PAIS = Pattern.compile(
            "LUGAR\\s+(?:CE|DE)\\s+NACIMIENTO\\s*\\n[A-ZÁÉÍÓÚÑ][^\\n]+\\n[+\\s]*([A-ZÁÉÍÓÚÑ]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HIJO_DE = Pattern.compile(
            "([A-ZÁÉÍÓÚÑ]+)\\s*/\\s*([A-ZÁÉÍÓÚÑ]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.ESP_DNI_OLD;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        fields.add(field("documentNumber", ocrText, DOC_NUMBER, 0.90));

        extract(ocrText, APELLIDOS).ifPresent(v -> {
            String norm = v.replaceAll("\\n", " ").replaceAll("\\s+", " ").trim();
            fields.add(ExtractedField.of("apellidos", norm, 0.85));
        });

        boolean nombreFound = extract(ocrText, NOMBRE).map(v -> {
            fields.add(ExtractedField.of("nombre", v.trim(), 0.85));
            return true;
        }).orElse(false);
        if (!nombreFound) {
            extract(ocrText, NOMBRE_FALLBACK).ifPresent(v ->
                    fields.add(ExtractedField.of("nombre", v.trim(), 0.75)));
        }
        fields.add(field("sexo",          ocrText, SEXO,          0.90));
        fields.add(field("nacionalidad",  ocrText, NACIONALIDAD,  0.88));
        fields.add(field("fechaNacimiento", ocrText, FECHA_NAC,   0.88));

        var m = NUM_SOPORTE.matcher(ocrText);
        if (m.find()) {
            fields.add(ExtractedField.of("numSoporte", m.group(1), 0.88));
            fields.add(ExtractedField.of("fechaVigencia", normalizeDateSpaces(m.group(2)), 0.88));
        }

        fields.add(field("lugarNacimiento", ocrText, LUGAR_NAC_CIUDAD, 0.78));
        fields.add(field("paisNacimiento",  ocrText, LUGAR_NAC_PAIS,   0.75));

        var hm = HIJO_DE.matcher(ocrText);
        if (hm.find()) {
            fields.add(ExtractedField.of("hijoDe", hm.group(1) + " / " + hm.group(2), 0.75));
        }

        fields.stream()
              .filter(f -> f.getFieldName().equals("fechaNacimiento") && f.getValue() != null)
              .forEach(f -> f.setValue(normalizeDateSpaces(f.getValue())));

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "ESPAÑA", "ESPANA",
                "DOCUMENTO NACIONAL DE IDENTIDAD",
                "NUM SOPORT", "SEXO", "NACIONALIDAD",
                "FECHA DE NACIMIENTO");
        boolean isNew = ocrText.toUpperCase().contains("COGNOMS")
                || ocrText.toUpperCase().contains("EQUIPO")
                || ocrText.toUpperCase().contains("HIJO/A DE");
        double score = hits * 0.14;
        if (isNew) score -= 0.3;
        return Math.min(Math.max(score, 0.0), 1.0);
    }

    // "11 02 1956" → "1956-02-11"
    private String normalizeDateSpaces(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.matches("\\d{2}\\s\\d{2}\\s\\d{4}")) {
            String[] p = raw.split("\\s");
            return p[2] + "-" + p[1] + "-" + p[0];
        }
        return normalizeDate(raw);
    }
}
