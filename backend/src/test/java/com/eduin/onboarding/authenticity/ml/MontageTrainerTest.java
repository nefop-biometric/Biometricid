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
        String featureType = System.getProperty("ml.features", "classic");

        ImageLoader loader = new ImageLoader();
        DocumentCropService cropService = new DocumentCropService();
        PhotoZoneLocator zoneLocator = new PhotoZoneLocator();
        DnnEmbedder embedder = "dnn".equals(featureType)
                ? new DnnEmbedder(System.getProperty("ml.onnx", "models/dnn/mobilenetv2-7.onnx"))
                : null;
        System.out.println("FEATURES: " + featureType);

        List<Sample> samples = new ArrayList<>();
        loadDir(Path.of(datasetDir, "montaje"), MontageClassifier.LABEL_MONTAGE,
                loader, cropService, zoneLocator, embedder, samples);
        loadDir(Path.of(datasetDir, "autentica"), MontageClassifier.LABEL_AUTHENTIC,
                loader, cropService, zoneLocator, embedder, samples);

        long montages = samples.stream().filter(s -> s.label == MontageClassifier.LABEL_MONTAGE).count();
        System.out.printf("DATASET: %d muestras (%d montaje, %d auténticas)%n",
                samples.size(), montages, samples.size() - montages);
        if (samples.size() < 6 || montages == 0 || montages == samples.size()) {
            System.out.println("Dataset insuficiente para entrenar.");
            return;
        }

        // ── Barrido de peso de clase montaje con evaluación leave-one-out ────
        double[] weightCandidates = { 1.0, 2.0, 3.0, 5.0, 8.0, 11.4 };
        for (double w : weightCandidates) {
            int tp = 0, tn = 0, fp = 0, fn = 0;
            List<String> failures = new ArrayList<>();
            for (int held = 0; held < samples.size(); held++) {
                List<Sample> train = new ArrayList<>(samples);
                Sample test = train.remove(held);
                SVM svm = fitSvm(train, w);
                float predicted = predict(svm, test.features);
                boolean isMontage = test.label == MontageClassifier.LABEL_MONTAGE;
                boolean saysMontage = predicted == MontageClassifier.LABEL_MONTAGE;
                if (isMontage && saysMontage) tp++;
                else if (!isMontage && !saysMontage) tn++;
                else if (!isMontage) fp++;
                else { fn++; failures.add(test.name); }
                svm.close();
            }
            System.out.printf("LOO w=%.1f: aciertos=%d/%d  montajesDetectados=%d/%d  falsosPositivos=%d/%d  (FN: %s)%n",
                    w, tp + tn, samples.size(), tp, tp + fn, fp, tn + fp, String.join(",", failures));
        }

        double finalWeight = Double.parseDouble(System.getProperty("ml.weight", "3.0"));
        System.out.printf("PESO FINAL: %.1f%n", finalWeight);

        // ── Modelo final con todas las muestras ──────────────────────────────
        SVM finalModel = fitSvm(samples, finalWeight);
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
                                DnnEmbedder embedder, List<Sample> out) throws Exception {
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
                float[] features = embedder != null
                        ? embedder.embed(doc, zone)
                        : PhotoZoneFeatures.extract(doc, zone);
                out.add(new Sample(f.getFileName().toString(), label, features));
                zone.close();
                doc.release();
            }
        }
    }

    private static SVM fitSvm(List<Sample> train, double montageWeight) {
        int dims = train.get(0).features.length;
        Mat features = new Mat(train.size(), dims, CV_32F);
        Mat labels = new Mat(train.size(), 1, CV_32S);
        FloatIndexer fi = features.createIndexer();
        org.bytedeco.javacpp.indexer.IntIndexer li = labels.createIndexer();
        for (int i = 0; i < train.size(); i++) {
            for (int j = 0; j < dims; j++) {
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
        // Peso de la clase montaje: sin esto, con clases desbalanceadas
        // (pocos montajes, muchas auténticas) el SVM predice siempre la mayoritaria.
        if (montageWeight > 0) {
            Mat weights = new Mat(2, 1, CV_32F);
            FloatIndexer wi = weights.createIndexer();
            wi.put(0, 0, 1.0f);                       // clase 0 = auténtica
            wi.put(1, 0, (float) montageWeight);      // clase 1 = montaje
            wi.release();
            svm.setClassWeights(weights);
        }
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
