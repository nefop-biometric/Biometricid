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
 * Modelo SVM sobre características de textura/color de la zona del retrato
 * (ver PhotoZoneFeatures). Se entrena con MontageTrainerTest y el dataset
 * local ml-dataset/ (capturas reales de Eduin, NO se sube al repo).
 *
 * Si el archivo de modelo no existe, el clasificador queda deshabilitado y
 * el análisis de autenticidad continúa sin este chequeo.
 */
@Slf4j
@Service
public class MontageClassifier {

    public static final float LABEL_MONTAGE = 1f;
    public static final float LABEL_AUTHENTIC = 0f;

    private final PhotoZoneLocator zoneLocator;
    private final SVM model;

    public MontageClassifier(PhotoZoneLocator zoneLocator,
                             @Value("${app.authenticity.montage-model:models/montage-svm.yml}") String modelPath) {
        this.zoneLocator = zoneLocator;
        SVM loaded = null;
        try {
            if (Files.exists(Path.of(modelPath))) {
                loaded = SVM.load(modelPath);
                log.info("Modelo de montaje cargado: {} ({} dims)", modelPath, PhotoZoneFeatures.DIMENSIONS);
            } else {
                log.warn("Modelo de montaje no encontrado en {} — chequeo ML deshabilitado", modelPath);
            }
        } catch (Exception e) {
            log.error("No se pudo cargar el modelo de montaje {}: {}", modelPath, e.getMessage());
        }
        this.model = loaded;
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
            float[] features = PhotoZoneFeatures.extract(document, zone);
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
