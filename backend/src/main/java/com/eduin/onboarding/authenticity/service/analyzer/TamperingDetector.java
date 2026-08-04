package com.eduin.onboarding.authenticity.service.analyzer;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Detecta manipulación física del documento:
 * 1. ELA (Error Level Analysis)  — inconsistencias JPEG por edición/inserción
 * 2. Zona de foto                 — análisis específico del área de la fotografía del titular
 *    a. Discontinuidad de borde   — corte abrupto de ruido/color en el límite de la foto
 *    b. Doble compresión JPEG     — foto pegada que ya venía comprimida (artefactos distintos)
 *    c. Inconsistencia de color   — diferente temperatura/saturación entre foto y documento
 * 3. Ruido por bloques            — material insertado tiene diferente nivel de ruido
 * 4. Iluminación                  — gradiente de luz inconsistente revela collage
 * 5. Clones                       — copy-paste de regiones dentro del documento
 */
@Component
public class TamperingDetector {

    private static final double ELA_QUALITY = 75.0;
    private static final double ELA_AMPLIFY = 15.0;

    // Zona esperada de la foto del titular, expresada como fracción [x, y, w, h] del documento.
    // IMPORTANTE: estas fracciones son relativas al DOCUMENTO recortado (DocumentCropService),
    // no al frame completo de la cámara.
    // Cédulas/DNI tipo ID-1 (CC NEW, DNI, TI, CE, PPT): foto en cuadrante derecho.
    // Cédula colombiana ANTIGUA (amarilla): foto a la derecha del centro, con margen
    // hasta el borde derecho (columna de marca de agua).
    // Pasaporte TD3 (COL_PA): foto en cuadrante izquierdo, página de datos biográficos.
    private static final double[] PHOTO_ZONE_CARD     = { 0.62, 0.08, 0.32, 0.75 };
    private static final double[] PHOTO_ZONE_CC_OLD   = { 0.48, 0.12, 0.32, 0.80 };
    private static final double[] PHOTO_ZONE_PASSPORT = { 0.06, 0.12, 0.32, 0.65 };

    // Detector de rostro Haar: ubica la foto REAL del titular (la posición impresa
    // varía entre emisiones del mismo tipo de documento — una zona fija falla).
    private volatile CascadeClassifier faceCascade;
    private volatile boolean cascadeInitTried = false;

    private CascadeClassifier faceCascade() {
        if (!cascadeInitTried) {
            synchronized (this) {
                if (!cascadeInitTried) {
                    try (InputStream in = getClass().getResourceAsStream(
                            "/cascades/haarcascade_frontalface_default.xml")) {
                        if (in != null) {
                            Path tmp = Files.createTempFile("haar_face", ".xml");
                            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                            CascadeClassifier c = new CascadeClassifier(tmp.toString());
                            if (!c.empty()) faceCascade = c;
                        }
                    } catch (Exception ignored) { }
                    cascadeInitTried = true;
                }
            }
        }
        return faceCascade;
    }

    /** Rostro más grande del documento, o null si no se detecta. */
    private Rect detectLargestFace(Mat image) {
        CascadeClassifier cascade = faceCascade();
        if (cascade == null) return null;
        Mat gray = new Mat();
        RectVector faces = new RectVector();
        try {
            cvtColor(image, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);
            int minDim = Math.min(image.cols(), image.rows());
            cascade.detectMultiScale(gray, faces, 1.1, 5, 0,
                    new Size(minDim / 10, minDim / 10), new Size());
            Rect best = null;
            long bestArea = 0;
            for (long i = 0; i < faces.size(); i++) {
                Rect f = faces.get(i);
                long area = (long) f.width() * f.height();
                if (area > bestArea) { bestArea = area; best = new Rect(f); }
            }
            return best;
        } finally {
            gray.release();
            faces.close();
        }
    }

    public AnalysisDetail analyze(Mat image, byte[] rawBytes) {
        return analyze(image, rawBytes, null);
    }

    public AnalysisDetail analyze(Mat image, byte[] rawBytes, com.eduin.onboarding.authenticity.model.DocumentType docType) {
        List<String> findings  = new ArrayList<>();
        List<String> warnings  = new ArrayList<>();

        boolean isPassport = docType != null && docType.isPassport();
        double[] photoZone = isPassport ? PHOTO_ZONE_PASSPORT
                : (docType == com.eduin.onboarding.authenticity.model.DocumentType.COL_CC_OLD ? PHOTO_ZONE_CC_OLD : PHOTO_ZONE_CARD);

        double elaScore      = performELA(rawBytes, findings);
        double multiElaScore = performMultiLevelELA(rawBytes, findings);
        double photoScore    = analyzePhotoZone(image, rawBytes, photoZone, docType, findings);
        double noiseScore    = analyzeNoiseConsistency(image, findings);
        double illumScore    = analyzeIlluminationConsistency(image, findings);
        double cloneScore    = detectCloneRegions(image, findings);
        double splicingScore = detectSplicingBoundaries(image, findings);

        // Pasaportes: la zona fotográfica tiene bordes/marcos que generan
        // falsos positivos en ELA y photo analysis — reducir su peso.
        double wEla, wMultiEla, wPhoto, wNoise, wIllum, wClone, wSplicing;
        if (isPassport) {
            wEla = 0.20; wMultiEla = 0.15; wPhoto = 0.15;
            wNoise = 0.20; wIllum = 0.15; wClone = 0.05; wSplicing = 0.10;
        } else {
            wEla = 0.20; wMultiEla = 0.20; wPhoto = 0.25;
            wNoise = 0.15; wIllum = 0.10; wClone = 0.05; wSplicing = 0.05;
        }

        double minCritical = Math.min(Math.min(elaScore, multiElaScore), photoScore);
        double score = elaScore      * wEla
                     + multiElaScore * wMultiEla
                     + photoScore    * wPhoto
                     + noiseScore    * wNoise
                     + illumScore    * wIllum
                     + cloneScore    * wClone
                     + splicingScore * wSplicing;

        double criticalFloor = isPassport ? 0.30 : 0.40;
        if (minCritical < criticalFloor) {
            score = Math.min(score, isPassport ? 0.50 : 0.40);
        }
        if (splicingScore < 0.50 && elaScore < 0.60) {
            score = Math.min(score, isPassport ? 0.45 : 0.38);
        }
        // Zona de foto comprometida (foto pegada/reemplazada) en documentos de
        // identidad: el análisis de foto ES el indicador de montaje — no se
        // promedia con el resto. No aplica a pasaportes (zona propensa a falsos
        // positivos por diseño complejo de la página).
        if (!isPassport && photoScore < 0.60) {
            score = Math.min(score, photoScore + 0.10);
        }

        boolean passed = score >= 0.60;
        String verdict = passed
                ? "Sin indicios de manipulación física"
                : buildVerdict(elaScore, photoScore, noiseScore);

        return AnalysisDetail.builder()
                .analyzer("TAMPERING_DETECTION")
                .score(round(score))
                .passed(passed)
                .verdict(verdict)
                .findings(findings)
                .warnings(warnings)
                .build();
    }

    // ── 1. ELA global ────────────────────────────────────────────────────────

    private double performELA(byte[] rawBytes, List<String> findings) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) return 0.75;

            BufferedImage recomp = recompressJpeg(original, (float)(ELA_QUALITY / 100.0));
            if (recomp == null) return 0.75;

            int w = Math.min(original.getWidth(),  recomp.getWidth());
            int h = Math.min(original.getHeight(), recomp.getHeight());
            int rW = Math.max(1, w / 8), rH = Math.max(1, h / 8);

            double[] regionDiffs = new double[64];
            int idx = 0;
            for (int ry = 0; ry < 8; ry++) {
                for (int rx = 0; rx < 8; rx++) {
                    double sum = 0; int cnt = 0;
                    for (int y = ry * rH; y < (ry + 1) * rH && y < h; y++) {
                        for (int x = rx * rW; x < (rx + 1) * rW && x < w; x++) {
                            int o  = original.getRGB(x, y);
                            int r2 = recomp.getRGB(x, y);
                            int dr = ((o >> 16) & 0xFF) - ((r2 >> 16) & 0xFF);
                            int dg = ((o >>  8) & 0xFF) - ((r2 >>  8) & 0xFF);
                            int db = ( o        & 0xFF) - ( r2        & 0xFF);
                            sum += Math.sqrt(dr*dr + dg*dg + db*db) * ELA_AMPLIFY;
                            cnt++;
                        }
                    }
                    regionDiffs[idx++] = cnt > 0 ? sum / cnt : 0;
                }
            }

            double avg = 0, max = 0;
            for (double d : regionDiffs) { avg += d; max = Math.max(max, d); }
            avg /= 64;
            double variance = 0;
            for (double d : regionDiffs) variance += Math.pow(d - avg, 2);
            double std = Math.sqrt(variance / 64);

            if (std > 80 || max > avg * 3.5) {
                findings.add(String.format(
                        "ELA: Inconsistencia de compresión JPEG (stdDev=%.1f, pico/promedio=%.1fx). Zonas editadas detectadas.",
                        std, max / (avg + 0.01)));
                return Math.max(0.1, 1.0 - std / 200.0);
            }
            return 1.0;
        } catch (Exception e) {
            return 0.70;
        }
    }

    // ── 2. Análisis de zona de foto ───────────────────────────────────────────
    // Cuando alguien pega una foto sobre la original:
    //   a) El borde de la foto puesta muestra discontinuidad abrupta de ruido/color
    //   b) La foto pegada suele venir ya comprimida → doble artefacto JPEG en esa zona
    //   c) La temperatura de color de la foto insertada difiere del resto del documento

    private double analyzePhotoZone(Mat image, byte[] rawBytes, double[] photoZone,
                                    com.eduin.onboarding.authenticity.model.DocumentType docType,
                                    List<String> findings) {
        int imgW = image.cols(), imgH = image.rows();

        int px, py, pw, ph;
        double ringScore = 1.0;

        // Preferir la foto REAL: ubicar el rostro del titular. La posición de la
        // foto varía entre emisiones del mismo tipo de documento.
        Rect face = detectLargestFace(image);
        if (face != null) {
            // Región de foto derivada del rostro (cabeza + hombros + margen)
            px = face.x() - (int)(face.width() * 0.40);
            py = face.y() - (int)(face.height() * 0.55);
            pw = (int)(face.width() * 1.80);
            ph = (int)(face.height() * 2.60);

            // Análisis de anillo: en una foto auténtica el fondo alrededor de la
            // cabeza es LISO (fondo de estudio). Si alrededor del rostro aparece
            // la textura del carnet (guilloché) o un borde de recorte, la foto
            // fue recortada y pegada (montaje).
            ringScore = analyzeFaceRing(image, face, findings);

            // Cédula amarilla (COL_CC_OLD): el retrato auténtico es una impresión
            // integrada al carnet (apagada, texturizada, envejecida). Un anillo de
            // PAPEL FOTOGRÁFICO alrededor de la cabeza —brillante, uniforme y casi
            // sin color— delata una foto real recortada y sobrepuesta (montaje),
            // aunque su fondo liso engañe al análisis de densidad de bordes.
            if (docType == com.eduin.onboarding.authenticity.model.DocumentType.COL_CC_OLD) {
                ringScore = Math.min(ringScore, analyzePhotoPaperRing(image, face, findings));
            }
            face.close();
        } else {
            // Fallback: zona fija por tipo de documento
            px = (int)(photoZone[0] * imgW);
            py = (int)(photoZone[1] * imgH);
            pw = (int)(photoZone[2] * imgW);
            ph = (int)(photoZone[3] * imgH);
        }

        // Protección de límites
        px = Math.max(0, Math.min(px, imgW - 2));
        py = Math.max(0, Math.min(py, imgH - 2));
        pw = Math.min(pw, imgW - px);
        ph = Math.min(ph, imgH - py);
        if (pw < 20 || ph < 20) return Math.min(0.85, ringScore);

        // Verificar primero si la foto está obstruida (tachada, tapada con marcador/cinta)
        double obscureScore = analyzePhotoObscured(image, px, py, pw, ph, findings);
        if (obscureScore < 0.50) {
            return obscureScore; // Foto claramente obstruida
        }

        double borderScore  = analyzePhotoBorder(image, px, py, pw, ph, findings);
        double colorScore   = analyzePhotoColorInconsistency(image, px, py, pw, ph, findings);
        double elaZoneScore = analyzePhotoELA(rawBytes, image, px, py, pw, ph, findings);

        double score = borderScore * 0.40 + colorScore * 0.30 + elaZoneScore * 0.30;
        // Una señal individual FUERTE de foto pegada no se diluye en el promedio:
        // el score de la zona queda topado por la peor señal fuerte.
        if (colorScore < 0.60)  score = Math.min(score, colorScore);
        if (borderScore < 0.60) score = Math.min(score, borderScore);
        if (ringScore < 0.60)   score = Math.min(score, ringScore);
        return Math.max(0.0, score);
    }

    /**
     * Detección de foto sobrepuesta en cédula amarilla: mide si las franjas
     * alrededor de la cabeza tienen la firma de PAPEL FOTOGRÁFICO (fondo de
     * estudio): sin bordes, brillante, muy uniforme y casi sin saturación.
     *
     * Calibrado con capturas reales (2026-08): montajes físicos dan franjas con
     * V 169-224 (±4-6) y S 11-23; las auténticas dan V 87-126 con S 35-51, o
     * franjas saturadas/yellow del carnet (S 40-80), o directamente no se
     * detecta el rostro (retrato impreso lavado). Umbral: 2 de 3 franjas.
     */
    private double analyzePhotoPaperRing(Mat image, Rect face, List<String> findings) {
        Mat gray = new Mat(), edges = new Mat(), hsv = new Mat();
        try {
            cvtColor(image, gray, COLOR_BGR2GRAY);
            GaussianBlur(gray, gray, new Size(3, 3), 0);
            Canny(gray, edges, 60, 160);
            cvtColor(image, hsv, COLOR_BGR2HSV);

            int fw = face.width(), fh = face.height();
            int band = Math.max(8, (int)(fw * 0.22));
            int[][] patches = {
                { face.x() - band,      face.y() - (int)(fh * 0.15), band, (int)(fh * 0.9) },
                { face.x() + fw,        face.y() - (int)(fh * 0.15), band, (int)(fh * 0.9) },
                { face.x() - band / 2,  face.y() - (int)(fh * 0.45) - band / 2, fw + band, band }
            };

            int photoPaperBands = 0, measuredBands = 0;
            for (int[] p : patches) {
                int x = Math.max(0, p[0]), y = Math.max(0, p[1]);
                int w = Math.min(p[2], image.cols() - x), h = Math.min(p[3], image.rows() - y);
                if (w < 4 || h < 4) continue;
                measuredBands++;

                Rect r = new Rect(x, y, w, h);
                Mat ePatch = edges.apply(r);
                double density = (double) countNonZero(ePatch) / ((double) w * h);
                ePatch.release();

                Mat hPatch = hsv.apply(r);
                Mat mean = new Mat(), std = new Mat();
                meanStdDev(hPatch, mean, std);
                DoubleIndexer mIdx = mean.createIndexer();
                DoubleIndexer sIdx = std.createIndexer();
                double satMean = mIdx.get(1);
                double valMean = mIdx.get(2);
                double valStd  = sIdx.get(2);
                mIdx.release(); sIdx.release();
                hPatch.release(); mean.release(); std.release();

                boolean photoPaper = density < 0.015   // sin textura de impresión
                        && valMean > 150               // brillante (papel foto)
                        && valStd < 15                 // uniformidad extrema
                        && satMean < 30;               // sin el tono del carnet
                if (photoPaper) photoPaperBands++;
            }

            if (measuredBands >= 2 && photoPaperBands >= 2) {
                findings.add(String.format(
                        "Fondo de PAPEL FOTOGRÁFICO alrededor del rostro (%d/%d franjas: " +
                        "brillante, uniforme y sin textura del carnet). En la cédula amarilla " +
                        "el retrato auténtico está impreso e integrado — indicio fuerte de " +
                        "fotografía recortada y sobrepuesta (montaje).",
                        photoPaperBands, measuredBands));
                return 0.40;
            }
            return 1.0;
        } finally {
            gray.release(); edges.release(); hsv.release();
        }
    }

    /**
     * Anillo alrededor del rostro: mide la densidad de bordes (Canny) en las
     * franjas izquierda, derecha y superior de la cabeza. En una foto de
     * documento auténtica ese fondo es liso; el guilloché del carnet o el borde
     * de una silueta recortada producen alta densidad de bordes.
     */
    private double analyzeFaceRing(Mat image, Rect face, List<String> findings) {
        Mat gray = new Mat(), edges = new Mat();
        try {
            cvtColor(image, gray, COLOR_BGR2GRAY);
            GaussianBlur(gray, gray, new Size(3, 3), 0);
            Canny(gray, edges, 60, 160);

            int fw = face.width(), fh = face.height();
            int band = Math.max(8, (int)(fw * 0.22));

            // Franjas: izquierda, derecha y superior de la cabeza
            int[][] patches = {
                { face.x() - band,      face.y() - (int)(fh * 0.15), band, (int)(fh * 0.9) },
                { face.x() + fw,        face.y() - (int)(fh * 0.15), band, (int)(fh * 0.9) },
                { face.x() - band / 2,  face.y() - (int)(fh * 0.45) - band / 2, fw + band, band }
            };

            double totalEdge = 0, totalPx = 0;
            for (int[] p : patches) {
                int x = Math.max(0, p[0]), y = Math.max(0, p[1]);
                int w = Math.min(p[2], image.cols() - x), h = Math.min(p[3], image.rows() - y);
                if (w < 4 || h < 4) continue;
                Mat patch = edges.apply(new Rect(x, y, w, h));
                totalEdge += countNonZero(patch);
                totalPx   += (double) w * h;
                patch.release();
            }
            if (totalPx < 100) return 1.0;

            double density = totalEdge / totalPx;

            // Foto auténtica: fondo liso → densidad < ~8%.
            // Silueta recortada sobre guilloché: 15-35%.
            if (density > 0.11) {
                findings.add(String.format(
                        "Fondo alrededor del rostro NO uniforme: densidad de bordes=%.0f%% " +
                        "(esperado <8%% en foto original). La fotografía parece recortada y " +
                        "sobrepuesta sobre el documento (montaje).",
                        density * 100));
                // 0.11→0.55, 0.15→0.35, 0.20→0.10
                return Math.max(0.10, 0.55 - (density - 0.11) * 5.0);
            }
            return 1.0;
        } finally {
            gray.release(); edges.release();
        }
    }

    // 2-prev. Foto obstruida: tachada con marcador, cubierta con cinta, o bloqueada digitalmente
    private double analyzePhotoObscured(Mat image, int px, int py, int pw, int ph,
                                        List<String> findings) {
        Mat hsv = new Mat();
        cvtColor(image, hsv, COLOR_BGR2HSV);
        MatVector ch = new MatVector(3);
        split(hsv, ch);

        Mat valZone = ch.get(2).apply(new Rect(px, py, pw, ph));
        Mat satZone = ch.get(1).apply(new Rect(px, py, pw, ph));

        Mat mV = new Mat(), sV = new Mat(), mS = new Mat(), sS = new Mat();
        meanStdDev(valZone, mV, sV);
        meanStdDev(satZone, mS, sS);
        double meanVal = readDouble(mV, 0);
        double meanSat = readDouble(mS, 0);

        // Contar píxeles muy oscuros (V < 40) en la zona de foto
        int darkPixels = 0, totalPixels = 0;
        for (int y = py; y < py + ph && y < image.rows(); y++) {
            for (int x = px; x < px + pw && x < image.cols(); x++) {
                int v = valZone.ptr(y - py, x - px).get() & 0xFF;
                if (v < 40) darkPixels++;
                totalPixels++;
            }
        }

        hsv.release();
        for (int i = 0; i < ch.size(); i++) ch.get(i).release();
        valZone.release(); satZone.release();
        mV.release(); sV.release(); mS.release(); sS.release();

        double darkRatio = totalPixels > 0 ? (double) darkPixels / totalPixels : 0;

        // Zona completamente negra/oscura → foto tachada o cubierta
        if (meanVal < 35 || darkRatio > 0.70) {
            findings.add(String.format(
                    "Zona de foto OBSTRUIDA: brillo promedio=%.0f, píxeles oscuros=%.0f%%. " +
                    "La fotografía del titular está tachada, cubierta o bloqueada.",
                    meanVal, darkRatio * 100));
            return 0.0;
        }

        // Zona muy oscura con baja saturación → posible ocultamiento parcial
        if (meanVal < 60 && meanSat < 30 && darkRatio > 0.40) {
            findings.add(String.format(
                    "Zona de foto posiblemente obstruida: brillo=%.0f, saturación=%.0f, oscuros=%.0f%%.",
                    meanVal, meanSat, darkRatio * 100));
            return 0.25;
        }

        return 1.0;
    }

    // 2a. Discontinuidad de borde: si la foto fue pegada, hay un salto brusco
    //     de ruido/gradiente justo en sus bordes vs. el interior

    private double analyzePhotoBorder(Mat image, int px, int py, int pw, int ph,
                                      List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        int margin = Math.max(4, Math.min(pw, ph) / 10);

        // Ruido promedio DENTRO de la zona de foto
        Mat photoRegion = gray.apply(new Rect(px + margin, py + margin,
                                              pw - 2 * margin, ph - 2 * margin));
        Mat bInner = new Mat(), sInner = new Mat();
        GaussianBlur(photoRegion, bInner, new Size(5, 5), 0);
        Mat noiseInner = new Mat();
        subtract(photoRegion, bInner, noiseInner);
        noiseInner.convertTo(noiseInner, CV_32F);
        Mat mI = new Mat(), sI = new Mat();
        meanStdDev(noiseInner, mI, sI);
        double innerNoise = readDouble(sI, 0);

        // Ruido promedio en el BORDE inmediato alrededor de la zona de foto
        double outerNoise = 0;
        int outerSamples  = 0;
        int border = margin;

        // Franja izquierda
        if (px - border >= 0) {
            Mat outer = gray.apply(new Rect(Math.max(0, px - border), py,
                                            border, ph));
            Mat bo = new Mat(), no = new Mat();
            GaussianBlur(outer, bo, new Size(5, 5), 0);
            subtract(outer, bo, no);
            no.convertTo(no, CV_32F);
            Mat mO = new Mat(), sO = new Mat();
            meanStdDev(no, mO, sO);
            outerNoise += readDouble(sO, 0);
            outerSamples++;
            outer.release(); bo.release(); no.release(); mO.release(); sO.release();
        }
        // Franja derecha
        if (px + pw + border < gray.cols()) {
            Mat outer = gray.apply(new Rect(px + pw, py,
                                            border, ph));
            Mat bo = new Mat(), no = new Mat();
            GaussianBlur(outer, bo, new Size(5, 5), 0);
            subtract(outer, bo, no);
            no.convertTo(no, CV_32F);
            Mat mO = new Mat(), sO = new Mat();
            meanStdDev(no, mO, sO);
            outerNoise += readDouble(sO, 0);
            outerSamples++;
            outer.release(); bo.release(); no.release(); mO.release(); sO.release();
        }

        gray.release(); photoRegion.release(); bInner.release();
        noiseInner.release(); mI.release(); sI.release();

        if (outerSamples == 0) return 0.85;
        outerNoise /= outerSamples;

        // Si el ruido DENTRO de la foto es muy diferente al ruido FUERA, hay discontinuidad
        double ratio = (innerNoise + 0.01) / (outerNoise + 0.01);
        boolean anomaly = ratio > 2.5 || ratio < 0.35;

        if (anomaly) {
            findings.add(String.format(
                    "Zona de foto — discontinuidad de ruido en el borde: interior=%.2f, exterior=%.2f (ratio=%.2fx). " +
                    "Indica posible foto superpuesta.",
                    innerNoise, outerNoise, ratio));
            return Math.max(0.0, 1.0 - Math.abs(Math.log(ratio)) / 2.0);
        }
        return 1.0;
    }

    // 2b. ELA localizado en la zona de foto
    //     Una foto pegada que venía comprimida muestra artefactos JPEG distintos
    //     al resto del documento (doble compresión)

    private double analyzePhotoELA(byte[] rawBytes, Mat image, int px, int py, int pw, int ph,
                                   List<String> findings) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) return 0.80;

            BufferedImage recomp = recompressJpeg(original, 0.75f);
            if (recomp == null) return 0.80;

            int w = Math.min(original.getWidth(),  recomp.getWidth());
            int h = Math.min(original.getHeight(), recomp.getHeight());

            // Escalar coordenadas si la imagen fue redimensionada
            double scaleX = (double) w / image.cols();
            double scaleY = (double) h / image.rows();
            int spx = (int)(px * scaleX), spy = (int)(py * scaleY);
            int spw = (int)(pw * scaleX), sph = (int)(ph * scaleY);
            spw = Math.min(spw, w - spx);
            sph = Math.min(sph, h - spy);
            if (spw < 10 || sph < 10) return 0.80;

            // ELA promedio DENTRO de la zona de foto
            double photoEla  = elaRegion(original, recomp, spx, spy, spw, sph);

            // ELA promedio en el RESTO del documento (excluyendo la zona de foto)
            double restEla   = elaRegionExclude(original, recomp, spx, spy, spw, sph, w, h);

            double ratio = (restEla + 0.01) > 0 ? photoEla / (restEla + 0.01) : 1.0;

            // Si la zona de foto tiene nivel de error MUY diferente al resto → foto insertada
            if (ratio > 2.2 || ratio < 0.45) {
                findings.add(String.format(
                        "Zona de foto — doble compresión JPEG detectada: ELA_foto=%.1f, ELA_doc=%.1f (ratio=%.2fx). " +
                        "La foto del titular puede haber sido sustituida.",
                        photoEla, restEla, ratio));
                return Math.max(0.05, 1.0 - Math.abs(Math.log(ratio + 0.01)) / 2.0);
            }
            return 1.0;
        } catch (Exception e) {
            return 0.75;
        }
    }

    // 2c. Inconsistencia de color: la foto pegada tiene diferente temperatura/saturación

    private double analyzePhotoColorInconsistency(Mat image, int px, int py, int pw, int ph,
                                                   List<String> findings) {
        Mat hsv = new Mat();
        cvtColor(image, hsv, COLOR_BGR2HSV);
        MatVector ch = new MatVector(3);
        split(hsv, ch);

        // Excluir píxeles de reflejo/flash (V>230): el brillo de un reflejo en la zona
        // de la foto o en el documento es ruido óptico, no información de color del titular.
        Mat valFull = ch.get(2);
        Mat noGlareMask = new Mat();
        threshold(valFull, noGlareMask, 230, 255, THRESH_BINARY_INV);

        Mat maskZone = noGlareMask.apply(new Rect(px, py, pw, ph));
        Mat satZone  = ch.get(1).apply(new Rect(px, py, pw, ph));
        Mat valZone  = ch.get(2).apply(new Rect(px, py, pw, ph));
        Mat mSZ = new Mat(), sSZ = new Mat(), mVZ = new Mat(), sVZ = new Mat();
        meanStdDev(satZone, mSZ, sSZ, maskZone);
        meanStdDev(valZone, mVZ, sVZ, maskZone);
        double satPhoto  = readDouble(mSZ, 0);
        double valPhoto  = readDouble(mVZ, 0);

        // Región de referencia: el cuerpo del documento al lado OPUESTO de la foto
        // (la foto puede estar a la izquierda — CC nueva — o a la derecha — CC antigua).
        // Se toma el lado más ancho; si no hay franja suficiente, no se puede comparar.
        int leftW  = Math.max(0, px - 10);
        int rightX = Math.min(image.cols(), px + pw + 10);
        int rightW = Math.max(0, image.cols() - rightX);
        Rect docRect = leftW >= rightW
                ? new Rect(0, 0, Math.max(1, leftW), image.rows())
                : new Rect(rightX, 0, Math.max(1, rightW), image.rows());
        if (Math.max(leftW, rightW) < image.cols() * 0.15) {
            hsv.release(); noGlareMask.release();
            for (int i = 0; i < ch.size(); i++) ch.get(i).release();
            maskZone.release(); satZone.release(); valZone.release();
            mSZ.release(); sSZ.release(); mVZ.release(); sVZ.release();
            return 1.0; // sin región de comparación confiable
        }
        Mat maskDoc = noGlareMask.apply(docRect);
        Mat satDoc  = ch.get(1).apply(docRect);
        Mat valDoc  = ch.get(2).apply(docRect);
        Mat mSD = new Mat(), sSD = new Mat(), mVD = new Mat(), sVD = new Mat();
        meanStdDev(satDoc, mSD, sSD, maskDoc);
        meanStdDev(valDoc, mVD, sVD, maskDoc);
        double satDocument = readDouble(mSD, 0);
        double valDocument = readDouble(mVD, 0);

        hsv.release(); noGlareMask.release();
        for (int i = 0; i < ch.size(); i++) ch.get(i).release();
        mSZ.release(); sSZ.release(); mVZ.release(); sVZ.release();
        mSD.release(); sSD.release(); mVD.release(); sVD.release();

        // DIRECCIONAL: una foto pegada (impresa a color en papel fotográfico) es MÁS
        // saturada que el carnet. La dirección inversa es legítima: la cédula digital
        // trae la foto del titular en blanco y negro (menos saturada que el carnet).
        double satDiff = satPhoto - satDocument;
        double valDiff = Math.abs(valPhoto - valDocument);
        boolean anomaly = satDiff > 30 || valDiff > 60;

        if (anomaly) {
            findings.add(String.format(
                    "Zona de foto — color inconsistente con el documento: " +
                    "sat_foto=%.0f vs sat_doc=%.0f (Δ%.0f), brillo_foto=%.0f vs brillo_doc=%.0f (Δ%.0f). " +
                    "Foto del titular puede ser de una fuente diferente.",
                    satPhoto, satDocument, satDiff,
                    valPhoto, valDocument, valDiff));
            // En un documento auténtico la foto comparte el proceso de impresión del
            // resto del carnet — una diferencia grande de saturación es señal fuerte
            // de foto pegada. Penalización agresiva: Δ30→0.45, Δ37→0.28, Δ48+→0.10
            double penalty = (Math.max(satDiff, valDiff) - 8) / 40.0;
            return Math.max(0.10, 1.0 - penalty);
        }
        return 1.0;
    }

    // ── 3. Consistencia de ruido por bloques ─────────────────────────────────

    private double analyzeNoiseConsistency(Mat image, List<String> findings) {
        Mat gray    = new Mat();
        Mat blurred = new Mat();
        Mat noise   = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        GaussianBlur(gray, blurred, new Size(5, 5), 0);
        subtract(gray, blurred, noise);
        noise.convertTo(noise, CV_32F);

        List<Double> variances = new ArrayList<>();
        int bs = 64;
        for (int y = 0; y + bs < noise.rows(); y += bs) {
            for (int x = 0; x + bs < noise.cols(); x += bs) {
                Mat block = noise.apply(new Rect(x, y, bs, bs));
                Mat mM = new Mat(), sM = new Mat();
                meanStdDev(block, mM, sM);
                variances.add(readDouble(sM, 0));
                block.release(); mM.release(); sM.release();
            }
        }
        gray.release(); blurred.release(); noise.release();

        if (variances.isEmpty()) return 0.85;
        double avg = variances.stream().mapToDouble(d -> d).average().orElse(0);
        double max = variances.stream().mapToDouble(d -> d).max().orElse(0);
        double min = variances.stream().mapToDouble(d -> d).min().orElse(0);
        double relRange = avg > 0 ? (max - min) / avg : 0;

        if (relRange > 4.0) {
            findings.add(String.format(
                    "Inconsistencia de ruido: rango relativo=%.1fx entre bloques. Posible material insertado.",
                    relRange));
            return Math.max(0.2, 1.0 - (relRange - 4.0) / 8.0);
        }
        return 1.0;
    }

    // ── 4. Consistencia de iluminación ────────────────────────────────────────

    private double analyzeIlluminationConsistency(Mat image, List<String> findings) {
        Mat gray = new Mat(), illum = new Mat(), gx = new Mat(), gy = new Mat(), gradM = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        int k = Math.max(51, (int)(Math.min(gray.rows(), gray.cols()) * 0.15) | 1);
        GaussianBlur(gray, illum, new Size(k, k), 0);
        Sobel(illum, gx, CV_32F, 1, 0, 3, 1, 0, BORDER_DEFAULT);
        Sobel(illum, gy, CV_32F, 0, 1, 3, 1, 0, BORDER_DEFAULT);
        magnitude(gx, gy, gradM);

        Mat mM = new Mat(), sM = new Mat();
        meanStdDev(gradM, mM, sM);
        double gmean = readDouble(mM, 0);
        double gstd  = readDouble(sM, 0);
        gray.release(); illum.release(); gx.release(); gy.release();
        gradM.release(); mM.release(); sM.release();

        double cv = gmean > 0 ? gstd / gmean : 0;
        if (cv > 2.5) {
            findings.add(String.format(
                    "Iluminación inconsistente: CV=%.2f. Posible elemento sobrepuesto.", cv));
            return Math.max(0.3, 1.0 - (cv - 2.5) / 5.0);
        }
        return 1.0;
    }

    // ── 5. Detección de regiones clonadas ────────────────────────────────────

    private double detectCloneRegions(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        int bs = 32, step = 64;
        List<double[]> hashes = new ArrayList<>();

        for (int r = 0; r + bs < gray.rows(); r += step) {
            for (int c = 0; c + bs < gray.cols(); c += step) {
                Mat block = gray.apply(new Rect(c, r, bs, bs));
                Mat small = new Mat();
                resize(block, small, new Size(8, 8));
                double[] hash = new double[64];
                for (int i = 0; i < 8; i++)
                    for (int j = 0; j < 8; j++)
                        hash[i*8+j] = small.ptr(i, j).get() & 0xFF;
                hashes.add(hash);
                block.release(); small.release();
            }
        }
        gray.release();

        int suspect = 0;
        for (int i = 0; i < hashes.size(); i++)
            for (int j = i + 2; j < hashes.size(); j++)
                if (hammingDist(hashes.get(i), hashes.get(j)) <= 5) suspect++;

        double total = (double) hashes.size() * (hashes.size() - 1) / 2;
        double ratio = total > 0 ? suspect / total : 0;

        if (ratio > 0.05) {
            findings.add(String.format(
                    "Regiones clonadas: %.1f%% de bloques casi idénticos en distintas posiciones.", ratio * 100));
            return Math.max(0.3, 1.0 - ratio * 5);
        }
        return 1.0;
    }

    // ── 6. ELA multi-nivel ────────────────────────────────────────────────────
    // Una zona editada tiene un nivel de compresión diferente al original.
    // Comparar a 3 calidades (60%, 75%, 90%) amplifica la señal vs. una sola calidad.
    // Una zona editada que ya venía comprimida (doble compresión) se estabiliza
    // a baja calidad pero se destaca a alta → la diferencia entre niveles es alta.

    private double performMultiLevelELA(byte[] rawBytes, List<String> findings) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (original == null) return 0.80;

            // NOTA: comparar la MAGNITUD GLOBAL de error entre calidades (60/75/90) no es
            // señal válida de edición — cualquier foto real muestra más diferencia a menor
            // calidad, sin relación con manipulación. La señal correcta es buscar una REGIÓN
            // que sea un outlier de forma CONSISTENTE en las 3 calidades (relativo al promedio
            // de su propia calidad) — eso sí delata una zona con distinto historial de compresión.
            float[] qualities = {0.60f, 0.75f, 0.90f};
            int w = original.getWidth(), h = original.getHeight();
            int rW = Math.max(1, w / 8), rH = Math.max(1, h / 8);

            // outlierRatio[region] = cuántas de las 3 calidades marcan esa región como outlier
            int[] outlierHits = new int[64];

            for (float quality : qualities) {
                BufferedImage recomp = recompressJpeg(original, quality);
                if (recomp == null) continue;
                double[] regionDiffs = new double[64];
                int idx = 0;
                for (int ry = 0; ry < 8; ry++) {
                    for (int rx = 0; rx < 8; rx++) {
                        double regionSum = 0; int regionCnt = 0;
                        for (int y = ry * rH; y < Math.min((ry+1)*rH, h); y++) {
                            for (int x = rx * rW; x < Math.min((rx+1)*rW, w); x++) {
                                int o = original.getRGB(x, y), r2 = recomp.getRGB(x, y);
                                int dr = ((o>>16)&0xFF)-((r2>>16)&0xFF);
                                int dg = ((o>>8)&0xFF)-((r2>>8)&0xFF);
                                int db = (o&0xFF)-(r2&0xFF);
                                regionSum += Math.sqrt(dr*dr+dg*dg+db*db);
                                regionCnt++;
                            }
                        }
                        regionDiffs[idx++] = regionCnt > 0 ? regionSum / regionCnt : 0;
                    }
                }
                double avg = 0;
                for (double d : regionDiffs) avg += d;
                avg /= 64;
                if (avg < 0.5) continue; // sin contenido suficiente para evaluar
                // Marcar como outlier las regiones con error 3x+ el promedio de ESTA calidad
                for (int i = 0; i < 64; i++) {
                    if (regionDiffs[i] > avg * 3.0) outlierHits[i]++;
                }
            }

            // Una región editada se mantiene outlier en las 3 calidades (consistente).
            // Una región normal puede destacar en una calidad por azar, pero no en las 3.
            int consistentOutliers = 0;
            for (int hits : outlierHits) if (hits >= 3) consistentOutliers++;

            if (consistentOutliers >= 2) {
                findings.add(String.format(
                        "ELA multi-nivel: %d región(es) con error de compresión consistentemente anómalo " +
                        "en las 3 calidades evaluadas — indica zona con historial de edición distinto al resto.",
                        consistentOutliers));
                return Math.max(0.10, 1.0 - consistentOutliers * 0.12);
            }
            return 1.0;
        } catch (Exception e) {
            return 0.80;
        }
    }

    // ── 7. Detección de bordes de splicing ───────────────────────────────────
    // Cuando se pega un elemento sobre el documento, la frontera entre el elemento
    // pegado y el fondo muestra:
    //  a) Un salto abrupto de ruido local
    //  b) Gradiente muy alto en una línea fina → borde artificial
    //  c) Inconsistencia de dirección de gradiente (borde artificial vs. borde de texto natural)

    private double detectSplicingBoundaries(Mat image, List<String> findings) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);

        // Calcular mapa de ruido local en bloques 16x16
        int bs = 16;
        int cols16 = gray.cols() / bs;
        int rows16 = gray.rows() / bs;
        if (cols16 < 4 || rows16 < 4) { gray.release(); return 1.0; }

        double[][] noiseMap = new double[rows16][cols16];
        for (int r = 0; r < rows16; r++) {
            for (int c = 0; c < cols16; c++) {
                Mat block = gray.apply(new Rect(c * bs, r * bs, bs, bs));
                Mat blur  = new Mat(), noise = new Mat();
                GaussianBlur(block, blur, new Size(3, 3), 0);
                absdiff(block, blur, noise);
                noise.convertTo(noise, CV_32F);
                Mat mM = new Mat(), sM = new Mat();
                meanStdDev(noise, mM, sM);
                noiseMap[r][c] = readDouble(sM, 0);
                block.release(); blur.release(); noise.release(); mM.release(); sM.release();
            }
        }
        gray.release();

        // Buscar saltos abruptos en el mapa de ruido (borde de splicing)
        int suspectBoundaries = 0;
        int totalBoundaries   = 0;

        for (int r = 0; r < rows16; r++) {
            for (int c = 0; c < cols16 - 1; c++) {
                double left  = noiseMap[r][c];
                double right = noiseMap[r][c + 1];
                double maxN  = Math.max(left, right);
                double ratio = maxN > 0.5 ? Math.abs(left - right) / maxN : 0;
                if (ratio > 0.70) suspectBoundaries++;
                totalBoundaries++;
            }
        }
        for (int r = 0; r < rows16 - 1; r++) {
            for (int c = 0; c < cols16; c++) {
                double top    = noiseMap[r][c];
                double bottom = noiseMap[r + 1][c];
                double maxN   = Math.max(top, bottom);
                double ratio  = maxN > 0.5 ? Math.abs(top - bottom) / maxN : 0;
                if (ratio > 0.70) suspectBoundaries++;
                totalBoundaries++;
            }
        }

        double boundaryRatio = totalBoundaries > 0 ? (double) suspectBoundaries / totalBoundaries : 0;
        if (boundaryRatio > 0.18) {
            findings.add(String.format(
                    "Bordes de splicing: %.0f%% de fronteras entre bloques muestran salto abrupto de ruido. " +
                    "Indica elemento externo pegado sobre el documento.",
                    boundaryRatio * 100));
            return Math.max(0.10, 1.0 - boundaryRatio * 3);
        }
        return 1.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BufferedImage recompressJpeg(BufferedImage src, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) return null;
            ImageWriter writer = writers.next();
            ImageWriteParam iwp = writer.getDefaultWriteParam();
            iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            iwp.setCompressionQuality(quality);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(src, null, null), iwp);
            writer.dispose();
            return ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) { return null; }
    }

    private double elaRegion(BufferedImage orig, BufferedImage recomp,
                             int x, int y, int w, int h) {
        double sum = 0; int cnt = 0;
        for (int ry = y; ry < y + h; ry++) {
            for (int rx = x; rx < x + w; rx++) {
                int o = orig.getRGB(rx, ry), r2 = recomp.getRGB(rx, ry);
                int dr = ((o >> 16) & 0xFF) - ((r2 >> 16) & 0xFF);
                int dg = ((o >>  8) & 0xFF) - ((r2 >>  8) & 0xFF);
                int db = ( o        & 0xFF) - ( r2        & 0xFF);
                sum += Math.sqrt(dr*dr + dg*dg + db*db); cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private double elaRegionExclude(BufferedImage orig, BufferedImage recomp,
                                    int ex, int ey, int ew, int eh, int w, int h) {
        double sum = 0; int cnt = 0;
        for (int ry = 0; ry < h; ry++) {
            for (int rx = 0; rx < w; rx++) {
                if (rx >= ex && rx < ex + ew && ry >= ey && ry < ey + eh) continue;
                int o = orig.getRGB(rx, ry), r2 = recomp.getRGB(rx, ry);
                int dr = ((o >> 16) & 0xFF) - ((r2 >> 16) & 0xFF);
                int dg = ((o >>  8) & 0xFF) - ((r2 >>  8) & 0xFF);
                int db = ( o        & 0xFF) - ( r2        & 0xFF);
                sum += Math.sqrt(dr*dr + dg*dg + db*db); cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private double readDouble(Mat mat, int channel) {
        try (DoubleIndexer idx = mat.createIndexer()) {
            return idx.get((long) channel);
        } catch (Exception e) { return 0; }
    }

    private int hammingDist(double[] a, double[] b) {
        double mean = 0;
        for (double v : a) mean += v;
        mean /= a.length;
        int d = 0;
        for (int i = 0; i < a.length; i++)
            if ((a[i] > mean) != (b[i] > mean)) d++;
        return d;
    }

    private String buildVerdict(double ela, double photo, double noise) {
        List<String> issues = new ArrayList<>();
        if (photo < 0.60) issues.add("foto del titular manipulada/sustituida");
        if (ela   < 0.60) issues.add("inconsistencia JPEG (ELA)");
        if (noise < 0.60) issues.add("material insertado (ruido inconsistente)");
        return "Manipulación detectada: " + (issues.isEmpty() ? "múltiples indicios" : String.join(", ", issues));
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
