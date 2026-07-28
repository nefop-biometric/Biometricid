package com.eduin.onboarding.authenticity.service.analyzer;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Detecta fotocopias (blanco/negro y color) de documentos de identidad.
 *
 * Una fotocopia es tan inválida como un documento digital — el portador
 * no está presentando el documento físico original.
 *
 * Técnicas:
 * 1. Saturación global       → fotocopia B&N: saturación media < 8
 * 2. Trama de impresora FFT  → rejilla de puntos de tóner/inkjet a media frecuencia
 * 3. Grano de tóner          → ruido de alta frecuencia con distribución bimodal (tóner sí/no)
 * 4. Rango dinámico          → fotocopia aplana los tonos medios (histograma bimodal extremo)
 * 5. Textura de papel copia  → las fotocopiadoras añaden grano de rodillo característico
 */
@Component
public class PhotocopyDetector {

    public AnalysisDetail analyze(Mat image) {
        List<String> findings = new ArrayList<>();

        double satScore      = analyzeSaturation(image, findings);
        double haftoneScore  = analyzeHalftonePattern(image, findings);
        double tonerScore    = analyzeTonerGrain(image, findings);
        double dynamicScore  = analyzeDynamicRange(image, findings);

        // Si detecta B&N → saturación ya es señal suficiente, los otros refuerzan
        double score = satScore    * 0.40
                     + haftoneScore * 0.25
                     + tonerScore   * 0.20
                     + dynamicScore * 0.15;

        // Fotocopia B&N confirmada (saturación muy baja) → veto directo
        if (satScore < 0.20) {
            score = Math.min(score, 0.25);
        }
        // Trama de impresora + rango dinámico plano → fotocopia color
        if (haftoneScore < 0.40 && dynamicScore < 0.50) {
            score = Math.min(score, 0.38);
        }

        boolean passed = score >= 0.60;
        return AnalysisDetail.builder()
                .analyzer("PHOTOCOPY_DETECTION")
                .score(round(score))
                .passed(passed)
                .verdict(passed ? "Sin indicios de fotocopia"
                        : buildVerdict(satScore, haftoneScore, tonerScore, dynamicScore))
                .findings(findings)
                .warnings(new ArrayList<>())
                .build();
    }

    // ── 1. Saturación global ──────────────────────────────────────────────────
    // Una fotocopia B&N tiene saturación prácticamente cero en toda la imagen.
    // Una fotocopia color la tiene ligeramente desaturada pero no tanto como B&N.

    private double analyzeSaturation(Mat image, List<String> findings) {
        Mat hsv = new Mat();
        cvtColor(image, hsv, COLOR_BGR2HSV);
        MatVector ch = new MatVector(3);
        split(hsv, ch);
        Mat sat = ch.get(1);

        Mat mM = new Mat(), sM = new Mat();
        meanStdDev(sat, mM, sM);
        double meanSat = readDouble(mM, 0);
        double stdSat  = readDouble(sM, 0);

        // Contar píxeles con saturación > 20 (color real)
        Mat colored = new Mat();
        threshold(sat, colored, 20, 255, THRESH_BINARY);
        double colorRatio = (double) countNonZero(colored) / (image.rows() * image.cols());

        hsv.release(); sat.release(); mM.release(); sM.release(); colored.release();
        for (int i = 0; i < ch.size(); i++) ch.get(i).release();

        if (meanSat < 8 || colorRatio < 0.05) {
            findings.add(String.format(
                    "Fotocopia B&N: saturación media=%.1f, solo %.1f%% de píxeles con color. " +
                    "Documento en escala de grises — no es el documento físico original.",
                    meanSat, colorRatio * 100));
            return Math.max(0.0, meanSat / 8.0);
        }
        if (meanSat < 20 && stdSat < 12) {
            findings.add(String.format(
                    "Posible fotocopia color: saturación baja (media=%.1f, std=%.1f). " +
                    "Los documentos originales tienen colores más vivos.",
                    meanSat, stdSat));
            return Math.max(0.20, meanSat / 30.0);
        }
        return 1.0;
    }

    // ── 2. Trama de impresora via FFT ─────────────────────────────────────────
    // Las impresoras láser e inkjet producen una trama de puntos (halftone) a
    // frecuencias específicas (60–150 lpi típicamente), detectables como picos
    // en la banda de frecuencias medias del espectro FFT.

    private double analyzeHalftonePattern(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        int optR = getOptimalDFTSize(gray.rows());
        int optC = getOptimalDFTSize(gray.cols());
        Mat padded = new Mat();
        copyMakeBorder(gray, padded, 0, optR - gray.rows(), 0, optC - gray.cols(),
                BORDER_CONSTANT, Scalar.all(0));
        padded.convertTo(padded, CV_32F);

        Mat zeros = Mat.zeros(padded.size(), CV_32F).asMat();
        Mat complex = new Mat();
        merge(new MatVector(padded, zeros), complex);
        dft(complex, complex);

        MatVector planes = new MatVector(2);
        split(complex, planes);
        Mat mag = new Mat();
        magnitude(planes.get(0), planes.get(1), mag);

        Mat logMag = new Mat();
        Mat one = Mat.ones(mag.size(), CV_32F).asMat();
        add(mag, one, logMag);
        log(logMag, logMag);
        normalize(logMag, logMag, 0, 1, NORM_MINMAX, -1, new Mat());
        shiftDFT(logMag);

        int cx = logMag.cols() / 2, cy = logMag.rows() / 2;
        // Banda de frecuencias medias: 15–40% del radio máximo
        int rMin = (int)(Math.min(cx, cy) * 0.15);
        int rMax = (int)(Math.min(cx, cy) * 0.40);

        FloatIndexer fi = logMag.createIndexer();
        double bandEnergy = 0, totalEnergy = 0;
        int bandPeaks = 0;

        for (int r = 0; r < logMag.rows(); r++) {
            for (int c = 0; c < logMag.cols(); c++) {
                float val = fi.get(r, c);
                double dist = Math.sqrt(Math.pow(r - cy, 2) + Math.pow(c - cx, 2));
                totalEnergy += val;
                if (dist >= rMin && dist <= rMax) {
                    bandEnergy += val;
                    if (val > 0.78f) bandPeaks++;
                }
            }
        }

        long totalPx = (long) logMag.rows() * logMag.cols() + 1;
        gray.release(); padded.release(); zeros.release(); one.release();
        complex.release(); mag.release(); logMag.release();

        double bandRatio   = totalEnergy > 0 ? bandEnergy / totalEnergy : 0;
        double normBandPks = (double) bandPeaks / totalPx;

        // Trama de impresora: energía concentrada en banda media + picos bien definidos
        if (normBandPks > 0.0018 || bandRatio > 0.28) {
            findings.add(String.format(
                    "Trama de impresora detectada: %.1f%% energía en frecuencias medias (%d picos). " +
                    "Indica documento impreso/fotocopiado.",
                    bandRatio * 100, bandPeaks));
            return Math.max(0.0, 0.35 - normBandPks * 50);
        }
        return 1.0;
    }

    // ── 3. Grano de tóner ────────────────────────────────────────────────────
    // El tóner de las fotocopiadoras láser produce un grano muy específico:
    // - Alta frecuencia espacial en zonas de texto (puntos de tóner fundidos)
    // - Distribución bimodal de píxeles: negro (tóner) o blanco (papel)
    // - Contraste local extremo en bordes de caracteres

    private double analyzeTonerGrain(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        // Umbralización local: si la imagen es una fotocopia, muchos bloques
        // tendrán distribución bimodal (solo píxeles muy oscuros o muy claros)
        int bs = 32;
        int bimodalBlocks = 0, totalBlocks = 0;

        for (int y = 0; y + bs < gray.rows(); y += bs) {
            for (int x = 0; x + bs < gray.cols(); x += bs) {
                Mat block = gray.apply(new Rect(x, y, bs, bs));
                Mat mM = new Mat(), sM = new Mat();
                meanStdDev(block, mM, sM);
                double mean = readDouble(mM, 0);
                double std  = readDouble(sM, 0);

                // Bloques de texto: media intermedia, std alta (blanco+negro mezclados)
                if (mean > 40 && mean < 220 && std > 50) {
                    // Contar píxeles extremos (tóner: <30 o papel: >225)
                    Mat veryDark = new Mat(), veryLight = new Mat();
                    threshold(block, veryDark,  30, 255, THRESH_BINARY_INV);
                    threshold(block, veryLight, 225, 255, THRESH_BINARY);
                    int dark  = countNonZero(veryDark);
                    int light = countNonZero(veryLight);
                    int total = bs * bs;
                    // Bimodal: más del 70% de píxeles son extremos
                    if ((double)(dark + light) / total > 0.70) bimodalBlocks++;
                    totalBlocks++;
                    veryDark.release(); veryLight.release();
                }
                block.release(); mM.release(); sM.release();
            }
        }
        gray.release();

        if (totalBlocks < 5) return 1.0;

        double bimodalRatio = (double) bimodalBlocks / totalBlocks;
        if (bimodalRatio > 0.50) {
            findings.add(String.format(
                    "Grano de tóner/tinta: %.0f%% de bloques de texto con distribución bimodal " +
                    "(píxeles extremos negro/blanco típicos de fotocopia o impresión).",
                    bimodalRatio * 100));
            return Math.max(0.10, 1.0 - bimodalRatio);
        }
        return 1.0;
    }

    // ── 4. Rango dinámico ────────────────────────────────────────────────────
    // Los documentos originales tienen gradientes suaves (plastificado, hologramas).
    // Las fotocopias aplanan los tonos medios → histograma polarizado a extremos.

    private double analyzeDynamicRange(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        // Calcular histograma de 256 bins
        int[] hist = new int[256];
        for (int r = 0; r < gray.rows(); r++) {
            for (int c = 0; c < gray.cols(); c++) {
                hist[gray.ptr(r, c).get() & 0xFF]++;
            }
        }
        gray.release();

        int totalPx = image.rows() * image.cols();

        // Píxeles en extremos (fotocopia polariza tonos)
        int darkPx  = 0, lightPx = 0, midPx = 0;
        for (int i = 0;   i < 50;  i++) darkPx  += hist[i];
        for (int i = 206; i < 256; i++) lightPx += hist[i];
        for (int i = 80;  i < 176; i++) midPx   += hist[i];

        double darkRatio  = (double) darkPx  / totalPx;
        double lightRatio = (double) lightPx / totalPx;
        double midRatio   = (double) midPx   / totalPx;
        double extremeRatio = darkRatio + lightRatio;

        // En fotocopia: >75% en extremos y <10% en tonos medios
        if (extremeRatio > 0.75 && midRatio < 0.10) {
            findings.add(String.format(
                    "Rango dinámico colapsado: %.0f%% píxeles en extremos (negro/blanco), " +
                    "solo %.0f%% tonos medios — perfil típico de fotocopia.",
                    extremeRatio * 100, midRatio * 100));
            return Math.max(0.10, 1.0 - extremeRatio);
        }
        return 1.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void shiftDFT(Mat src) {
        int cx = src.cols() / 2, cy = src.rows() / 2;
        Mat q0 = src.apply(new Rect(0,  0,  cx, cy));
        Mat q1 = src.apply(new Rect(cx, 0,  cx, cy));
        Mat q2 = src.apply(new Rect(0,  cy, cx, cy));
        Mat q3 = src.apply(new Rect(cx, cy, cx, cy));
        Mat tmp = new Mat();
        q0.copyTo(tmp); q3.copyTo(q0); tmp.copyTo(q3);
        q1.copyTo(tmp); q2.copyTo(q1); tmp.copyTo(q2);
        tmp.release();
    }

    private double readDouble(Mat mat, int channel) {
        try (DoubleIndexer idx = mat.createIndexer()) {
            return idx.get((long) channel);
        } catch (Exception e) { return 0; }
    }

    private String buildVerdict(double sat, double half, double toner, double dyn) {
        List<String> issues = new ArrayList<>();
        if (sat   < 0.40) issues.add(sat < 0.20 ? "fotocopia B&N (sin color)" : "fotocopia color (saturación baja)");
        if (half  < 0.60) issues.add("trama de impresora detectada");
        if (toner < 0.60) issues.add("grano de tóner/tinta");
        if (dyn   < 0.60) issues.add("rango dinámico colapsado");
        return "Fotocopia detectada: " + (issues.isEmpty() ? "múltiples indicios" : String.join(", ", issues));
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
