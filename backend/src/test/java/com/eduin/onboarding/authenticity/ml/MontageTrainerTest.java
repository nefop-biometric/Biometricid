package com.eduin.onboarding.authenticity.ml;

import com.eduin.onboarding.authenticity.service.DocumentCropService;
import com.eduin.onboarding.authenticity.service.analyzer.ImageLoader;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.TermCriteria;
import org.bytedeco.opencv.opencv_ml.SVM;
import org.bytedeco.opencv.opencv_ml.TrainData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_32S;
import static org.bytedeco.opencv.global.opencv_ml.ROW_SAMPLE;

/**
 * Entrena y evalúa el clasificador de foto sobrepuesta (montaje).
 *
 * Uso:  mvn test -Dtest=MontageTrainerTest -Dml.dataset=ml-dataset -Dml.out=models/montage-svm.yml
 *
 * El dataset son dos carpetas: ml-dataset/montaje y ml-dataset/autentica, con
 * capturas de frente (idealmente tomadas por la propia app). La evaluación es
 * leave-one-out (con N pequeño es la única honesta) y el modelo final se
 * entrena con todas las muestras.
 */
class MontageTrainerTest {

    private record Sample(String name, float label, float[] features) {
    }

    @Test
    void trainAndEvaluate() throws Exception {
        String datasetDir = System.getProperty("ml.dataset");
        if (datasetDir == null) {
            System.out.println("(sin -Dml.dataset, entrenamiento omitido)");
            return;
        }
        String outPath = System.getProperty("ml.out", "models/montage-svm.yml");

        ImageLoader loader = new ImageLoader();
        DocumentCropService cropService = new DocumentCropService();
        PhotoZoneLocator zoneLocator = new PhotoZoneLocator();

        List<Sample> samples = new ArrayList<>();
        loadDir(Path.of(datasetDir, "montaje"), MontageClassifier.LABEL_MONTAGE,
                loader, cropService, zoneLocator, samples);
        loadDir(Path.of(datasetDir, "autentica"), MontageClassifier.LABEL_AUTHENTIC,
                loader, cropService, zoneLocator, samples);

        long montages = samples.stream().filter(s -> s.label == MontageClassifier.LABEL_MONTAGE).count();
        System.out.printf("DATASET: %d muestras (%d montaje, %d auténticas)%n",
                samples.size(), montages, samples.size() - montages);
        if (samples.size() < 6 || montages == 0 || montages == samples.size()) {
            System.out.println("Dataset insuficiente para entrenar.");
            return;
        }

        // ── Evaluación leave-one-out ─────────────────────────────────────────
        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (int held = 0; held < samples.size(); held++) {
            List<Sample> train = new ArrayList<>(samples);
            Sample test = train.remove(held);
            SVM svm = fitSvm(train);
            float predicted = predict(svm, test.features);
            boolean isMontage = test.label == MontageClassifier.LABEL_MONTAGE;
            boolean saysMontage = predicted == MontageClassifier.LABEL_MONTAGE;
            if (isMontage && saysMontage) tp++;
            else if (!isMontage && !saysMontage) tn++;
            else if (!isMontage) fp++;
            else fn++;
            if (isMontage != saysMontage) {
                System.out.printf("  LOO FALLO: %s (real=%s, predicho=%s)%n",
                        test.name, isMontage ? "montaje" : "auténtica",
                        saysMontage ? "montaje" : "auténtica");
            }
            svm.close();
        }
        System.out.printf("LOO: aciertos=%d/%d  TP=%d TN=%d FP=%d FN=%d%n",
                tp + tn, samples.size(), tp, tn, fp, fn);

        // ── Modelo final con todas las muestras ──────────────────────────────
        SVM finalModel = fitSvm(samples);
        Files.createDirectories(Path.of(outPath).toAbsolutePath().getParent());
        finalModel.save(outPath);
        System.out.println("MODELO GUARDADO: " + outPath);

        for (Sample s : samples) {
            float p = predict(finalModel, s.features);
            System.out.printf("  train-fit %-25s real=%s predicho=%s%n", s.name,
                    s.label == 1f ? "montaje" : "auténtica", p == 1f ? "montaje" : "auténtica");
        }
    }

    private static void loadDir(Path dir, float label, ImageLoader loader,
                                DocumentCropService cropService, PhotoZoneLocator zoneLocator,
                                List<Sample> out) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                if (!f.toString().toLowerCase().matches(".*\\.(jpg|jpeg|png)$")) continue;
                byte[] bytes = Files.readAllBytes(f);
                Mat full = loader.load(bytes, "_train.jpg");
                Mat doc = full;
                Rect docRect = cropService.detectDocument(full);
                if (docRect != null) {
                    doc = new Mat(full, docRect).clone();
                    full.release();
                    docRect.close();
                }
                Rect zone = zoneLocator.locate(doc);
                out.add(new Sample(f.getFileName().toString(), label,
                        PhotoZoneFeatures.extract(doc, zone)));
                zone.close();
                doc.release();
            }
        }
    }

    private static SVM fitSvm(List<Sample> train) {
        Mat features = new Mat(train.size(), PhotoZoneFeatures.DIMENSIONS, CV_32F);
        Mat labels = new Mat(train.size(), 1, CV_32S);
        FloatIndexer fi = features.createIndexer();
        org.bytedeco.javacpp.indexer.IntIndexer li = labels.createIndexer();
        for (int i = 0; i < train.size(); i++) {
            for (int j = 0; j < PhotoZoneFeatures.DIMENSIONS; j++) {
                fi.put(i, j, train.get(i).features[j]);
            }
            li.put(i, 0, (int) train.get(i).label);
        }
        fi.release();
        li.release();

        SVM svm = SVM.create();
        svm.setType(SVM.C_SVC);
        // Lineal y con C moderado: con decenas de muestras, un kernel RBF memoriza.
        svm.setKernel(SVM.LINEAR);
        svm.setC(1.0);
        svm.setTermCriteria(new TermCriteria(TermCriteria.MAX_ITER + TermCriteria.EPS, 10000, 1e-6));
        TrainData data = TrainData.create(features, ROW_SAMPLE, labels);
        svm.train(data);
        data.close();
        features.release();
        labels.release();
        return svm;
    }

    private static float predict(SVM svm, float[] featureVector) {
        Mat sample = new Mat(1, featureVector.length, CV_32F);
        FloatIndexer fi = sample.createIndexer();
        for (int i = 0; i < featureVector.length; i++) fi.put(0, i, featureVector[i]);
        fi.release();
        float label = svm.predict(sample);
        sample.release();
        return label;
    }
}
