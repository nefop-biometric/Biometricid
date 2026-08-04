package com.eduin.onboarding.authenticity;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import com.eduin.onboarding.authenticity.model.DocumentType;
import com.eduin.onboarding.authenticity.service.DocumentCropService;
import com.eduin.onboarding.authenticity.service.analyzer.ImageLoader;
import com.eduin.onboarding.authenticity.service.analyzer.TamperingDetector;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnóstico manual: corre el TamperingDetector sobre una captura real con montaje
 * y muestra los hallazgos de cada sub-análisis. Se activa solo con -Ddiag.image=...
 */
class TamperingDiagnosticTest {

    @Test
    void diagnoseImage() throws Exception {
        String imagePath = System.getProperty("diag.image");
        if (imagePath == null) {
            System.out.println("(sin -Ddiag.image, test omitido)");
            return;
        }

        byte[] rawBytes = Files.readAllBytes(Path.of(imagePath));
        ImageLoader loader = new ImageLoader();
        DocumentCropService cropService = new DocumentCropService();
        TamperingDetector detector = new TamperingDetector();

        Mat fullFrame = loader.load(rawBytes, "_diag.jpg");
        Mat mat = fullFrame;
        byte[] analysisBytes = rawBytes;
        Rect docRect = cropService.detectDocument(fullFrame);
        if (docRect != null) {
            mat = new Mat(fullFrame, docRect).clone();
            analysisBytes = encodeJpeg(mat);
            System.out.printf("CROP: %dx%d en (%d,%d)%n",
                    docRect.width(), docRect.height(), docRect.x(), docRect.y());
        } else {
            System.out.println("CROP: no detectado (se analiza el frame completo)");
        }

        // Sub-scores individuales vía reflexión (métodos privados)
        java.util.List<String> diagFindings = new java.util.ArrayList<>();
        Object face = invoke(detector, "detectLargestFace", new Class[]{Mat.class}, mat);
        System.out.println("FACE: " + (face == null ? "NO DETECTADO" : describeRect((Rect) face, mat)));
        System.out.println("ela          = " + invoke(detector, "performELA", new Class[]{byte[].class, java.util.List.class}, analysisBytes, diagFindings));
        System.out.println("multiEla     = " + invoke(detector, "performMultiLevelELA", new Class[]{byte[].class, java.util.List.class}, analysisBytes, diagFindings));
        System.out.println("noise        = " + invoke(detector, "analyzeNoiseConsistency", new Class[]{Mat.class, java.util.List.class}, mat, diagFindings));
        System.out.println("illumination = " + invoke(detector, "analyzeIlluminationConsistency", new Class[]{Mat.class, java.util.List.class}, mat, diagFindings));
        System.out.println("clone        = " + invoke(detector, "detectCloneRegions", new Class[]{Mat.class, java.util.List.class}, mat, diagFindings));
        System.out.println("splicing     = " + invoke(detector, "detectSplicingBoundaries", new Class[]{Mat.class, java.util.List.class}, mat, diagFindings));
        if (face != null) {
            System.out.println("faceRing     = " + invoke(detector, "analyzeFaceRing", new Class[]{Mat.class, Rect.class, java.util.List.class}, mat, face, diagFindings));
        }

        if (face != null) {
            ringStats(mat, (Rect) face);
            silhouetteStats(mat, (Rect) face);
        }

        // Zona de retrato: derivada del rostro, o zona fija CC_OLD si no hay rostro
        int px, py, pw, ph;
        if (face != null) {
            Rect fr = (Rect) face;
            px = fr.x() - (int) (fr.width() * 0.40);
            py = fr.y() - (int) (fr.height() * 0.55);
            pw = (int) (fr.width() * 1.80);
            ph = (int) (fr.height() * 2.60);
        } else {
            double[] zone = (double[]) field(detector, "PHOTO_ZONE_CC_OLD");
            px = (int) (zone[0] * mat.cols());
            py = (int) (zone[1] * mat.rows());
            pw = (int) (zone[2] * mat.cols());
            ph = (int) (zone[3] * mat.rows());
        }
        px = Math.max(0, Math.min(px, mat.cols() - 2));
        py = Math.max(0, Math.min(py, mat.rows() - 2));
        pw = Math.min(pw, mat.cols() - px);
        ph = Math.min(ph, mat.rows() - py);
        portraitStats(mat, px, py, pw, ph);

        AnalysisDetail detail = detector.analyze(mat, analysisBytes, DocumentType.COL_CC_OLD);
        System.out.println("SCORE: " + detail.getScore() + "  passed=" + detail.isPassed());
        System.out.println("VERDICT: " + detail.getVerdict());
        System.out.println("FINDINGS:");
        if (detail.getFindings() != null) {
            detail.getFindings().forEach(f -> System.out.println("  - " + f));
        }
        System.out.println("WARNINGS:");
        if (detail.getWarnings() != null) {
            detail.getWarnings().forEach(w -> System.out.println("  - " + w));
        }
    }

    /** Estadísticas del anillo alrededor del rostro: densidad de bordes y color HSV por franja. */
    private static void ringStats(Mat image, Rect face) {
        Mat gray = new Mat(), edges = new Mat(), hsv = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.cvtColor(image, gray,
                org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY);
        org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur(gray, gray,
                new org.bytedeco.opencv.opencv_core.Size(3, 3), 0);
        org.bytedeco.opencv.global.opencv_imgproc.Canny(gray, edges, 60, 160);
        org.bytedeco.opencv.global.opencv_imgproc.cvtColor(image, hsv,
                org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV);

        int fw = face.width(), fh = face.height();
        int band = Math.max(8, (int) (fw * 0.22));
        int[][] patches = {
                {face.x() - band, face.y() - (int) (fh * 0.15), band, (int) (fh * 0.9)},
                {face.x() + fw, face.y() - (int) (fh * 0.15), band, (int) (fh * 0.9)},
                {face.x() - band / 2, face.y() - (int) (fh * 0.45) - band / 2, fw + band, band}
        };
        String[] names = {"izq", "der", "sup"};
        for (int i = 0; i < patches.length; i++) {
            int[] p = patches[i];
            int x = Math.max(0, p[0]), y = Math.max(0, p[1]);
            int w = Math.min(p[2], image.cols() - x), h = Math.min(p[3], image.rows() - y);
            if (w < 4 || h < 4) { System.out.println("RING " + names[i] + ": fuera de imagen"); continue; }
            Rect r = new Rect(x, y, w, h);
            Mat ePatch = edges.apply(r);
            double density = (double) org.bytedeco.opencv.global.opencv_core.countNonZero(ePatch) / (w * h);
            Mat hPatch = hsv.apply(r);
            Mat mean = new Mat(), std = new Mat();
            org.bytedeco.opencv.global.opencv_core.meanStdDev(hPatch, mean, std);
            java.nio.DoubleBuffer mb = mean.createBuffer(), sb = std.createBuffer();
            System.out.printf("RING %s: edges=%.1f%%  H=%.0f±%.0f S=%.0f±%.0f V=%.0f±%.0f%n",
                    names[i], density * 100,
                    mb.get(0), sb.get(0), mb.get(1), sb.get(1), mb.get(2), sb.get(2));
            ePatch.release(); hPatch.release(); mean.release(); std.release();
        }
        gray.release(); edges.release(); hsv.release();
    }

    /** Saturación de la zona de retrato: media, desviación y fracción de píxeles muy saturados. */
    private static void portraitStats(Mat image, int px, int py, int pw, int ph) {
        Mat hsv = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.cvtColor(image, hsv,
                org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV);
        Mat zone = hsv.apply(new Rect(px, py, pw, ph));
        org.bytedeco.opencv.opencv_core.MatVector ch = new org.bytedeco.opencv.opencv_core.MatVector(3);
        org.bytedeco.opencv.global.opencv_core.split(zone, ch);
        Mat sat = ch.get(1);
        Mat mean = new Mat(), std = new Mat();
        org.bytedeco.opencv.global.opencv_core.meanStdDev(sat, mean, std);
        Mat highSat = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.threshold(sat, highSat, 90, 255,
                org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY);
        double highFrac = (double) org.bytedeco.opencv.global.opencv_core.countNonZero(highSat)
                / ((double) pw * ph);
        java.nio.DoubleBuffer mb = mean.createBuffer(), sb = std.createBuffer();
        System.out.printf("PORTRAIT [%d,%d %dx%d]: satMean=%.0f satStd=%.0f highSat(>90)=%.1f%%%n",
                px, py, pw, ph, mb.get(0), sb.get(0), highFrac * 100);
        hsv.release(); zone.release(); sat.release(); highSat.release(); mean.release(); std.release();
    }

    /**
     * Nitidez del contorno de la silueta: rayos radiales desde el centro del rostro
     * (hemisferio superior). Un recorte pegado produce un escalón abrupto de gris;
     * un retrato impreso tiene transiciones suaves.
     */
    private static void silhouetteStats(Mat image, Rect face) {
        Mat gray = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.cvtColor(image, gray,
                org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY);
        org.bytedeco.javacpp.indexer.UByteIndexer idx = gray.createIndexer();

        double cx = face.x() + face.width() / 2.0;
        double cy = face.y() + face.height() / 2.0;
        double r0 = face.width() * 0.45, r1 = face.width() * 1.15;

        java.util.List<Double> maxSteps = new java.util.ArrayList<>();
        for (int deg = 180; deg <= 360; deg += 5) {   // hemisferio superior (y hacia arriba)
            double rad = Math.toRadians(deg);
            double dx = Math.cos(rad), dy = Math.sin(rad);
            double maxStep = 0;
            for (double r = r0; r < r1; r += 1.0) {
                int xA = (int) Math.round(cx + dx * (r - 2)), yA = (int) Math.round(cy + dy * (r - 2));
                int xB = (int) Math.round(cx + dx * (r + 2)), yB = (int) Math.round(cy + dy * (r + 2));
                if (xA < 0 || yA < 0 || xB < 0 || yB < 0
                        || xA >= gray.cols() || xB >= gray.cols() || yA >= gray.rows() || yB >= gray.rows()) {
                    break;
                }
                double step = Math.abs((double) idx.get(yA, xA) - idx.get(yB, xB));
                if (step > maxStep) maxStep = step;
            }
            maxSteps.add(maxStep);
        }
        idx.release(); gray.release();
        if (maxSteps.isEmpty()) { System.out.println("SILHOUETTE: sin rayos"); return; }
        java.util.Collections.sort(maxSteps);
        double median = maxSteps.get(maxSteps.size() / 2);
        double p80 = maxSteps.get((int) (maxSteps.size() * 0.8));
        long sharp = maxSteps.stream().filter(s -> s > 70).count();
        System.out.printf("SILHOUETTE: median=%.0f p80=%.0f rayosNitidos(>70)=%d/%d%n",
                median, p80, sharp, maxSteps.size());
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            var m = target.getClass().getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            return "ERROR: " + e.getCause();
        }
    }

    private static String describeRect(Rect r, Mat img) {
        return String.format("%dx%d en (%d,%d) [imagen %dx%d]",
                r.width(), r.height(), r.x(), r.y(), img.cols(), img.rows());
    }

    private static byte[] encodeJpeg(Mat mat) {
        BytePointer buf = new BytePointer();
        try {
            opencv_imgcodecs.imencode(".jpg", mat, buf,
                    new IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 92));
            byte[] out = new byte[(int) buf.limit()];
            buf.get(out);
            return out;
        } finally {
            buf.deallocate();
        }
    }
}
