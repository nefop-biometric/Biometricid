package com.eduin.onboarding.ocr.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Slf4j
@Service
public class ImagePreprocessorService {

    /**
     * Preprocesa la imagen para mejorar la tasa de éxito del OCR.
     * Pipeline: escala de grises → ecualización de contraste local → denoising leve.
     *
     * NOTA: deliberadamente NO se aplica umbralización binaria (adaptiveThreshold/Otsu)
     * aquí. Tesseract 5 (motor LSTM) hace su propia binarización interna optimizada
     * y suele funcionar peor si se le entrega una imagen ya binarizada a mano —
     * en documentos con fondos con textura/degradado de color (marcas de agua,
     * hologramas, fondos celestes con figuras) la umbralización adaptativa
     * convertía esas texturas en ruido binario que enterraba el texto real,
     * destruyendo la tasa de reconocimiento (confirmado con DNI Perú/Panamá/Ecuador,
     * cuyos fondos con marca de agua quedaban ilegibles tras la binarización previa).
     */
    public BufferedImage preprocess(byte[] imageBytes) throws IOException {
        Mat src = imdecode(
                new org.bytedeco.opencv.opencv_core.Mat(imageBytes),
                IMREAD_COLOR
        );

        if (src.empty()) {
            // Fallback: devolver imagen original como BufferedImage
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }

        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);

        // Escalar a al menos 300 DPI equivalente (mínimo 1800px en el lado largo)
        gray = ensureMinResolution(gray, 1800);

        // CLAHE: normaliza contraste local sin generar artefactos binarios duros
        Mat equalized = new Mat();
        var clahe = org.bytedeco.opencv.global.opencv_imgproc.createCLAHE(2.0, new Size(8, 8));
        clahe.apply(gray, equalized);

        // Denoising muy leve (preserva bordes de las letras)
        Mat denoised = new Mat();
        org.bytedeco.opencv.global.opencv_photo.fastNlMeansDenoising(equalized, denoised, 5, 15, 5);

        return matToBufferedImage(denoised);
    }

    /**
     * Preprocesamiento específico para zona MRZ (binarización fuerte, recorte inferior).
     */
    public BufferedImage preprocessMrz(byte[] imageBytes) throws IOException {
        Mat src = imdecode(
                new org.bytedeco.opencv.opencv_core.Mat(imageBytes),
                IMREAD_COLOR
        );

        if (src.empty()) {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }

        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);
        gray = ensureMinResolution(gray, 1800);

        // Recortar el tercio inferior donde suele estar la MRZ
        int mrzStartRow = (int) (gray.rows() * 0.65);
        org.bytedeco.opencv.opencv_core.Rect mrzRoi =
                new org.bytedeco.opencv.opencv_core.Rect(0, mrzStartRow, gray.cols(), gray.rows() - mrzStartRow);
        Mat mrzRegion = new Mat(gray, mrzRoi);

        // Binarización dura para OCR-B
        Mat thresh = new Mat();
        threshold(mrzRegion, thresh, 0, 255, THRESH_BINARY + THRESH_OTSU);

        return matToBufferedImage(thresh);
    }

    private Mat ensureMinResolution(Mat mat, int minLongSide) {
        int longSide = Math.max(mat.cols(), mat.rows());
        if (longSide < minLongSide) {
            double scale = (double) minLongSide / longSide;
            Mat resized = new Mat();
            resize(mat, resized, new Size((int) (mat.cols() * scale), (int) (mat.rows() * scale)));
            return resized;
        }
        return mat;
    }

    private BufferedImage matToBufferedImage(Mat mat) throws IOException {
        BytePointer bp = new BytePointer();
        imencode(".png", mat, bp);
        byte[] bytes = new byte[(int) bp.limit()];
        bp.get(bytes);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
