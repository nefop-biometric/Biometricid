package com.eduin.onboarding.ocr.extractor.colombia;

import com.eduin.onboarding.ocr.dto.OcrRequest;
import com.eduin.onboarding.ocr.extractor.BaseExtractor;
import com.eduin.onboarding.ocr.model.DocumentType;
import com.eduin.onboarding.ocr.model.ExtractedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extractor para Cédula de Ciudadanía Colombiana (versión antigua / amarilla).
 *
 * LAYOUT REAL del documento (el label va DEBAJO del valor):
 *
 *   NUMERO  7.178.857          ← número
 *   PARADA CUERVO              ← apellidos (sin label previo)
 *   APELLIDOS                  ← label después del valor
 *   DIEGO ARMANDO              ← nombres
 *   NOMBRES                    ← label después del valor
 *   [firma]
 *   FIRMA
 *
 * Reverso:
 *   FECHA DE NACIMIENTO   13-NOV-1976
 *   NEIVA
 *   (HUILA)
 *   LUGAR DE NACIMIENTO
 *   1.64        O+       M
 *   ESTATURA   G.S. RH  SEXO
 *   13-ENE-1995 NEIVA
 *   FECHA Y LUGAR DE EXPEDICION
 */
@Component
public class ColCCOldExtractor extends BaseExtractor {

    // ── Número de documento ───────────────────────────────────────────────────
    // "NUMERO  7.178.857"  o  "NUMERO 52.538 .847" (OCR puede insertar espacio)
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "NUMERO\\s+([\\d.\\s]{5,22}\\d)", Pattern.CASE_INSENSITIVE);
    // Layout invertido (frecuente en el OCR real): el número en la línea ANTERIOR
    // al label — "79.108.562\nNUMERO"
    private static final Pattern DOC_NUMBER2 = Pattern.compile(
            "^(\\d[\\d.\\s]{4,20}\\d)\\s*\\n+\\s*NUMERO",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Apellidos: línea(s) ANTES del label "APELLIDOS" ──────────────────────
    // Puede haber una línea en blanco entre el valor y el label.
    // Se usa [^\n]+ para no capturar caracteres acentuados mal codificados por OCR.
    private static final Pattern APELLIDOS = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑa-záéíóúñ][^\\n]{1,50}?)\\s*\\n+\\s*APEL\\w{0,4}DOS",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Nombres: línea ANTES del label "NOMBRES" ─────────────────────────────
    // Mínimo 5 caracteres totales ({4,50}) para evitar artefactos del OCR invertido ("PAS", "AS", etc.)
    private static final Pattern NOMBRES = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑa-záéíóúñ][^\\n]{4,50}?)\\s*\\n+\\s*N[O0]MB?RES",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Reverso ───────────────────────────────────────────────────────────────

    // "FECHA DE NACIMIENTO 13-NOV-1976" — OCR omite la I ("NACIMENTO"), o sustituye O por 0 ("0CT")
    private static final Pattern FECHA_NAC = Pattern.compile(
            "FECHA\\s+DE\\s+NACI\\w{0,7}O\\.?\\s+(\\d{2}-[A-Z0-9]{3,4}\\.?-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // Ciudad antes de "(DPTO)\nLUGAR[...] DE NACIMIENTO"
    private static final Pattern LUGAR_NAC = Pattern.compile(
            "^([A-ZÁÉÍÓÚÑa-záéíóúñ][^\\n]{1,50}?)\\s*\\n+\\s*\\([^)]{2,30}\\)\\s*\\n+[^\\n]*?NACIMIENTO",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Departamento entre paréntesis: (HUILA), (CUNDINAMARCA) …
    // OCR puede partir la palabra en dos líneas: "(CUNDIN" + "AMARCA)"
    private static final Pattern DEPTO_NAC = Pattern.compile(
            "\\(([A-ZÁÉÍÓÚÑa-záéíóúñ\\s]{2,30})\\)[^\\n]*\\n+[^\\n]*?NACIMIENTO",
            Pattern.CASE_INSENSITIVE);

    // Estatura: "1.64", "1,64", o "164"/"168" (OCR omite el punto decimal)
    private static final Pattern ESTATURA = Pattern.compile(
            "(\\d[,.]?\\d{2})\\s+[ABO0]{1,2}[+-]",
            Pattern.CASE_INSENSITIVE);

    // Grupo sanguíneo en misma línea que estatura
    private static final Pattern SANGRE = Pattern.compile(
            "\\d[,.]?\\d{2}\\s+([ABO0]{1,2}[+-])",
            Pattern.CASE_INSENSITIVE);

    // Sexo: "M" o "F" en la misma línea que estatura+sangre, o en línea con label SEXO
    private static final Pattern SEXO_INLINE = Pattern.compile(
            "\\d[,.]?\\d{2}\\s+[ABO0]{1,2}[+-]\\s+([MF])\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEXO_LABEL = Pattern.compile(
            "\\b([MF])\\s*\\n+\\s*SEXO",
            Pattern.CASE_INSENSITIVE);

    // "13-ENE-1995 NEIVA\nFECHA Y LUGAR DE EXPEDICION"
    // Fecha puede tener punto: "12-FEB.-1973" / mes con 0 en lugar de O: "30-MAR-2010"
    // Label puede estar garbleado: "an Y LUGAR DE EXPEDICION", "JGAR DE EXPEDICION"
    private static final Pattern FECHA_EXP = Pattern.compile(
            "(\\d{2}-[A-Z0-9]{3,4}\\.?-\\d{4})\\s+[^\\n]+\\n+[^\\n]*?LUGAR\\s+DE\\s+EXPEDICION",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LUGAR_EXP = Pattern.compile(
            "\\d{2}-[A-Z0-9]{3,4}\\.?-\\d{4}\\s+([A-ZÁÉÍÓÚÑa-záéíóúñ][^\\n]{1,50}?)\\s*\\n+[^\\n]*?LUGAR\\s+DE\\s+EXPEDICION",
            Pattern.CASE_INSENSITIVE);

    // ── Código inferior (pie de página) ──────────────────────────────────────
    // Formato: A-0700100-00061141-M-0007178857-20080830
    private static final Pattern CODIGO_INF    = Pattern.compile(
            "([APE]-\\d{7}-\\d{8}-[MF]-\\d{7,13}-\\d{8})", Pattern.CASE_INSENSITIVE);
    // OCR degradado: separadores como espacio, número/fecha final truncados
    // "A-2400200-61240236-F 005215405"
    private static final Pattern CODIGO_INF_PARTIAL = Pattern.compile(
            "([APE][-\\s]\\d{7}[-\\s]\\d{8}[-\\s][MF][-\\s]?(\\d{7,13}))", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOC_FROM_COD  = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-[MF]-(\\d{7,13})-\\d{8}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEX_FROM_COD  = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-([MF])-\\d{7,13}-\\d{8}", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEXP_FROM_COD = Pattern.compile(
            "[APE]-\\d{7}-\\d{8}-[MF]-\\d{7,13}-(\\d{8})", Pattern.CASE_INSENSITIVE);

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.COL_CC_OLD;
    }

    @Override
    public List<ExtractedField> extract(String ocrText) {
        List<ExtractedField> fields = new ArrayList<>();

        // Número: "NUMERO 52.538 .847" → quitar puntos y espacios → "52538847"
        // El label puede venir antes o después del valor según cómo lea el OCR.
        Optional<String> numOpt = extract(ocrText, DOC_NUMBER);
        if (numOpt.isEmpty()) numOpt = extract(ocrText, DOC_NUMBER2);
        numOpt.ifPresent(v ->
                fields.add(ExtractedField.of("documentNumber",
                        v.replaceAll("[.\\s]", ""), 0.90)));

        // Apellidos (línea anterior al label "APELLIDOS")
        extract(ocrText, APELLIDOS).ifPresent(v ->
                fields.add(ExtractedField.of("apellidos",
                        v.replaceAll("[^A-ZÁÉÍÓÚÑa-záéíóúñ\\s]", "").trim(), 0.87)));

        // Nombres (línea anterior al label "NOMBRES")
        extract(ocrText, NOMBRES).ifPresent(v ->
                fields.add(ExtractedField.of("nombres",
                        v.replaceAll("[^A-ZÁÉÍÓÚÑa-záéíóúñ\\s]", "").trim(), 0.87)));

        // Reverso
        fields.add(field("fechaNacimiento", ocrText, FECHA_NAC,  0.90));
        fields.add(field("lugarNacimiento", ocrText, LUGAR_NAC,  0.80));
        fields.add(field("departamento",    ocrText, DEPTO_NAC,  0.80));
        fields.add(field("estatura",        ocrText, ESTATURA,   0.85));
        fields.add(field("grupoSanguineo",  ocrText, SANGRE,     0.88));

        // Sexo: primero en la línea de estatura, si no, antes del label SEXO
        Optional<String> sexoOpt = extract(ocrText, SEXO_INLINE);
        if (sexoOpt.isEmpty()) sexoOpt = extract(ocrText, SEXO_LABEL);
        sexoOpt.ifPresent(v -> fields.add(ExtractedField.of("sexo", v.toUpperCase(), 0.90)));

        fields.add(field("fechaExpedicion", ocrText, FECHA_EXP,  0.88));
        fields.add(field("lugarExpedicion", ocrText, LUGAR_EXP,  0.80));

        // ── Código inferior (fuente de máxima fiabilidad) ──────────────────
        Optional<String> codigoFull = extract(ocrText, CODIGO_INF);
        codigoFull.ifPresent(codigo -> {
            fields.add(ExtractedField.of("codigoInferior", codigo, 0.95));

            // Número del documento desde el código (quitar ceros a la izquierda)
            extract(ocrText, DOC_FROM_COD).ifPresent(docNum -> {
                String clean = docNum.replaceFirst("^0+", "");
                fields.stream()
                      .filter(f -> "documentNumber".equals(f.getFieldName()))
                      .findFirst()
                      .ifPresentOrElse(
                              f -> { if (f.getValue() == null) { f.setValue(clean); f.setConfidence(0.97); } },
                              ()  -> fields.add(ExtractedField.of("documentNumber", clean, 0.97))
                      );
            });

            // Sexo del código si no se leyó del reverso
            extract(ocrText, SEX_FROM_COD).ifPresent(sexo ->
                    fields.stream()
                          .filter(f -> "sexo".equals(f.getFieldName()) && f.getValue() == null)
                          .findFirst()
                          .ifPresent(f -> { f.setValue(sexo); f.setConfidence(0.97); }));

            // Fecha de generación del código PDF417
            extract(ocrText, FEXP_FROM_COD).ifPresent(fechaCod -> {
                if (fechaCod.length() == 8) {
                    String fechaNorm = fechaCod.substring(0,4) + "-"
                            + fechaCod.substring(4,6) + "-" + fechaCod.substring(6,8);
                    fields.add(ExtractedField.of("fechaGeneracionCodigo", fechaNorm, 0.96));

                    // Alerta si difiere de la fecha impresa
                    fields.stream()
                          .filter(f -> "fechaExpedicion".equals(f.getFieldName())
                                  && f.getValue() != null && !f.getValue().equals(fechaNorm))
                          .findFirst()
                          .ifPresent(f -> fields.add(ExtractedField.of("alertaFechaExpedicion",
                                  "DISCREPANCIA: impreso=" + f.getValue() + " codigo=" + fechaNorm,
                                  1.0)));
                }
            });
        });

        // Código degradado por OCR (espacios como separador, cola truncada):
        // suficiente para derivar el número con confianza reducida — clave para
        // correlacionar frente y reverso aunque el OCR pierda dígitos.
        if (codigoFull.isEmpty()) {
            Matcher pm = CODIGO_INF_PARTIAL.matcher(ocrText);
            if (pm.find()) {
                fields.add(ExtractedField.of("codigoInferior", pm.group(1).trim(), 0.60));
                String clean = pm.group(2).replaceFirst("^0+", "");
                fields.stream()
                      .filter(f -> "documentNumber".equals(f.getFieldName()))
                      .findFirst()
                      .ifPresentOrElse(
                              f -> { if (f.getValue() == null) { f.setValue(clean); f.setConfidence(0.60); } },
                              ()  -> fields.add(ExtractedField.of("documentNumber", clean, 0.60)));
            }
        }

        // Normalizar fechas
        fields.stream()
              .filter(f -> f.getFieldName().startsWith("fecha") && f.getValue() != null
                      && !f.getFieldName().equals("codigoInferior")
                      && !f.getFieldName().startsWith("alerta"))
              .forEach(f -> f.setValue(normalizeDate(f.getValue())));

        return fields;
    }

    // Campos que pertenecen al frente de la cédula antigua
    private static final Set<String> FRONT_FIELDS = Set.of(
            "documentNumber", "apellidos", "nombres");

    // Campos que pertenecen al reverso. El código inferior (barcode PDF417 y su
    // línea impresa) está FÍSICAMENTE en el reverso — de él se deriva también el
    // documentNumber, clave para correlacionar frente y reverso.
    private static final Set<String> BACK_FIELDS = Set.of(
            "fechaNacimiento", "lugarNacimiento", "departamento",
            "estatura", "grupoSanguineo", "sexo",
            "fechaExpedicion", "lugarExpedicion",
            "documentNumber", "codigoInferior", "fechaGeneracionCodigo",
            "alertaFechaExpedicion");

    @Override
    public List<ExtractedField> extract(String ocrText, OcrRequest.DocumentSide side) {
        List<ExtractedField> all = extract(ocrText);
        if (side == null || side == OcrRequest.DocumentSide.AUTO) return all;
        Set<String> keep = side == OcrRequest.DocumentSide.FRONT ? FRONT_FIELDS : BACK_FIELDS;
        return all.stream()
                .filter(f -> keep.contains(f.getFieldName()))
                .collect(Collectors.toList());
    }

    @Override
    public double classifyConfidence(String ocrText) {
        int hits = countKeywords(ocrText,
                "REPUBLICA DE COLOMBIA",
                "CEDULA DE CIUDADANIA",
                "IDENTIFICACION PERSONAL",
                "REGISTRADURIA",
                "REGISTRADOR NACIONAL",
                "INDICE DERECHO",
                "FECHA Y LUGAR DE EXPEDICION",
                "G.S. RH",
                "ESTATURA");

        boolean isNew = ocrText.toUpperCase().contains("DIGITAL")
                || ocrText.toUpperCase().contains("FECHA DE EXPEDICION")
                || ocrText.toUpperCase().contains("VIGENCIA");

        double score = hits * 0.12;
        if (isNew) score -= 0.2;
        return Math.min(Math.max(score, 0.0), 1.0);
    }
}
