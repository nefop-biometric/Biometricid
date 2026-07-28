package com.eduin.onboarding.authenticity.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.stereotype.Component;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Detecta y recorta el documento dentro del frame capturado por la cámara.
 *
 * Las capturas de webcam incluyen el fondo alrededor del documento (mesa, manos,
 * etc.). Los analizadores (zona de foto, relación de aspecto, perfil de color)
 * asumen que la imagen ES el documento — sin este recorte, sus coordenadas
 * relativas caen desplazadas y los análisis pierden efectividad.
 */
@Slf4j
@Component
public class DocumentCropService {

    /** Fracción mínima/máxima del frame que debe ocupar el documento para confiar en la detección. */
    private static final double MIN_AREA_RATIO = 0.20;
    private static final double MAX_AREA_RATIO = 0.95;
    /** El contorno debe llenar al menos esta fracción de su bounding box (forma rectangular). */
    private static final double MIN_RECTANGULARITY = 0.75;

    /**
     * Detecta el documento como la mayor región clara sobre el fondo.
     * @return bounding box del documento, o null si no hay detección confiable
     *         (en ese caso el llamador debe usar el frame completo).
     */
    public Rect detectDocument(Mat image) {
        Mat gray = new Mat(), blur = new Mat(), binary = new Mat(), kernel = null;
        try {
            cvtColor(image, gray, COLOR_BGR2GRAY);
            GaussianBlur(gray, blur, new Size(5, 5), 0);
            threshold(blur, binary, 0, 255, THRESH_BINARY + THRESH_OTSU);
            kernel = getStructuringElement(MORPH_RECT, new Size(25, 25));
            morphologyEx(binary, binary, MORPH_CLOSE, kernel);

            MatVector contours = new MatVector();
            Mat hierarchy = new Mat();
            findContours(binary, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
            hierarchy.release();

            Rect best = null;
            double bestArea = 0;
            for (long i = 0; i < contours.size(); i++) {
                double area = contourArea(contours.get(i));
                if (area > bestArea) {
                    bestArea = area;
                    if (best != null) best.close();
                    best = boundingRect(contours.get(i));
                }
            }
            contours.close();

            if (best == null) return null;

            double imgArea  = (double) image.cols() * image.rows();
            double rectArea = (double) best.width() * best.height();

            if (rectArea / imgArea < MIN_AREA_RATIO || rectArea / imgArea > MAX_AREA_RATIO) {
                log.debug("Documento no recortado: área {}% fuera de rango",
                        Math.round(rectArea / imgArea * 100));
                best.close();
                return null;
            }
            if (bestArea / rectArea < MIN_RECTANGULARITY) {
                log.debug("Documento no recortado: contorno no rectangular ({}%)",
                        Math.round(bestArea / rectArea * 100));
                best.close();
                return null;
            }

            log.info("Documento detectado: [{}x{} en ({},{})] = {}% del frame",
                    best.width(), best.height(), best.x(), best.y(),
                    Math.round(rectArea / imgArea * 100));
            return best;

        } finally {
            gray.release(); blur.release(); binary.release();
            if (kernel != null) kernel.release();
        }
    }
}
