package com.eduin.onboarding.authenticity.ml;

import com.eduin.onboarding.authenticity.service.DocumentCropService;
import com.eduin.onboarding.authenticity.service.analyzer.ImageLoader;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reproduce EXACTAMENTE el camino del runtime (modelo cargado de disco,
 * misma localización de zona y embedding) sobre una imagen dada.
 * Uso: mvn test -Dtest=MontageRuntimeCheckTest -Dmc.image=...
 */
class MontageRuntimeCheckTest {

    @Test
    void checkImage() throws Exception {
        String imagePath = System.getProperty("mc.image");
        if (imagePath == null) {
            System.out.println("(sin -Dmc.image, omitido)");
            return;
        }
        PhotoZoneLocator zoneLocator = new PhotoZoneLocator();
        MontageClassifier classifier = new MontageClassifier(zoneLocator,
                "models/montage-svm-dnn.yml", "dnn", "models/dnn/mobilenetv2-7.onnx");
        System.out.println("DISPONIBLE: " + classifier.isAvailable());

        byte[] bytes = Files.readAllBytes(Path.of(imagePath));
        ImageLoader loader = new ImageLoader();
        DocumentCropService cropService = new DocumentCropService();
        Mat full = loader.load(bytes, "_check.jpg");
        Mat doc = full;
        Rect docRect = cropService.detectDocument(full);
        if (docRect != null) {
            doc = new Mat(full, docRect).clone();
            full.release();
            docRect.close();
        }
        System.out.println("PREDICCION isMontage=" + classifier.isMontage(doc));
        doc.release();
    }
}
