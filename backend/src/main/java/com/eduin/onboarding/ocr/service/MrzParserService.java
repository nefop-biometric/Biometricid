package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.model.MrzData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de zonas MRZ (Machine Readable Zone) según norma ICAO 9303.
 * Soporta TD1 (3 líneas × 30 chars — cédulas) y TD3 (2 líneas × 44 chars — pasaportes).
 */
@Slf4j
@Service
public class MrzParserService {

    private static final int[] CHECK_WEIGHTS = {7, 3, 1};
    private static final String VALID_MRZ_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<";

    // Regex para detectar líneas MRZ dentro del texto OCR crudo
    private static final Pattern MRZ_LINE_PATTERN =
            Pattern.compile("[A-Z0-9<]{28,48}", Pattern.MULTILINE);

    public MrzData parse(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) return null;

        String[] lines = extractMrzLines(rawOcrText);
        if (lines == null) return null;

        if (lines.length == 2 && lines[0].length() == 44) {
            return parseTD3(lines);
        } else if (lines.length == 3 && lines[0].length() == 30) {
            return parseTD1(lines);
        }

        return null;
    }

    // TD3: Pasaportes (2 líneas × 44 chars)
    private MrzData parseTD3(String[] lines) {
        String l1 = fixTd3Line1(padOrTrim(lines[0], 44));
        String l2 = fixTd3Line2(padOrTrim(lines[1], 44));

        String surnames = "", givenNames = "";
        String namesArea = l1.substring(5).replaceAll("<+$", "");
        int dblSep = namesArea.indexOf("<<");
        if (dblSep > 0) {
            surnames = namesArea.substring(0, dblSep).replace("<", " ").trim();
            givenNames = namesArea.substring(dblSep + 2).replace("<", " ").trim();
        } else {
            surnames = namesArea.replace("<", " ").trim();
        }

        String docNumber = l2.substring(0, 9).replace("<", "");
        String docCheckDigit = l2.substring(9, 10);
        boolean docValid = verifyCheckDigit(l2.substring(0, 9), docCheckDigit);

        String dob = l2.substring(13, 19);
        String dobCheck = l2.substring(19, 20);
        boolean dobValid = verifyCheckDigit(dob, dobCheck);

        String expiry = l2.substring(21, 27);
        String expiryCheck = l2.substring(27, 28);
        boolean expiryValid = verifyCheckDigit(expiry, expiryCheck);

        String compositeData = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43);
        String compositeCheck = l2.substring(43, 44);
        boolean compositeValid = verifyCheckDigit(compositeData, compositeCheck);

        return MrzData.builder()
                .mrzType("TD3")
                .rawLine1(l1)
                .rawLine2(l2)
                .documentCode(l1.substring(0, 2).replace("<", "").trim())
                .issuingCountry(l1.substring(2, 5).replace("<", ""))
                .surnames(surnames)
                .givenNames(givenNames)
                .documentNumber(docNumber)
                .documentNumberCheckDigit(docCheckDigit)
                .documentNumberValid(docValid)
                .dateOfBirth(dob)
                .dateOfBirthCheckDigit(dobCheck)
                .dateOfBirthValid(dobValid)
                .sex(l2.substring(20, 21))
                .expiryDate(expiry)
                .expiryDateCheckDigit(expiryCheck)
                .expiryDateValid(expiryValid)
                .nationality(l2.substring(10, 13).replace("<", ""))
                .optionalData1(l2.substring(28, 42).replace("<", " ").trim())
                .compositeCheckDigit(compositeCheck)
                .compositeValid(compositeValid)
                .allCheckDigitsValid(docValid && dobValid && expiryValid && compositeValid)
                .build();
    }

    // TD1: Cédulas (3 líneas × 30 chars)
    private MrzData parseTD1(String[] lines) {
        String l1 = fixTd1Line1(padOrTrim(lines[0], 30));
        String l2 = fixTd1Line2(padOrTrim(lines[1], 30));
        String l3 = fixOcrConfusion(padOrTrim(lines[2], 30), false);

        String docNumber = l1.substring(5, 14).replace("<", "");
        String docCheck = l1.substring(14, 15);
        boolean docValid = verifyCheckDigit(l1.substring(5, 14), docCheck);

        // Cédula colombiana: el campo "documento" de la línea 1 es el SERIAL de la
        // tarjeta física; el número de cédula del ciudadano (NUIP) viaja en el campo
        // opcional de la línea 2 (ej: 7907212M3209239COL{80009938}<<<4).
        // El OCR suele perder los '<' de relleno y el dígito compuesto final queda
        // pegado a la corrida: "800099384<<<" en vez de "80009938<<<4". Si tras la
        // corrida no aparece un dígito compuesto separado, el último dígito de la
        // corrida ES el compuesto y se descarta.
        String issuing = l1.substring(2, 5).replace("<", "");
        if ("COL".equals(issuing)) {
            String tail = l2.substring(18);
            Matcher nuipM = Pattern.compile("^(\\d{6,})(?:<+(\\d))?<*$").matcher(tail);
            if (nuipM.matches()) {
                String run = nuipM.group(1);
                if (nuipM.group(2) == null && run.length() > 6) {
                    run = run.substring(0, run.length() - 1);
                }
                docNumber = run.replaceFirst("^0+", "");
            } else {
                Matcher any = Pattern.compile("(\\d{6,})").matcher(tail);
                if (any.find()) docNumber = any.group(1).replaceFirst("^0+", "");
            }
        }

        String dob = l2.substring(0, 6);
        String dobCheck = l2.substring(6, 7);
        boolean dobValid = verifyCheckDigit(dob, dobCheck);

        String expiry = l2.substring(8, 14);
        String expiryCheck = l2.substring(14, 15);
        boolean expiryValid = verifyCheckDigit(expiry, expiryCheck);

        String compositeData = l1.substring(5) + l2.substring(0, 7) + l2.substring(8, 15) + l2.substring(18, 29);
        String compositeCheck = l2.substring(29, 30);
        boolean compositeValid = verifyCheckDigit(compositeData, compositeCheck);

        String surnames = "", givenNames = "";
        // Quitar trailing '<' y buscar '<<' como separador apellidos/nombres
        String nameArea = l3.replaceAll("<+$", "");
        int dblSep = nameArea.indexOf("<<");
        if (dblSep > 0) {
            surnames = nameArea.substring(0, dblSep).replace("<", " ").trim();
            givenNames = nameArea.substring(dblSep + 2).replace("<", " ").trim();
        } else {
            // Sin separador doble claro: todo va como apellidos
            surnames = nameArea.replace("<", " ").trim();
        }

        return MrzData.builder()
                .mrzType("TD1")
                .rawLine1(l1)
                .rawLine2(l2)
                .rawLine3(l3)
                .documentCode(l1.substring(0, 2).replace("<", "").trim())
                .issuingCountry(l1.substring(2, 5).replace("<", ""))
                .documentNumber(docNumber)
                .documentNumberCheckDigit(docCheck)
                .documentNumberValid(docValid)
                .dateOfBirth(dob)
                .dateOfBirthCheckDigit(dobCheck)
                .dateOfBirthValid(dobValid)
                .sex(l2.substring(7, 8))
                .expiryDate(expiry)
                .expiryDateCheckDigit(expiryCheck)
                .expiryDateValid(expiryValid)
                .nationality(l2.substring(15, 18).replace("<", ""))
                .optionalData1(l1.substring(15, 30).replace("<", " ").trim())
                .optionalData2(l2.substring(18, 29).replace("<", " ").trim())
                .surnames(surnames)
                .givenNames(givenNames)
                .compositeCheckDigit(compositeCheck)
                .compositeValid(compositeValid)
                .allCheckDigitsValid(docValid && dobValid && expiryValid && compositeValid)
                .build();
    }

    private String[] extractMrzLines(String text) {
        String[] rawLines = text.toUpperCase().split("\\n");

        java.util.List<String> td3Lines = new java.util.ArrayList<>();
        java.util.List<String> td1Lines = new java.util.ArrayList<>();

        for (String rawLine : rawLines) {
            // Las líneas MRZ reales son contiguas: texto normal con muchas
            // palabras ("Country code Pasaporte No...") queda descartado aquí,
            // aunque al quitar espacios alcance la longitud de una línea MRZ.
            if (countChar(rawLine.trim(), ' ') > 4) continue;

            String clean = rawLine.replaceAll("[^A-Z0-9<]", "");
            if (clean.length() < 26 || clean.length() > 48) continue;
            if (!looksLikeMrz(clean)) continue;

            String fixed = normalizeMrzLine(clean);

            if (fixed.length() >= 40 && fixed.length() <= 48) {
                td3Lines.add(padOrTrim(fixed, 44));
            } else if (fixed.length() >= 26 && fixed.length() <= 34) {
                td1Lines.add(padOrTrim(fixed, 30));
            }
        }

        // Dedup: keep the line closest to target length per unique prefix
        if (td3Lines.size() >= 2) return pickBest(td3Lines, 2);
        if (td1Lines.size() >= 3) return pickBest(td1Lines, 3);

        return null;
    }

    /**
     * Señal estructural fuerte de que la línea ES una zona MRZ y no texto
     * normal que casualmente tiene la longitud correcta.
     */
    private boolean looksLikeMrz(String line) {
        if (countChar(line, '<') >= 3) return true;                       // separadores reales
        long digits = line.chars().filter(Character::isDigit).count();
        if (digits >= 12) return true;                                    // línea numérica (L2)
        if (line.matches("^P[<KXSC][A-Z]{3}.*")) return true;             // TD3 L1: P<COL...
        if (line.matches("^[IAC][A-Z0-9<][A-Z]{3}\\d.*")) return true;    // TD1 L1: ICCOL004...
        // Línea de nombres degradada: palabras separadas por K/X/S/C con run final
        return line.matches("^[A-Z]+(?:[KXSC][A-Z]+)*[KXSC]{2,}$");
    }

    /**
     * Normaliza una línea MRZ donde el OCR confundió '<' con letras.
     * Reglas: secuencias de 3+ letras repetidas al final (KKK, SSS, CCC, XXX)
     * o secuencias mixtas de K/S/X/C al final se reemplazan por '<'.
     * También reemplaza K/S/X entre palabras en posiciones de separador.
     */
    private String normalizeMrzLine(String line) {
        // Trailing junk: 2+ chars de {K,S,X,C,Q} al final → <
        Matcher trailM = Pattern.compile("[KSXCQ]{2,}$").matcher(line);
        if (trailM.find()) {
            line = line.substring(0, trailM.start()) + "<".repeat(trailM.group().length());
        }
        // TD3 línea 1: P + no-< + COL → P<COL
        line = line.replaceAll("^P([XKSC])([A-Z]{3})", "P<$2");
        // TD1 línea 1: OCR lee 'I' como '1' → corregir inicio "1C" a "IC"
        if (line.matches("^1C[A-Z0-9]{3}.*") && line.length() >= 26) {
            line = "I" + line.substring(1);
        }
        // Separadores internos: K/X/S/C entre letras = '<'
        if (line.length() >= 5) {
            String prefix = line.substring(0, 5);
            String rest = line.substring(5);
            rest = rest.replaceAll("([A-Z])([KXSC]{2,})([A-Z])", "$1<<$3");
            rest = rest.replaceAll("([A-Z])([KXSC])([A-Z])", "$1<$3");
            line = prefix + rest;
        }
        // K/X/S/C/Q inmediatamente antes del relleno final de '<' es un '<' misleído.
        // Solo al FINAL de la línea: aplicarlo en cualquier posición corrompería
        // texto legítimo (ej: "P<COL" → "P<<OL").
        line = line.replaceAll("([KXSCQ])(<+)$", "<$2");
        return line;
    }

    /**
     * Corrige confusiones OCR comunes entre letras y dígitos.
     * En posiciones que deberían ser numéricas: O→0, I→1, S→5, B→8
     * En posiciones que deberían ser texto: 0→O, 1→I
     * @param hasNumericFields true si la línea contiene campos numéricos (líneas 1-2)
     */
    // TD1 L1: pos 0-1=docCode(text), 2-4=country(text), 5-14=docNum+check(num), 15-29=optional(num)
    private String fixTd1Line1(String line) {
        return fixByPositions(line, new int[]{0,1,2,3,4});
    }
    // TD1 L2: pos 0-6=DOB+check(num), 7=sex(text), 8-14=expiry+check(num), 15-17=nationality(text), 18-29=optional(num)
    private String fixTd1Line2(String line) {
        return fixByPositions(line, new int[]{7,15,16,17});
    }
    // TD3 L1: pos 0=P(text), 1=type(text), 2-4=country(text), 5+=names(text)
    private String fixTd3Line1(String line) {
        return fixOcrConfusion(line, false);
    }
    // TD3 L2: pos 0-9=docNum+check(num), 10-12=nationality(text), 13-19=DOB+check(num), 20=sex(text), 21-43=expiry+optional+composite(num)
    private String fixTd3Line2(String line) {
        return fixByPositions(line, new int[]{10,11,12,20});
    }

    private String fixByPositions(String line, int[] textPositions) {
        if (line == null || line.length() < 5) return line;
        char[] chars = line.toCharArray();
        java.util.Set<Integer> textSet = new java.util.HashSet<>();
        for (int p : textPositions) textSet.add(p);
        for (int i = 0; i < chars.length; i++) {
            if (textSet.contains(i)) {
                if (chars[i] == '0') chars[i] = 'O';
                else if (chars[i] == '1') chars[i] = 'I';
            } else {
                if (chars[i] == 'O') chars[i] = '0';
                else if (chars[i] == 'I') chars[i] = '1';
            }
        }
        return new String(chars);
    }

    // Línea de nombres: 0→O, 1→I
    private String fixOcrConfusion(String line, boolean unused) {
        if (line == null || line.length() < 5) return line;
        char[] chars = line.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '0') chars[i] = 'O';
            else if (chars[i] == '1') chars[i] = 'I';
        }
        return new String(chars);
    }

    boolean verifyCheckDigit(String data, String checkDigitStr) {
        try {
            int expected = Integer.parseInt(checkDigitStr);
            int sum = 0;
            for (int i = 0; i < data.length(); i++) {
                char c = data.charAt(i);
                int value;
                if (c == '<') value = 0;
                else if (c >= '0' && c <= '9') value = c - '0';
                else if (c >= 'A' && c <= 'Z') value = c - 'A' + 10;
                else return false;
                sum += value * CHECK_WEIGHTS[i % 3];
            }
            return (sum % 10) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String[] pickBest(java.util.List<String> lines, int count) {
        // When there are duplicate MRZ readings (OCR often reads same zone twice),
        // group by first 5 chars prefix and keep the one with more '<' separators
        // (better fidelity to real MRZ structure)
        java.util.Map<String, String> bestByPrefix = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            String prefix = line.length() >= 5 ? line.substring(0, 5) : line;
            String existing = bestByPrefix.get(prefix);
            if (existing == null || countChar(line, '<') > countChar(existing, '<')
                    || (countChar(line, '<') == countChar(existing, '<') && line.length() > existing.length())) {
                bestByPrefix.put(prefix, line);
            }
        }
        java.util.List<String> best = new java.util.ArrayList<>(bestByPrefix.values());
        if (best.size() >= count) return best.subList(0, count).toArray(new String[0]);
        // Fallback: use original list
        if (lines.size() >= count) return lines.subList(0, count).toArray(new String[0]);
        return null;
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }

    private String padOrTrim(String s, int length) {
        if (s.length() >= length) return s.substring(0, length);
        return s + "<".repeat(length - s.length());
    }
}
