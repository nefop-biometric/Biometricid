package com.eduin.onboarding.authenticity.service.analyzer;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import com.eduin.onboarding.authenticity.model.VerificationResult.MrzResult;
import net.sourceforge.tess4j.Tesseract;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Analiza la zona MRZ (Machine Readable Zone) de pasaportes.
 * Formato ICAO 9303 TD3: 2 líneas de 44 caracteres.
 */
@Component
public class MrzAnalyzer {

    private static final int[] WEIGHTS = {7, 3, 1};

    @Value("${truedocument.tesseract.data-path}")
    private String tessDataPath;

    public MrzResult analyzeMrz(Mat image) {
        try {
            String ocrText = extractMrzText(image);
            return parseMrz(ocrText);
        } catch (Exception e) {
            return MrzResult.builder()
                    .detected(false)
                    .valid(false)
                    .checksumErrors("Error al procesar MRZ: " + e.getMessage())
                    .build();
        }
    }

    public AnalysisDetail toAnalysisDetail(MrzResult mrz) {
        List<String> findings = new ArrayList<>();

        if (!mrz.isDetected()) {
            findings.add("No se detectó zona MRZ en la imagen");
        } else if (!mrz.isCheckDigitsValid()) {
            findings.add("Dígitos verificadores MRZ inválidos: " + mrz.getChecksumErrors());
        }

        double score;
        if (!mrz.isDetected())     score = 0.3;
        else if (mrz.isValid())    score = 1.0;
        else if (mrz.isCheckDigitsValid()) score = 0.8;
        else                       score = 0.2;

        return AnalysisDetail.builder()
                .analyzer("MRZ_VALIDATION")
                .score(score)
                .passed(score >= 0.7)
                .verdict(score >= 0.7 ? "MRZ válida" : "MRZ con errores")
                .findings(findings)
                .warnings(List.of())
                .build();
    }

    // ── OCR de zona MRZ ──────────────────────────────────────────────────────

    private String extractMrzText(Mat image) throws Exception {
        Mat gray   = new Mat();
        Mat binary = new Mat();
        Mat scaled = new Mat();

        cvtColor(image, gray, COLOR_BGR2GRAY);
        adaptiveThreshold(gray, binary, 255,
                ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, 11, 2);
        resize(binary, scaled, new Size(binary.cols() * 2, binary.rows() * 2),
                0, 0, INTER_CUBIC);

        Path tmp = Files.createTempFile("mrz_", ".png");
        try {
            opencv_imgcodecs.imwrite(tmp.toString(), scaled);

            Tesseract tess = new Tesseract();
            tess.setDatapath(tessDataPath);
            tess.setLanguage("eng");
            tess.setPageSegMode(6);
            tess.setOcrEngineMode(1);
            tess.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<");

            return tess.doOCR(tmp.toFile());
        } finally {
            Files.deleteIfExists(tmp);
            gray.release(); binary.release(); scaled.release();
        }
    }

    // ── Parsing MRZ ICAO 9303 ─────────────────────────────────────────────────

    private MrzResult parseMrz(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return MrzResult.builder().detected(false).valid(false).build();
        }

        String upper = rawText.toUpperCase();

        // 1) Buscar líneas TD3 (44 chars ±4) y firmas TD1 ESTRICTAS, línea por línea.
        // Las firmas TD1 deben ser inconfundibles: fragmentos truncados de un MRZ
        // TD3 no pueden matchearlas (TD3 L1 empieza con P, TD3 L2 con letras del nº doc).
        List<String> td3Lines = new ArrayList<>();
        int td1LineCount = 0;
        for (String rl : upper.split("\\n")) {
            String cl = rl.replaceAll("[^A-Z0-9<]", "");
            if (cl.length() >= 40 && cl.length() <= 48) {
                td3Lines.add(cl.length() >= 44 ? cl.substring(0, 44)
                        : cl + "<".repeat(44 - cl.length()));
            } else if (cl.length() >= 26 && cl.length() <= 34 && !cl.startsWith("P")) {
                // TD1 L1: docCode(I/A/C, OCR a veces lee '1') + país + run de dígitos
                boolean isTd1L1 = cl.matches("^[IAC1][A-Z<][A-Z0-9]{3}\\d{5,}.*");
                // TD1 L2: DOB(7 dígitos) + sexo + expiry(7 dígitos) + nacionalidad
                boolean isTd1L2 = cl.matches("^\\d{7}[MF]\\d{7}[A-Z0-9]{3}.*");
                if (isTd1L1 || isTd1L2) td1LineCount++;
            }
        }

        if (td3Lines.size() >= 2) {
            String l1 = td3Lines.stream().filter(l -> l.startsWith("P")).findFirst().orElse(null);
            if (l1 != null) {
                final String l1f = l1;
                String l2 = td3Lines.stream().filter(l -> !l.equals(l1f)).findFirst().orElse(td3Lines.get(1));
                MrzResult strict = decodeTd3(l1, l2);
                if (strict.isValid()) return strict;
                // Posiciones fijas fallaron (OCR pierde '<' y desalinea campos):
                // intentar decodificación estructural por regex
                MrzResult byRegex = regexDecodeTd3(upper);
                return byRegex != null ? byRegex : strict;
            }
        }

        // 2) Sin líneas TD3: 2+ firmas TD1 → es una cédula, no un pasaporte
        if (td1LineCount >= 2) {
            return MrzResult.builder()
                    .detected(true).valid(false).wrongFormat(true)
                    .checksumErrors("El MRZ detectado es formato TD1 (documento de identidad tipo cédula), no TD3 de pasaporte")
                    .build();
        }

        // 3) Decodificación estructural: localiza la ESTRUCTURA TD3 sin depender
        // de posiciones fijas (el OCR pierde caracteres '<' y desalinea todo)
        MrzResult byRegex = regexDecodeTd3(upper);
        if (byRegex != null) return byRegex;

        // 4) Fallback texto corrido: anclar donde empieza un MRZ de pasaporte
        String cleaned = upper.replaceAll("[^A-Z0-9<]", "");
        Matcher anchorM = Pattern.compile("P[<KXSC][A-Z]{3}").matcher(cleaned);
        if (anchorM.find() && cleaned.length() - anchorM.start() >= 88) {
            int anchor = anchorM.start();
            return decodeTd3(cleaned.substring(anchor, anchor + 44),
                             cleaned.substring(anchor + 44, anchor + 88));
        }

        return MrzResult.builder()
                .detected(false).valid(false)
                .checksumErrors("No se encontraron dos líneas MRZ TD3 de 44 caracteres")
                .build();
    }

    /**
     * Decodifica la línea 2 del TD3 por ESTRUCTURA en lugar de posiciones fijas:
     *   docNum(8-9) + check + país(3) + dob(6) + check + sexo + expiry(6) + check
     * Tolera '<' perdidos por el OCR (que desalinean las posiciones) y 0↔O.
     * Solo devuelve resultado si los dígitos verificadores ICAO validan —
     * texto arbitrario no puede pasar ese filtro.
     */
    private MrzResult regexDecodeTd3(String upperText) {
        String joined = upperText.replaceAll("[^A-Z0-9<\\n]", "");

        // Línea 2: estructura numérica con dígitos verificadores
        Matcher m = Pattern.compile(
                "([A-Z0-9]{2}\\d{6})<?(\\d)([A-Z0]{3})(\\d{6})(\\d)([MF])(\\d{6})(\\d)")
                .matcher(joined.replace("\n", ""));
        if (!m.find()) return null;

        String docNumber = m.group(1);
        char   dnCheck   = m.group(2).charAt(0);
        String nat       = m.group(3).replace('0', 'O');
        String dob       = m.group(4);
        char   dobCheck  = m.group(5).charAt(0);
        String sex       = m.group(6);
        String expiry    = m.group(7);
        char   expCheck  = m.group(8).charAt(0);

        // Verificación ICAO sobre lo extraído (docNum se rellena a 9 con '<')
        String docField = docNumber.length() >= 9 ? docNumber.substring(0, 9)
                : docNumber + "<".repeat(9 - docNumber.length());
        boolean docValid = checkDigit(docField, dnCheck);
        boolean dobValid = checkDigit(dob, dobCheck);
        boolean expValid = checkDigit(expiry, expCheck);

        int validCount = (docValid ? 1 : 0) + (dobValid ? 1 : 0) + (expValid ? 1 : 0);
        if (validCount < 2) return null;

        // Línea 1: nombres — normalizar '<' degradado a K/X/S/C y perdido tras P
        String surname = "", givenNames = "";
        for (String rl : upperText.split("\\n")) {
            String cl = rl.replaceAll("[^A-Z0-9<]", "");
            if (cl.length() < 20 || !cl.startsWith("P")) continue;
            String names = cl.replaceAll("[KXSCQ<]{2,}$", "");
            names = names.replaceFirst("^P[<KXSC]?", "");
            names = names.replaceFirst("^([A-Z]{3})", "");   // código de país
            names = names.replaceAll("([A-Z])([KXSC]{2})([A-Z])", "$1<<$3");
            names = names.replaceAll("([A-Z])([KXSC])([A-Z])", "$1<$3");
            int sep = names.indexOf("<<");
            if (sep > 0) {
                surname    = names.substring(0, sep).replace('<', ' ').trim();
                givenNames = names.substring(sep + 2).replace('<', ' ').trim();
            } else {
                surname = names.replace('<', ' ').trim();
            }
            break;
        }

        boolean allValid = docValid && dobValid && expValid;
        return MrzResult.builder()
                .detected(true).valid(allValid)
                .documentNumber(docNumber)
                .nationality(nat)
                .dateOfBirth(fmtDate(dob))
                .expiryDate(fmtDate(expiry))
                .surname(surname).givenNames(givenNames)
                .checkDigitsValid(allValid)
                .checksumErrors(allValid ? null
                        : String.format("Decodificación estructural: %d/3 dígitos verificadores válidos", validCount))
                .build();
    }

    private MrzResult decodeTd3(String l1, String l2) {
        List<String> errors = new ArrayList<>();

        String countryCode = l1.substring(2, 5).replace("<", "");
        String namePart    = l1.substring(5);
        String[] ns        = namePart.split("<<", 2);
        String surname     = ns[0].replace("<", " ").trim();
        String givenNames  = ns.length > 1 ? ns[1].replace("<", " ").trim() : "";

        String docNumber = l2.substring(0, 9).replace("<", "");
        char   dnCheck   = l2.charAt(9);
        String nat       = l2.substring(10, 13).replace("<", "");
        String dob       = l2.substring(13, 19);
        char   dobCheck  = l2.charAt(19);
        String expiry    = l2.substring(21, 27);
        char   expCheck  = l2.charAt(27);
        String optional  = l2.substring(28, 42);
        char   compCheck = l2.charAt(43);

        if (!checkDigit(l2.substring(0, 9), dnCheck))   errors.add("número de documento");
        if (!checkDigit(dob, dobCheck))                   errors.add("fecha de nacimiento");
        if (!checkDigit(expiry, expCheck))                errors.add("fecha de expiración");
        if (!checkDigit(l2.substring(0, 10) + dob + dobCheck + expiry + expCheck + optional, compCheck))
            errors.add("dígito compuesto");

        boolean ok = errors.isEmpty();

        return MrzResult.builder()
                .detected(true).valid(ok)
                .rawLine1(l1).rawLine2(l2)
                .documentNumber(docNumber)
                .nationality(nat)
                .dateOfBirth(fmtDate(dob))
                .expiryDate(fmtDate(expiry))
                .surname(surname).givenNames(givenNames)
                .checkDigitsValid(ok)
                .checksumErrors(ok ? null : "Errores en: " + String.join(", ", errors))
                .build();
    }

    private boolean checkDigit(String data, char expected) {
        int sum = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int v = c == '<' ? 0 : c >= 'A' ? c - 'A' + 10 : c - '0';
            sum += v * WEIGHTS[i % 3];
        }
        return sum % 10 == (expected - '0');
    }

    private String fmtDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) return yymmdd;
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        return (yy > 30 ? "19" : "20") + yymmdd.substring(0, 2) + "-"
                + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
    }
}
