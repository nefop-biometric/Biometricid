package com.eduin.onboarding.authenticity.service.analyzer;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Detecta si una imagen es una foto de otra foto/pantalla/impresión.
 *
 * Técnicas:
 * 1. FFT          → patrones Moiré (frecuencias periódicas de pantalla/impresora)
 * 2. Píxeles RGB  → subpíxeles de pantalla (grid R-G-B característico de LCD/OLED)
 * 3. Fondo        → contenido externo en los márgenes (mano, libreta, escritorio)
 * 4. Laplacian    → nitidez artificial de pantalla vs. documento físico real
 * 5. Perspectiva  → distorsión angular al fotografiar
 * 6. Reflejo      → sobreexposición / flash sobre plástico o pantalla
 */
@Component
public class RecaptureDetector {

    public AnalysisDetail analyze(Mat image) {
        List<String> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        double moireScore      = analyzeMoirePattern(image, findings);
        double screenScore     = analyzeScreenPixels(image, findings);
        double backgroundScore = analyzeBackground(image, findings, warnings);
        double sharpnessScore  = analyzeSharpnessProfile(image, findings);
        double[] perspResult   = analyzePerspectiveDistortionEx(image, findings);
        double perspectiveScore= perspResult[0];
        long   diagonalCount   = (long) perspResult[1];
        double glareScore      = analyzeGlare(image, findings);
        double backlightScore  = analyzeBacklightUniformity(image, findings);
        double flatTextureScore= analyzeFlatAreaTexture(image, findings);
        double colorTempScore  = analyzeColorTemperature(image, findings);

        double minCritical = Math.min(moireScore, screenScore);

        double score = moireScore       * 0.13
                     + screenScore      * 0.15
                     + flatTextureScore * 0.18   // señal más fiable para monitores 4K
                     + backlightScore   * 0.12   // retroiluminación uniforme = monitor
                     + colorTempScore   * 0.12   // sesgo azul de pantalla vs. papel cálido
                     + backgroundScore  * 0.12
                     + sharpnessScore   * 0.08
                     + perspectiveScore * 0.06
                     + glareScore       * 0.04;

        // Textura plana sin grano + retroiluminación uniforme → monitor limpio sin manos
        if (flatTextureScore < 0.70 && backlightScore < 0.70) {
            findings.add("Superficie sin textura de papel/plástico + iluminación uniforme — indica monitor o pantalla.");
            score = Math.min(score, 0.48);
        }
        // Temperatura azul de pantalla + textura plana
        if (colorTempScore < 0.55 && flatTextureScore < 0.80) {
            score = Math.min(score, 0.50);
        }
        // Moiré con patrón de seguridad: requiere MUCHAS diagonales Y otras señales de pantalla
        // (fondos texturizados como cuero/tela también generan diagonales por su grano natural)
        if (diagonalCount > 500 && perspectiveScore < 0.40
                && (flatTextureScore < 0.70 || colorTempScore < 0.70)) {
            findings.add(String.format(
                    "Alta densidad de líneas diagonales (%d) — interferencia Moiré con rejilla LCD.", diagonalCount));
            score = Math.min(score, 0.45);
        }
        // Zonas planas sin textura + alguna señal de pantalla
        if (flatTextureScore < 0.55 && (screenScore < 0.95 || backlightScore < 0.85)) {
            score = Math.min(score, 0.45);
        }
        // Retroiluminación muy uniforme + señales de pantalla
        if (backlightScore < 0.55 && screenScore < 0.92) {
            score = Math.min(score, 0.48);
        }
        // Fondo oscuro (bezel monitor) + señal de pantalla FUERTE (no basta con moderada,
        // ya que fondos texturizados reales — cuero, tela, madera — generan variación natural)
        if (backgroundScore < 0.50 && (colorTempScore < 0.55 || flatTextureScore < 0.60)) {
            score = Math.min(score, 0.52);
        }
        if (backgroundScore < 0.15) {
            score = Math.min(score, 0.50);
        }
        // Pantalla confirmada por múltiples señales fuertes
        if (minCritical < 0.40) {
            score = Math.min(score, 0.40);
        }

        boolean passed = score >= 0.60;

        return AnalysisDetail.builder()
                .analyzer("RECAPTURE_DETECTION")
                .score(round(score))
                .passed(passed)
                .verdict(passed ? "Sin indicios de recaptura"
                        : buildVerdict(moireScore, screenScore, backgroundScore, sharpnessScore,
                                       backlightScore, flatTextureScore, perspectiveScore, colorTempScore))
                .findings(findings)
                .warnings(warnings)
                .build();
    }

    // ── 1. Moiré via FFT ──────────────────────────────────────────────────────
    // Una foto de pantalla genera picos periódicos muy marcados en el espectro
    // de frecuencias por la rejilla de subpíxeles del display.

    private double analyzeMoirePattern(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        int optRows = getOptimalDFTSize(gray.rows());
        int optCols = getOptimalDFTSize(gray.cols());

        Mat padded = new Mat();
        copyMakeBorder(gray, padded, 0, optRows - gray.rows(), 0, optCols - gray.cols(),
                BORDER_CONSTANT, Scalar.all(0));
        padded.convertTo(padded, CV_32F);

        Mat zeros   = Mat.zeros(padded.size(), CV_32F).asMat();
        Mat complex = new Mat();
        merge(new MatVector(padded, zeros), complex);
        dft(complex, complex);

        MatVector planes = new MatVector(2);
        split(complex, planes);
        Mat mag = new Mat();
        magnitude(planes.get(0), planes.get(1), mag);

        Mat logMag = new Mat();
        Mat one    = Mat.ones(mag.size(), CV_32F).asMat();
        add(mag, one, logMag);
        log(logMag, logMag);
        normalize(logMag, logMag, 0, 1, NORM_MINMAX, -1, new Mat());
        shiftDFT(logMag);

        int cx   = logMag.cols() / 2;
        int cy   = logMag.rows() / 2;
        int excl = Math.min(cx, cy) / 5;

        FloatIndexer fi = logMag.createIndexer();
        double totalEnergy = 0, peakEnergy = 0;
        int peakCount = 0;

        for (int r = 0; r < logMag.rows(); r++) {
            for (int c = 0; c < logMag.cols(); c++) {
                float val  = fi.get(r, c);
                double dist = Math.sqrt(Math.pow(r - cy, 2) + Math.pow(c - cx, 2));
                totalEnergy += val;
                // Umbral sensible: 0.75 para capturar patrones de monitores de alta resolución
                if (dist > excl && val > 0.75f) { peakEnergy += val; peakCount++; }
            }
        }

        long totalPxSave = (long) logMag.rows() * logMag.cols() + 1;
        gray.release(); padded.release(); zeros.release(); one.release();
        complex.release(); mag.release(); logMag.release();

        double peakRatio = totalEnergy > 0 ? peakEnergy / totalEnergy : 0;
        long   totalPx   = totalPxSave;
        double normPeaks = (double) peakCount / totalPx;

        if (normPeaks > 0.0015 || peakRatio > 0.08) {
            findings.add(String.format(
                    "Patrón Moiré detectado: %.1f%% energía en frecuencias periódicas (%d picos). Indica foto de pantalla o impresión.",
                    peakRatio * 100, peakCount));
            return Math.max(0.0, 0.4 - peakRatio * 2);
        }
        return 1.0;
    }

    // ── 2. Detección de subpíxeles de pantalla ────────────────────────────────
    // Las pantallas LCD/OLED tienen una rejilla R-G-B que genera:
    //  a) Alta correlación entre canales en zonas uniformes
    //  b) Varianza de canal específica característica (R≠G≠B en cada píxel)
    //  c) Gradiente de color muy regular (banding)

    private double analyzeScreenPixels(Mat image, List<String> findings) {
        // split con MatVector de tamaño fijo — forma correcta en bytedeco
        MatVector mv = new MatVector(3);
        split(image, mv);
        Mat chB = mv.get(0);
        Mat chG = mv.get(1);
        Mat chR = mv.get(2);

        if (chB.empty() || chG.empty() || chR.empty()) {
            mv.close();
            return 0.85; // no se pudo analizar, asumir sin problema
        }

        // Máscara de zonas NO sobreexpuestas: el reflejo de flash sobre plástico/papel
        // satura los canales (clipping a 255) y simula artificialmente "uniformidad RGB"
        // y "textura plana" — hay que excluir esas zonas del análisis para evitar falsos positivos.
        Mat grayForMask = new Mat();
        cvtColor(image, grayForMask, COLOR_BGR2GRAY);
        Mat validMask = new Mat();
        threshold(grayForMask, validMask, 235, 255, THRESH_BINARY_INV);
        int validPx = countNonZero(validMask);
        int totalPxImg = image.rows() * image.cols();
        grayForMask.release();

        // Si hay demasiado glare (>40% sobreexpuesto), no se puede analizar con confianza
        boolean tooMuchGlare = validPx < totalPxImg * 0.60;

        // a) Diferencia inter-canal (excluyendo zonas de glare)
        Mat diffBG = new Mat(), diffGR = new Mat();
        absdiff(chB, chG, diffBG);
        absdiff(chG, chR, diffGR);

        Mat meanBG = new Mat(), stdBG = new Mat();
        Mat meanGR = new Mat(), stdGR = new Mat();
        meanStdDev(diffBG, meanBG, stdBG, validMask);
        meanStdDev(diffGR, meanGR, stdGR, validMask);

        double stdB = readDouble(stdBG, 0);
        double stdG = readDouble(stdGR, 0);

        // b) Laplacian bajo = textura plana de pantalla (excluyendo glare)
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        Mat laplacian = new Mat();
        Laplacian(gray, laplacian, CV_32F);
        Mat meanL = new Mat(), stdL = new Mat();
        meanStdDev(laplacian, meanL, stdL, validMask);
        double laplacianStd = readDouble(stdL, 0);

        // c) Banding: blur horizontal con kernel de ancho impar más cercano al ancho de imagen
        int blurW = image.cols() % 2 == 0 ? image.cols() - 1 : image.cols();
        blurW = Math.max(1, blurW);
        Mat blurH = new Mat();
        blur(gray, blurH, new Size(blurW, 1));
        Mat diffBanding = new Mat();
        absdiff(gray, blurH, diffBanding);
        Mat meanDB = new Mat(), stdDB = new Mat();
        meanStdDev(diffBanding, meanDB, stdDB, validMask);
        double bandingStd = readDouble(stdDB, 0);

        mv.close();
        validMask.release();
        diffBG.release(); diffGR.release();
        meanBG.release(); stdBG.release();
        meanGR.release(); stdGR.release();
        gray.release(); laplacian.release();
        meanL.release(); stdL.release();
        blurH.release(); diffBanding.release();
        meanDB.release(); stdDB.release();

        if (tooMuchGlare) return 0.85; // demasiado reflejo para análisis confiable

        double score = 1.0;
        List<String> issues = new ArrayList<>();

        // Umbrales recalibrados con casos reales:
        // - CC Antigua (amarilla, papel):     stdB~6.7, stdG~4.6, Laplacian~18.8
        // - Cédula plástica laminada c/flash: stdB~6.2, stdG~6.8, Laplacian~12.6  ← documentos reales
        //   pueden tener Laplacian bajo por laminado brillante; NO debe penalizar solo
        // - Pantalla LCD/OLED real: stdB/stdG < 3, Laplacian < 7 (mucho más extremo)
        // - Requiere señales MUY bajas, no solo "moderadamente bajas"
        boolean lowChannelVariance = stdB < 4 && stdG < 3;    // canales casi idénticos (solo pantalla real)
        boolean flatLaplacian      = laplacianStd < 8;         // textura prácticamente nula (solo pantalla)
        boolean hasBanding         = bandingStd < 5;           // banding horizontal fuerte

        int signalCount = (lowChannelVariance ? 1 : 0) + (flatLaplacian ? 1 : 0) + (hasBanding ? 1 : 0);

        if (lowChannelVariance) {
            issues.add(String.format("canales RGB uniformes (stdB=%.1f, stdG=%.1f — característico de pantalla)", stdB, stdG));
            score -= signalCount >= 2 ? 0.30 : 0.08;
        }
        if (flatLaplacian) {
            issues.add(String.format("textura artificial (Laplacian stdDev=%.1f — sin grano de papel/plástico real)", laplacianStd));
            score -= signalCount >= 2 ? 0.35 : 0.10;
        }
        if (hasBanding) {
            issues.add(String.format("banding horizontal (stdDev=%.1f — posibles franjas de pantalla)", bandingStd));
            score -= signalCount >= 2 ? 0.20 : 0.08;
        }

        if (!issues.isEmpty()) {
            findings.add("Indicios de foto de pantalla: " + String.join("; ", issues));
        }

        return Math.max(0.0, score);
    }

    // ── 3. Fondo con contenido ────────────────────────────────────────────────

    private double analyzeBackground(Mat image, List<String> findings, List<String> warnings) {
        Mat gray  = new Mat();
        Mat edges = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        Canny(gray, edges, 50, 150);

        int mW = (int)(image.cols() * 0.08);
        int mH = (int)(image.rows() * 0.08);

        long borderPx = 0, activeEdge = 0;
        for (int r = 0; r < edges.rows(); r++) {
            for (int c = 0; c < edges.cols(); c++) {
                boolean inBorder = r < mH || r >= edges.rows() - mH
                        || c < mW || c >= edges.cols() - mW;
                if (inBorder) {
                    borderPx++;
                    if ((edges.ptr(r, c).get() & 0xFF) > 0) activeEdge++;
                }
            }
        }
        double edgeDensity = borderPx > 0 ? (double) activeEdge / borderPx : 0;

        Mat borderMask = Mat.zeros(image.size(), CV_8U).asMat();
        rectangle(borderMask, new Point(0, 0), new Point(image.cols(), image.rows()),
                Scalar.all(255), -1, LINE_8, 0);
        rectangle(borderMask, new Point(mW, mH),
                new Point(image.cols() - mW, image.rows() - mH),
                Scalar.all(0), -1, LINE_8, 0);

        Mat meanM = new Mat(), stdM = new Mat();
        meanStdDev(gray, meanM, stdM, borderMask);
        double borderStd = readDouble(stdM, 0);

        gray.release(); edges.release(); borderMask.release();
        meanM.release(); stdM.release();

        // Umbral recalibrado: superficies texturizadas reales (cuero, madera, tela, alfombra)
        // generan stdDev de fondo entre 50-90 de forma natural y NO son señal de fraude.
        // Solo marcar cuando el fondo es extremo (mano muy cercana, pantalla de otro dispositivo, etc.)
        boolean complexBg = edgeDensity > 0.20 || borderStd > 95;
        if (complexBg) {
            if (edgeDensity > 0.20)
                findings.add(String.format("Fondo complejo: bordes en márgenes=%.1f%% (mano, mesa u objetos alrededor del documento)", edgeDensity * 100));
            if (borderStd > 95)
                findings.add(String.format("Alta variación en fondo: stdDev=%.1f (documento sobre superficie con contenido)", borderStd));
            return Math.max(0.0, 1.0 - edgeDensity * 2 - (borderStd - 95) / 150.0);
        }
        return 1.0;
    }

    // ── 4. Perfil de nitidez ──────────────────────────────────────────────────
    // Un documento físico fotografiado tiene textura de papel/plástico.
    // Una pantalla fotografiada es artificialmente plana o tiene sobre-nitidez digital.

    private double analyzeSharpnessProfile(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        // Gradiente local en bloques pequeños
        int bs = 32;
        List<Double> localGrads = new ArrayList<>();
        for (int r = 0; r + bs < gray.rows(); r += bs) {
            for (int c = 0; c + bs < gray.cols(); c += bs) {
                Mat block = gray.apply(new Rect(c, r, bs, bs));
                Mat gx = new Mat(), gy = new Mat(), gm = new Mat();
                Sobel(block, gx, CV_32F, 1, 0);
                Sobel(block, gy, CV_32F, 0, 1);
                magnitude(gx, gy, gm);
                Mat mM = new Mat(), mS = new Mat();
                meanStdDev(gm, mM, mS);
                localGrads.add(readDouble(mM, 0));
                block.release(); gx.release(); gy.release();
                gm.release(); mM.release(); mS.release();
            }
        }
        gray.release();

        if (localGrads.isEmpty()) return 0.85;

        double avg = localGrads.stream().mapToDouble(d -> d).average().orElse(0);
        double max = localGrads.stream().mapToDouble(d -> d).max().orElse(0);
        double min = localGrads.stream().mapToDouble(d -> d).min().orElse(0);

        // En una pantalla el gradiente es muy uniforme (sin zonas de mayor o menor nitidez)
        double uniformity = avg > 0 ? (max - min) / avg : 0;

        if (uniformity < 1.5 && avg < 25) {
            findings.add(String.format(
                    "Nitidez artificialmente uniforme (rango=%.1fx, avg=%.1f) — perfil de pantalla, no de documento físico.",
                    uniformity, avg));
            return Math.max(0.3, uniformity / 1.5);
        }
        return 1.0;
    }

    // ── 5. Distorsión de perspectiva ─────────────────────────────────────────
    // Devuelve [score, diagonalCount] para que el caller pueda usar el conteo
    // directamente como señal de Moiré (muchas diagonales = pantalla con patrón de seguridad).

    private double[] analyzePerspectiveDistortionEx(Mat image, List<String> findings) {
        Mat gray  = new Mat();
        Mat edges = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        // Umbral Canny más alto y longitud mínima de línea mayor: filtra el grano de
        // fondos texturizados (cuero, tela, madera) que generan miles de bordes cortos
        // y no deben confundirse con líneas estructurales de pantalla/documento.
        Canny(gray, edges, 60, 160);

        Vec4iVector lines = new Vec4iVector();
        HoughLinesP(edges, lines, 1, Math.PI / 180, 100, 180, 15);
        gray.release(); edges.release();

        if (lines.empty()) { lines.close(); return new double[]{0.85, 0}; }

        long h = 0, v = 0, d = 0;
        for (long i = 0; i < lines.size(); i++) {
            int x1 = lines.get(i).get(0), y1 = lines.get(i).get(1);
            int x2 = lines.get(i).get(2), y2 = lines.get(i).get(3);
            double angle = Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
            if (Math.abs(angle) < 10 || Math.abs(angle) > 170) h++;
            else if (Math.abs(Math.abs(angle) - 90) < 10)       v++;
            else                                                  d++;
        }
        lines.close();

        long total = h + v + d;
        double align = total > 0 ? (double)(h + v) / total : 1.0;
        if (align < 0.60) {
            findings.add(String.format(
                    "Distorsión de perspectiva: %.0f%% líneas alineadas (%d diagonales)", align * 100, d));
            return new double[]{align, d};
        }
        return new double[]{1.0, d};
    }

    // ── 6. Reflejo / sobreexposición ─────────────────────────────────────────

    private double analyzeGlare(Mat image, List<String> findings) {
        Mat hsv = new Mat();
        cvtColor(image, hsv, COLOR_BGR2HSV);
        MatVector ch = new MatVector(3);
        split(hsv, ch);
        Mat val    = ch.get(2);
        Mat overex = new Mat();
        threshold(val, overex, 250, 255, THRESH_BINARY);
        double ratio = (double) countNonZero(overex) / (image.rows() * image.cols());
        hsv.release(); val.release(); overex.release();
        for (int i = 0; i < ch.size(); i++) ch.get(i).release();

        if (ratio > 0.08) {
            findings.add(String.format("Reflejo/sobreexposición: %.1f%% de píxeles sobreexpuestos", ratio * 100));
            return Math.max(0.3, 1.0 - ratio * 4);
        }
        return 1.0;
    }

    // ── 7. Textura en zonas planas ────────────────────────────────────────────
    // El papel real siempre tiene micrograno (fibras, rugosidad) → std > 4 en áreas blancas.
    // Una pantalla LCD muestra píxeles perfectamente lisos → std < 2.5 en áreas blancas.
    // Esta es la señal más confiable para monitores 4K donde no hay Moiré visible.

    private double analyzeFlatAreaTexture(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        // Blur suave (5x5) para eliminar artefactos JPEG de alta frecuencia.
        // El grano de papel (escala media) sobrevive el blur; los píxeles LCD perfectos desaparecen.
        Mat blurred = new Mat();
        GaussianBlur(gray, blurred, new Size(5, 5), 0);
        gray.release();

        int bs = 24;
        List<Double> flatStds = new ArrayList<>();

        for (int y = 0; y + bs < blurred.rows(); y += bs) {
            for (int x = 0; x + bs < blurred.cols(); x += bs) {
                Mat block = blurred.apply(new Rect(x, y, bs, bs));
                Mat mM = new Mat(), sM = new Mat();
                meanStdDev(block, mM, sM);
                double mean = readDouble(mM, 0);
                double std  = readDouble(sM, 0);
                // Solo bloques brillantes y planos (fondo del documento, no texto)
                if (mean > 155 && std < 30) {
                    flatStds.add(std);
                }
                block.release(); mM.release(); sM.release();
            }
        }
        blurred.release();

        if (flatStds.size() < 6) return 1.0;

        double avgTexture = flatStds.stream().mapToDouble(d -> d).average().orElse(0);

        // Umbrales calibrados con imágenes reales:
        //   Papel/plástico físico: std residual tras blur 5x5 → 2.5–8.0
        //   Monitor LCD/OLED:      std residual → 0.3–1.8
        //   Fotocopia láser:       std residual → 1.0–2.5
        if (avgTexture < 2.5) {
            findings.add(String.format(
                    "Zonas planas sin textura de papel/plástico (std=%.2f tras denoising) — " +
                    "superficie digital (monitor) o impresión, no documento físico original.",
                    avgTexture));
            return Math.max(0.05, avgTexture / 2.5);
        }
        return 1.0;
    }

    // ── 9. Temperatura de color ───────────────────────────────────────────────
    // Los monitores LCD/OLED tienen temperatura de color fría (6000–6500 K):
    //   las zonas blancas tienen el canal Azul dominante (B > R).
    // Los documentos físicos bajo iluminación natural o fluorescente:
    //   temperatura cálida (3000–5000 K): canal Rojo dominante (R ≥ B).
    // La diferencia (B - R) en píxeles blancos es un discriminador fuerte.

    private double analyzeColorTemperature(Mat image, List<String> findings) {
        MatVector mv = new MatVector(3);
        split(image, mv);
        Mat chB = mv.get(0), chG = mv.get(1), chR = mv.get(2);

        if (chB.empty() || chR.empty()) { mv.close(); return 1.0; }

        // Máscara de píxeles brillantes (zonas "blancas" del documento)
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        Mat brightMask = new Mat();
        threshold(gray, brightMask, 180, 255, THRESH_BINARY);
        int brightCount = countNonZero(brightMask);

        if (brightCount < 500) {
            gray.release(); brightMask.release(); mv.close();
            return 1.0; // muy pocos píxeles brillantes, no se puede analizar
        }

        // Media de B y R solo en píxeles brillantes
        Mat mB = new Mat(), sB = new Mat(), mR = new Mat(), sR = new Mat();
        meanStdDev(chB, mB, sB, brightMask);
        meanStdDev(chR, mR, sR, brightMask);
        double meanB = readDouble(mB, 0);
        double meanR = readDouble(mR, 0);

        gray.release(); brightMask.release(); mv.close();
        mB.release(); sB.release(); mR.release(); sR.release();

        // B - R en zonas blancas:
        //   Monitor fría:    B - R >  +8  (azul dominante)
        //   Papel cálido:    B - R < -5   (rojo dominante)
        //   Luz neutra:      B - R ≈ 0 ± 5
        double blueRedDiff = meanB - meanR;

        if (blueRedDiff > 8.0) {
            findings.add(String.format(
                    "Temperatura de color fría (B-R=+%.1f en zonas blancas) — " +
                    "sesgo azul típico de monitor LCD/OLED (6000-6500K), no de documento físico bajo luz cálida.",
                    blueRedDiff));
            return Math.max(0.10, 1.0 - (blueRedDiff - 8.0) / 30.0);
        }
        return 1.0;
    }

    // ── 8. Uniformidad de retroiluminación ───────────────────────────────────
    // Un monitor tiene retroiluminación perfectamente plana: la luminancia
    // de la imagen es muy uniforme entre bloques (CV < 0.10).
    // Una foto real tiene sombras de mano, viñeteado de lente y luz ambiente
    // → variación natural entre bloques (CV 0.15–0.50).

    private double analyzeBacklightUniformity(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        int gridN = 4;
        int bW = Math.max(1, gray.cols() / gridN);
        int bH = Math.max(1, gray.rows() / gridN);

        List<Double> blockMeans = new ArrayList<>();
        for (int gy = 0; gy < gridN; gy++) {
            for (int gx = 0; gx < gridN; gx++) {
                int x = gx * bW, y = gy * bH;
                int w = Math.min(bW, gray.cols() - x);
                int h = Math.min(bH, gray.rows() - y);
                if (w < 4 || h < 4) continue;
                Mat block = gray.apply(new Rect(x, y, w, h));
                Mat mM = new Mat(), sM = new Mat();
                meanStdDev(block, mM, sM);
                blockMeans.add(readDouble(mM, 0));
                block.release(); mM.release(); sM.release();
            }
        }
        gray.release();
        if (blockMeans.size() < 4) return 1.0;

        double avg = blockMeans.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = blockMeans.stream().mapToDouble(d -> Math.pow(d - avg, 2)).average().orElse(0);
        double cv = avg > 10 ? Math.sqrt(variance) / avg : 1.0;

        // Analizar solo la zona central (excluir posible bezel oscuro del monitor)
        // que podría bajar artificialmente el promedio
        int cx = (int)(gray.cols() * 0.15), cy2 = (int)(gray.rows() * 0.15);
        int cw = gray.cols() - 2*cx, ch = gray.rows() - 2*cy2;
        if (cw > 10 && ch > 10) {
            Mat center = gray.apply(new Rect(cx, cy2, cw, ch));
            int gcN2 = Math.max(2, gridN);
            int bW2 = center.cols() / gcN2, bH2 = center.rows() / gcN2;
            List<Double> centerMeans = new ArrayList<>();
            for (int gy = 0; gy < gcN2; gy++) {
                for (int gx2 = 0; gx2 < gcN2; gx2++) {
                    int x = gx2 * bW2, y = gy * bH2;
                    int w = Math.min(bW2, center.cols() - x);
                    int h2 = Math.min(bH2, center.rows() - y);
                    if (w < 4 || h2 < 4) continue;
                    Mat b2 = center.apply(new Rect(x, y, w, h2));
                    Mat m2 = new Mat(), s2 = new Mat();
                    meanStdDev(b2, m2, s2);
                    centerMeans.add(readDouble(m2, 0));
                    b2.release(); m2.release(); s2.release();
                }
            }
            center.release();
            if (centerMeans.size() >= 4) {
                double cavg = centerMeans.stream().mapToDouble(d -> d).average().orElse(0);
                double cvar = centerMeans.stream().mapToDouble(d -> Math.pow(d - cavg, 2)).average().orElse(0);
                double ccv  = cavg > 10 ? Math.sqrt(cvar) / cavg : 1.0;
                // Monitor: zona central muy uniforme independientemente del brillo total
                if (ccv < 0.08 && cavg > 60) {
                    findings.add(String.format(
                            "Zona central con luminancia uniformemente plana (CV=%.3f, media=%.0f) — " +
                            "retroiluminación de pantalla, no iluminación natural.",
                            ccv, cavg));
                    gray.release();
                    return Math.max(0.15, ccv / 0.08);
                }
            }
        }
        gray.release();

        // Verificación global también
        if (cv < 0.07 && avg > 50) {
            findings.add(String.format(
                    "Luminancia global muy uniforme (CV=%.3f) — retroiluminación de pantalla.",
                    cv));
            return Math.max(0.20, cv / 0.07);
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

    private String buildVerdict(double m, double s, double b, double sh, double bl, double ft, double p, double ct) {
        List<String> issues = new ArrayList<>();
        if (ft < 0.6) issues.add("sin textura de papel/plástico (pantalla o impresión)");
        if (ct < 0.6) issues.add("temperatura de color fría (sesgo azul de monitor)");
        if (bl < 0.6) issues.add("retroiluminación uniforme (monitor)");
        if (s  < 0.6) issues.add("subpíxeles/banding de pantalla");
        if (m  < 0.6) issues.add("patrón Moiré");
        if (b  < 0.6) issues.add("fondo con contenido externo");
        if (sh < 0.6) issues.add("nitidez artificial");
        if (p  < 0.4) issues.add("interferencia Moiré con rejilla LCD");
        return "Foto de monitor/pantalla/impresión: " + String.join(", ", issues);
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
