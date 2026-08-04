package com.eduin.onboarding.authenticity.ml;

import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_core.split;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV;
import static org.bytedeco.opencv.global.opencv_imgproc.Canny;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

/**
 * Vector de características de textura y color de la zona del retrato, para el
 * clasificador de foto sobrepuesta. La zona se normaliza a 128x160 para que las
 * características no dependan de la resolución de captura.
 *
 * Composición (83 dims): LBP uniforme 8-vecinos (59) + histograma S (8) +
 * histograma V (8) + densidades de borde global/tercios superiores (4) +
 * medias y desviaciones de S y V (4).
 */
public final class PhotoZoneFeatures {

    public static final int DIMENSIONS = 83;
    private static final int NORM_W = 128;
    private static final int NORM_H = 160;

    /** Mapeo de patrón LBP (0-255) a bin uniforme (0-58). */
    private static final int[] UNIFORM_MAP = buildUniformMap();

    private PhotoZoneFeatures() {
    }

    public static float[] extract(Mat bgr, Rect zone) {
        Mat zoneMat = new Mat(bgr, zone).clone();
        Mat norm = new Mat();
        resize(zoneMat, norm, new Size(NORM_W, NORM_H), 0, 0, INTER_AREA);
        zoneMat.release();

        Mat gray = new Mat(), hsv = new Mat();
        cvtColor(norm, gray, COLOR_BGR2GRAY);
        cvtColor(norm, hsv, COLOR_BGR2HSV);

        float[] features = new float[DIMENSIONS];
        int idx = 0;

        // 1) LBP uniforme normalizado (59)
        double[] lbp = lbpHistogram(gray);
        for (double v : lbp) features[idx++] = (float) v;

        // 2) Histogramas S y V, 8 bins cada uno, normalizados (16)
        MatVector channels = new MatVector(3);
        split(hsv, channels);
        double[] sHist = histogram8(channels.get(1));
        double[] vHist = histogram8(channels.get(2));
        for (double v : sHist) features[idx++] = (float) v;
        for (double v : vHist) features[idx++] = (float) v;

        // 3) Densidad de bordes: global + tercios izquierdo/central/derecho de la
        //    mitad superior (donde está la cabeza y su fondo) (4)
        Mat blurred = new Mat(), edges = new Mat();
        GaussianBlur(gray, blurred, new Size(3, 3), 0);
        Canny(blurred, edges, 60, 160);
        features[idx++] = (float) density(edges, new Rect(0, 0, NORM_W, NORM_H));
        int half = NORM_H / 2, third = NORM_W / 3;
        features[idx++] = (float) density(edges, new Rect(0, 0, third, half));
        features[idx++] = (float) density(edges, new Rect(third, 0, third, half));
        features[idx++] = (float) density(edges, new Rect(2 * third, 0, NORM_W - 2 * third, half));

        // 4) Media y desviación de S y V, escaladas a [0,1] (4)
        double[] sStats = meanStd(channels.get(1));
        double[] vStats = meanStd(channels.get(2));
        features[idx++] = (float) (sStats[0] / 255.0);
        features[idx++] = (float) (sStats[1] / 255.0);
        features[idx++] = (float) (vStats[0] / 255.0);
        features[idx] = (float) (vStats[1] / 255.0);

        channels.close();
        norm.release(); gray.release(); hsv.release(); blurred.release(); edges.release();
        return features;
    }

    private static double[] lbpHistogram(Mat gray) {
        UByteIndexer p = gray.createIndexer();
        int[] counts = new int[59];
        long total = 0;
        for (int y = 1; y < gray.rows() - 1; y++) {
            for (int x = 1; x < gray.cols() - 1; x++) {
                int c = p.get(y, x);
                int code = 0;
                code |= (p.get(y - 1, x - 1) >= c ? 1 : 0) << 7;
                code |= (p.get(y - 1, x) >= c ? 1 : 0) << 6;
                code |= (p.get(y - 1, x + 1) >= c ? 1 : 0) << 5;
                code |= (p.get(y, x + 1) >= c ? 1 : 0) << 4;
                code |= (p.get(y + 1, x + 1) >= c ? 1 : 0) << 3;
                code |= (p.get(y + 1, x) >= c ? 1 : 0) << 2;
                code |= (p.get(y + 1, x - 1) >= c ? 1 : 0) << 1;
                code |= (p.get(y, x - 1) >= c ? 1 : 0);
                counts[UNIFORM_MAP[code]]++;
                total++;
            }
        }
        p.release();
        double[] hist = new double[59];
        for (int i = 0; i < 59; i++) hist[i] = total == 0 ? 0 : (double) counts[i] / total;
        return hist;
    }

    private static double[] histogram8(Mat channel) {
        UByteIndexer p = channel.createIndexer();
        int[] counts = new int[8];
        long total = (long) channel.rows() * channel.cols();
        for (int y = 0; y < channel.rows(); y++) {
            for (int x = 0; x < channel.cols(); x++) {
                counts[p.get(y, x) >> 5]++;
            }
        }
        p.release();
        double[] hist = new double[8];
        for (int i = 0; i < 8; i++) hist[i] = (double) counts[i] / total;
        return hist;
    }

    private static double density(Mat edges, Rect r) {
        Mat patch = edges.apply(r);
        double d = (double) countNonZero(patch) / ((double) r.width() * r.height());
        patch.release();
        return d;
    }

    private static double[] meanStd(Mat channel) {
        UByteIndexer p = channel.createIndexer();
        long n = (long) channel.rows() * channel.cols();
        double sum = 0, sumSq = 0;
        for (int y = 0; y < channel.rows(); y++) {
            for (int x = 0; x < channel.cols(); x++) {
                int v = p.get(y, x);
                sum += v;
                sumSq += (double) v * v;
            }
        }
        p.release();
        double mean = sum / n;
        return new double[]{ mean, Math.sqrt(Math.max(0, sumSq / n - mean * mean)) };
    }

    private static int[] buildUniformMap() {
        int[] map = new int[256];
        int next = 0;
        for (int code = 0; code < 256; code++) {
            int transitions = 0;
            for (int bit = 0; bit < 8; bit++) {
                int a = (code >> bit) & 1;
                int b = (code >> ((bit + 1) % 8)) & 1;
                if (a != b) transitions++;
            }
            map[code] = transitions <= 2 ? next++ : -1;
        }
        for (int code = 0; code < 256; code++) {
            if (map[code] == -1) map[code] = 58;   // no-uniformes al bin 58
        }
        return map;
    }
}
