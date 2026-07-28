package com.eduin.onboarding.ocr.extractor.spain;

import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor para DNI Español (versión nueva — DNIe 3.0, desde 2015).
 * Formato tarjeta TD1, chip NFC, MRZ 3 líneas × 30 chars.
 * Campos bilingües español / catalán.
 *
 * ── LAYOUT REAL CARA FRONTAL ─────────────────────────────────────────────────
 *
 *  REINO DE ESPAÑA
 *  DOCUMENTO NACIONAL DE IDENTIDAD
 *  604068878T        ← número impreso en borde izquierdo (repetido)
 *  DNI 60406878T     ← número principal
 *
 *  APELLIDOS / COGNOMS    ← label
 *  SUESCA                 ← apellido 1 (línea siguiente)
 *  VALLEJO                ← apellido 2 (siguiente línea)
 *
 *  NOMBRE / NOM           ← label
 *  DAVID SANTIAGO         ← nombre (línea siguiente)
 *
 *  SEXO / SEXE  NACIONALIDAD / NACIONALITAT  NACIMIENTO / NAIXEMENT   ← labels en fila
 *  M            ESP                           17 08 2014               ← valores en fila
 *
 *  EMISIÓN / EMISSIÓ  VALIDEZ / VALIDESA   ← labels
 *  16 05 2024         16 05 2029           ← valores
 *
 *  NUM SOPORTE
 *  CIA136800
 *
 * ── LAYOUT REAL CARA POSTERIOR ───────────────────────────────────────────────
 *
 *  DOMICILIO / DOMICILI          ← label
 *  C. DEP BENJAMIN RODRIGUEZ ... ← valor (puede ser varias líneas)
 *  ALICANTE                      ← ciudad
 *  ALICANTE                      ← provincia
 *
 *  LUGAR DE NACIMIENTO / LLOC DE NAIXEMENT  ← label
 *  BOGOTA                                   ← ciudad
 *  COLOMBIA                                 ← país
 *
 *  HIJO/A DE / FILL/A DE         ← label
 *  CRISTIAN CAMILO / DAYAN YELITZA ← valor
 *
 *  EQUIPO / EQUIP   03092A6D1   ← lateral izquierdo (vertical en el documento)
 *
 *  MRZ (TD1 — 3 líneas × 30 chars):
 *  IDESPCIA136800660406878T<<<<<<
 *  1408175M2905165ESP<<<<<<<<<<<4
 *  SUESCA<VALLEJO<<DAVID<SANTIAGO
 */
@Component
public class EspDNINewExtractor extends BaseExtractor {

    // ── Número DNI ────────────────────────────────────────────────────────────
    // Formato: 8 dígitos + letra control NIF (excluye I, O, U, Ñ)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "DNI[\\s:]*(\\d{8}[A-HJ-NP-TV-Z])",
            Pattern.CASE_INSENSITIVE);

    // ── Apellidos ─────────────────────────────────────────────────────────────
    // El label "APELLIDOS / COGNOMS" va antes, los valores en las líneas siguientes
    // Captura 1 o 2 líneas de apellidos hasta encontrar el label NOMBRE / NOM
    private static final Pattern APELLIDOS = Pattern.compile(
            "APELLIDOS?(?:\\s*/\\s*COGNOMS?)?\\s*\\n" +
            "((?:[A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]*\\n?){1,3})" +
            "(?=\\s*NOMBRE|\\s*NOM\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // ── Nombre ────────────────────────────────────────────────────────────────
    // El label "NOMBRE / NOM" va antes, el valor en la línea siguiente
    private static final Pattern NOMBRE = Pattern.compile(
            "NOMBRE(?:\\s*/\\s*NOM)?\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // ── Sexo ──────────────────────────────────────────────────────────────────
    // Aparece en fila de valores: "M    ESP    17 08 2014"
    // El label SEXO/SEXE está en la línea anterior; el valor es M o F aislado
    private static final Pattern SEXO = Pattern.compile(
            "SEXO(?:\\s*/\\s*SEXE)?[\\s\\S]{0,80}?\\n([MF])\\s+[A-Z]{2,3}\\s+\\d{2}\\s\\d{2}\\s\\d{4}",
            Pattern.CASE_INSENSITIVE);

    // ── Nacionalidad ─────────────────────────────────────────────────────────
    // "M    ESP    17 08 2014" — captura el código de 3 letras entre sexo y fecha
    private static final Pattern NACIONALIDAD = Pattern.compile(
            "SEXO(?:\\s*/\\s*SEXE)?[\\s\\S]{0,80}?\\n[MF]\\s+([A-Z]{3})\\s+\\d{2}\\s\\d{2}\\s\\d{4}",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha nacimiento ──────────────────────────────────────────────────────
    // "17 08 2014" — formato DD MM YYYY con espacios (layout DNIe)
    private static final Pattern FECHA_NAC = Pattern.compile(
            "NACIMIENTO(?:\\s*/\\s*NAIXEMENT)?[\\s\\S]{0,80}?\\n[MF]\\s+[A-Z]{3}\\s+(\\d{2}\\s\\d{2}\\s\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha emisión ────────────────────────────────────────────────────────
    // "16 05 2024" en la fila de valores bajo "EMISIÓN / EMISSIÓ"
    private static final Pattern FECHA_EMISION = Pattern.compile(
            "EMISI[OÓ]N(?:\\s*/\\s*EMISS[IÍ]O)?[\\s\\S]{0,80}?\\n(\\d{2}\\s\\d{2}\\s\\d{4})\\s+\\d{2}\\s\\d{2}\\s\\d{4}",
            Pattern.CASE_INSENSITIVE);

    // ── Fecha validez ────────────────────────────────────────────────────────
    // "16 05 2029" — segundo date en la fila de valores
    private static final Pattern FECHA_VALIDEZ = Pattern.compile(
            "EMISI[OÓ]N(?:\\s*/\\s*EMISS[IÍ]O)?[\\s\\S]{0,80}?\\n\\d{2}\\s\\d{2}\\s\\d{4}\\s+(\\d{2}\\s\\d{2}\\s\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // ── Número soporte (CAN) ─────────────────────────────────────────────────
    // "NUM SOPORTE\nCIA136800"
    private static final Pattern NUM_SOPORTE = Pattern.compile(
            "NUM\\s+SOPORTE\\s*\\n([A-Z]{0,3}\\d{6,9})",
            Pattern.CASE_INSENSITIVE);

    // ── Reverso: Domicilio ────────────────────────────────────────────────────
    // "DOMICILIO / DOMICILI\nDIRECCION...\nCIUDAD\nPROVINCIA"
    private static final Pattern DOMICILIO_CALLE = Pattern.compile(
            "DOMICILIO(?:\\s*/\\s*DOMICILI)?\\s*\\n([^\\n]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOMICILIO_CIUDAD = Pattern.compile(
            "DOMICILIO(?:\\s*/\\s*DOMICILI)?\\s*\\n[^\\n]+\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOMICILIO_PROVINCIA = Pattern.compile(
            "DOMICILIO(?:\\s*/\\s*DOMICILI)?\\s*\\n[^\\n]+\\n[A-ZÁÉÍÓÚÑ][^\\n]+\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // ── Reverso: Lugar de nacimiento ─────────────────────────────────────────
    // "LUGAR DE NACIMIENTO / LLOC DE NAIXEMENT\nBOGOTA\nCOLOMBIA"
    private static final Pattern LUGAR_NAC_CIUDAD = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO(?:\\s*/\\s*LLOC[^\\n]*)?\\s*\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_NAC_PAIS = Pattern.compile(
            "LUGAR\\s+DE\\s+NACIMIENTO(?:\\s*/\\s*LLOC[^\\n]*)?\\s*\\n[A-ZÁÉÍÓÚÑ][^\\n]+\\n([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ\\s]+?)\\n",
            Pattern.CASE_INSENSITIVE);

    // ── Reverso: Hijo/a de ────────────────────────────────────────────────────
    private static final Pattern HIJO_DE = Pattern.compile(
            "HIJO/?A?\\s+DE(?:\\s*/\\s*FILL/?A?\\s+DE)?\\s*\\n([A-ZÁÉÍÓÚÑ][^\\n]+)",
            Pattern.CASE_INSENSITIVE);

    // ── Reverso: Equipo/chip ─────────────────────────────────────────────────
    // Aparece vertical en el lateral: "EQUIPO / EQUIP\n03092A6D1" o "03092A6D1" solo
    private static final Pattern EQUIPO = Pattern.compile(
            "(?:EQUIPO(?:\\s*/\\s*EQUIP)?\\s*\\n([\\w]+))" +
            "|(?:^([0-9A-F]{9})$)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.ESP_DNI_NEW;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // ── Cara frontal ──────────────────────────────────────────────────────

        // Número DNI
        fields.add(field("documentNumber", ocrText, DOC_NUMBER, 0.96));

        // Apellidos — puede ser 1 o 2 líneas, unir con espacio
        extract(ocrText, APELLIDOS).ifPresent(v -> {
            String normalized = v.replaceAll("\\n", " ").replaceAll("\\s+", " ").trim();
            fields.add(ExtractedField.of("apellidos", normalized, 0.90));
        });

        // Nombre
        extract(ocrText, NOMBRE).ifPresent(v ->
                fields.add(ExtractedField.of("nombre", v.trim(), 0.90)));

        // Sexo (de la fila de tabla)
        fields.add(field("sexo",         ocrText, SEXO,         0.93));
        fields.add(field("nacionalidad",  ocrText, NACIONALIDAD, 0.92));

        // Fechas con espacios "DD MM YYYY"
        extract(ocrText, FECHA_NAC).ifPresent(v ->
                fields.add(ExtractedField.of("fechaNacimiento",
                        normalizeDateSpaces(v), 0.92)));

        extract(ocrText, FECHA_EMISION).ifPresent(v ->
                fields.add(ExtractedField.of("fechaEmision",
                        normalizeDateSpaces(v), 0.92)));

        extract(ocrText, FECHA_VALIDEZ).ifPresent(v ->
                fields.add(ExtractedField.of("fechaValidez",
                        normalizeDateSpaces(v), 0.92)));

        // Número soporte
        fields.add(field("numSoporte", ocrText, NUM_SOPORTE, 0.92));

        // ── Cara posterior ────────────────────────────────────────────────────

        extract(ocrText, DOMICILIO_CALLE).ifPresent(v ->
                fields.add(ExtractedField.of("domicilioCalle", v.trim(), 0.84)));

        extract(ocrText, DOMICILIO_CIUDAD).ifPresent(v ->
                fields.add(ExtractedField.of("domicilioCiudad", v.trim(), 0.82)));

        extract(ocrText, DOMICILIO_PROVINCIA).ifPresent(v ->
                fields.add(ExtractedField.of("domicilioProvincia", v.trim(), 0.82)));

        extract(ocrText, LUGAR_NAC_CIUDAD).ifPresent(v ->
                fields.add(ExtractedField.of("ciudadNacimiento", v.trim(), 0.87)));

        extract(ocrText, LUGAR_NAC_PAIS).ifPresent(v ->
                fields.add(ExtractedField.of("paisNacimiento", v.trim(), 0.87)));

        extract(ocrText, HIJO_DE).ifPresent(v ->
                fields.add(ExtractedField.of("hijoDe", v.trim(), 0.88)));

        // Equipo (chip)
        Matcher em = EQUIPO.matcher(ocrText);
        if (em.find()) {
            String eq = em.group(1) != null ? em.group(1) : em.group(2);
            if (eq != null) fields.add(ExtractedField.of("equipo", eq.trim(), 0.85));
        }

        return fields;
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REINO DE ESPAÑA", "ESPAÑA",
                "DOCUMENTO NACIONAL DE IDENTIDAD", "DNI",
                "NUM SOPORTE", "NACIMIENTO/NAIXEMENT",
                "APELLIDOS / COGNOMS", "NOMBRE / NOM",
                "EMISIÓN", "DOMICILIO / DOMICILI",
                "LUGAR DE NACIMIENTO / LLOC",
                "HIJO/A DE", "NATIONAL IDENTITY CARD");
        return Math.min(hits * 0.09, 1.0);
    }

    // "17 08 2014" → "2014-08-17"
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
