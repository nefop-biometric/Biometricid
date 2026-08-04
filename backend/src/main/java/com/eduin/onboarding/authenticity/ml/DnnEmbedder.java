package com.eduin.onboarding.authenticity.ml;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromONNX;

/**
 * Embeddings de la zona del retrato con MobileNetV2 (ONNX, OpenCV DNN).
 * El vector de logits (1000-d, normalizado L2) sirve como característica para
 * el SVM de montaje — mucho más expresivo que las características clásicas.
 *
 * La red no es thread-safe: embed() se sincroniza (mismo patrón que Tesseract).
 */
public class DnnEmbedder {

    public static final int DIMENSIONS = 1000;
    private static final int INPUT_SIZE = 224;

    private final Net net;

    public DnnEmbedder(String onnxPath) {
        if (!Files.exists(Path.of(onnxPath))) {
            throw new IllegalStateException("Modelo ONNX no encontrado: " + onnxPath);
        }
        this.net = readNetFromONNX(onnxPath);
    }

    public float[] embed(Mat bgr, Rect zone) {
        Mat zoneMat = new Mat(bgr, zone).clone();
        try {
            return embed(zoneMat);
        } finally {
            zoneMat.release();
        }
    }

    public synchronized float[] embed(Mat bgrImage) {
        // Normalización tipo ImageNet (media por canal, escala única): lo que importa
        // para el SVM es que entrenamiento e inferencia usen EXACTAMENTE la misma.
        Mat blob = blobFromImage(bgrImage, 1.0 / 58.0,
                new Size(INPUT_SIZE, INPUT_SIZE),
                new Scalar(103.53, 116.28, 123.675, 0.0),   // media BGR
                true, false, org.bytedeco.opencv.global.opencv_core.CV_32F);
        Mat out = null;
        try {
            net.setInput(blob);
            out = net.forward();
            FloatIndexer fi = out.createIndexer();
            float[] embedding = new float[DIMENSIONS];
            double norm = 0;
            for (int i = 0; i < DIMENSIONS; i++) {
                embedding[i] = fi.get(0, i);
                norm += (double) embedding[i] * embedding[i];
            }
            fi.release();
            norm = Math.sqrt(Math.max(norm, 1e-9));
            for (int i = 0; i < DIMENSIONS; i++) {
                embedding[i] /= (float) norm;
            }
            return embedding;
        } finally {
            blob.release();
            if (out != null) out.release();
        }
    }
}
