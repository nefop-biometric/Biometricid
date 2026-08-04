package com.eduin.onboarding.authenticity.ml;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_ml.SVM;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;

/**
 * Clasificador entrenado de foto sobrepuesta (montaje) para COL_CC_OLD.
 * SVM lineal sobre embeddings MobileNetV2 (o características clásicas) de la
 * zona del retrato. Se entrena con MontageTrainerTest y el dataset local
 * ml-dataset/ (capturas reales, NO se sube al repo).
 *
 * Métricas del modelo publicado (2026-08-03, 62 muestras, leave-one-out):
 * 4/5 montajes detectados, 0/57 falsos positivos. El tipo de característica
 * (app.authenticity.montage-features) DEBE coincidir con el del entrenamiento.
 *
 * Si falta el modelo (o el ONNX en modo dnn), el clasificador queda
 * deshabilitado y el análisis continúa sin este chequeo.
 */
@Slf4j
@Service
public class MontageClassifier {

    public static final float LABEL_MONTAGE = 1f;
    public static final float LABEL_AUTHENTIC = 0f;

    private final PhotoZoneLocator zoneLocator;
    private final SVM model;
    private final DnnEmbedder embedder;   // null en modo classic

    public MontageClassifier(PhotoZoneLocator zoneLocator,
                             @Value("${app.authenticity.montage-model:models/montage-svm-dnn.yml}") String modelPath,
                             @Value("${app.authenticity.montage-features:dnn}") String featureType,
                             @Value("${app.authenticity.montage-onnx:models/dnn/mobilenetv2-7.onnx}") String onnxPath) {
        this.zoneLocator = zoneLocator;

        SVM loadedModel = null;
        DnnEmbedder loadedEmbedder = null;
        try {
            if (!Files.exists(Path.of(modelPath))) {
                log.warn("Modelo de montaje no encontrado en {} — chequeo ML deshabilitado", modelPath);
            } else if ("dnn".equals(featureType) && !Files.exists(Path.of(onnxPath))) {
                log.warn("ONNX no encontrado en {} — chequeo ML deshabilitado", onnxPath);
            } else {
                loadedModel = SVM.load(modelPath);
                if ("dnn".equals(featureType)) {
                    loadedEmbedder = new DnnEmbedder(onnxPath);
                }
                log.info("Modelo de montaje cargado: {} (features={})", modelPath, featureType);
            }
        } catch (Exception e) {
            log.error("No se pudo cargar el modelo de montaje: {}", e.getMessage());
            loadedModel = null;
            loadedEmbedder = null;
        }
        this.model = loadedModel;
        this.embedder = loadedEmbedder;
    }

    public boolean isAvailable() {
        return model != null;
    }

    /**
     * true = el retrato parece una foto sobrepuesta (montaje).
     * Optional.empty() si el modelo no está disponible o la predicción falla.
     */
    public Optional<Boolean> isMontage(Mat document) {
        if (model == null) {
            return Optional.empty();
        }
        Rect zone = null;
        Mat sample = null;
        try {
            zone = zoneLocator.locate(document);
            float[] features = embedder != null
                    ? embedder.embed(document, zone)
                    : PhotoZoneFeatures.extract(document, zone);
            sample = new Mat(1, features.length, CV_32F);
            org.bytedeco.javacpp.indexer.FloatIndexer fi = sample.createIndexer();
            for (int i = 0; i < features.length; i++) fi.put(0, i, features[i]);
            fi.release();
            float label = model.predict(sample);
            return Optional.of(label == LABEL_MONTAGE);
        } catch (Exception e) {
            log.warn("Predicción de montaje falló: {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (sample != null) sample.release();
            if (zone != null) zone.close();
        }
    }
}
