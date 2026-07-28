package com.eduin.onboarding.ocr.model;

import lombok.Builder;
import lombok.Data;

/**
 * Datos extraídos de un código de barras PDF417 (o 1D/QR como fallback).
 *
 * Para la Cédula de Ciudadanía Colombiana (antigua) el código inferior contiene:
 *   [A|P|E]-SERIENUMREGISTRADURIA-CONSECUTIVO-SEXO-NUMERODOCUMENTO-FECHAEXPEDICION
 *   Ejemplo: A-1500113-45152074-M-0079108562-20060831
 *
 * Para otros documentos (pasaportes, etc.) se almacena el rawValue completo.
 */
@Data
@Builder
public class Pdf417Data {

    /** Texto crudo decodificado del código de barras. */
    private String rawValue;

    /** Formato detectado: PDF_417, QR_CODE, CODE_128, etc. */
    private String format;

    // ── Campos parseados (solo para CC Colombia antigua) ──────────────────────

    /** Serie de la Registraduría: prefijo A/P/E + 7 dígitos. */
    private String serieRegistraduria;

    /** Número de documento (sin ceros a la izquierda). */
    private String documentNumber;

    /** Sexo: M o F. */
    private String sex;

    /** Fecha de expedición en formato YYYY-MM-DD. */
    private String fechaExpedicion;

    /** Consecutivo interno del código. */
    private String consecutivo;

    /** true si el rawValue coincide con el patrón de CC Colombia antigua. */
    private boolean parsedColombianCC;
}
