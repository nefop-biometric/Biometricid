package com.eduin.onboarding.authenticity;

import com.eduin.onboarding.authenticity.ml.MontageClassifier;
import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import com.eduin.onboarding.authenticity.model.DocumentType;
import com.eduin.onboarding.authenticity.model.VerificationResult;
import com.eduin.onboarding.authenticity.service.DocumentCropService;
import com.eduin.onboarding.authenticity.service.analyzer.ImageLoader;
import com.eduin.onboarding.authenticity.service.analyzer.MrzAnalyzer;
import com.eduin.onboarding.authenticity.service.analyzer.PhotocopyDetector;
import com.eduin.onboarding.authenticity.service.analyzer.RecaptureDetector;
import com.eduin.onboarding.authenticity.service.analyzer.TamperingDetector;
import com.eduin.onboarding.authenticity.service.validator.DocumentStructureValidator;
import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import com.eduin.onboarding.processing.AuthenticityAnalyzer;
import com.eduin.onboarding.processing.AuthenticityResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Análisis de autenticidad por cara, portado del true-document-backend:
 * recorte del documento → recaptura + fotocopia + manipulación (rostro Haar +
 * anillo) + estructura → score ponderado normalizado con REGLA DE VETO
 * (crítico &lt; 0.60 en cards / &lt; 0.35 en pasaportes).
 *
 * La consolidación entre caras (regla de peor cara) vive en el módulo decision:
 * aquí solo se analiza UNA cara.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenCvAuthenticityAnalyzer implements AuthenticityAnalyzer {

    private final RecaptureDetector recaptureDetector;
    private final TamperingDetector tamperingDetector;
    private final PhotocopyDetector photocopyDetector;
    private final DocumentStructureValidator structureValidator;
    private final MrzAnalyzer mrzAnalyzer;
    private final ImageLoader imageLoader;
    private final DocumentCropService cropService;
    private final MontageClassifier montageClassifier;

    @Value("${app.authenticity.threshold:0.65}")
    private double authenticityThreshold;

    /**
     * Chequeo ML de foto sobrepuesta (COL_CC_OLD). DESHABILITADO por defecto:
     * solo activarlo cuando un modelo re-entrenado apruebe la validación
     * leave-one-out (ver MontageTrainerTest). El resultado ML manda a REVIEW
     * (cap 0.65), nunca a rechazo directo.
     */
    @Value("${app.authenticity.montage-ml.enabled:false}")
    private boolean montageMlEnabled;

    @Value("${app.authenticity.weights.recapture:0.30}")
    private double weightRecapture;

    @Value("${app.authenticity.weights.tampering:0.35}")
    private double weightTampering;

    @Value("${app.authenticity.weights.structure:0.35}")
    private double weightStructure;

    @Override
    public AuthenticityResult analyze(byte[] image, DocumentTypeSpec type, DocumentSide side) {
        try {
            return doAnalyze(image, type, side);
        } catch (Exception e) {
            // El análisis de autenticidad no debe tumbar el procesamiento de la cara:
            // sin resultado (null) la decisión final queda en REVIEW, nunca en APPROVED.
            log.error("Authenticity analysis failed for {} {}: {}", type.code(), side, e.getMessage(), e);
            return null;
        }
    }

    private AuthenticityResult doAnalyze(byte[] rawBytes, DocumentTypeSpec type, DocumentSide side)
            throws Exception {

        DocumentType docType = DocumentType.valueOf(type.code());
        com.eduin.onboarding.authenticity.model.DocumentSide oldSide =
                side == DocumentSide.BACK
                        ? com.eduin.onboarding.authenticity.model.DocumentSide.BACK
                        : com.eduin.onboarding.authenticity.model.DocumentSide.FRONT;

        Mat fullFrame = imageLoader.load(rawBytes, "_" + side.name() + ".jpg");

        // Recortar el documento del frame: los analizadores asumen que la imagen
        // ES el documento; el fondo de la captura desalinea los análisis espaciales.
        Mat mat = fullFrame;
        byte[] analysisBytes = rawBytes;
        Rect docRect = cropService.detectDocument(fullFrame);
        if (docRect != null) {
            mat = new Mat(fullFrame, docRect).clone();
            fullFrame.release();
            docRect.close();
            analysisBytes = encodeJpeg(mat);
        }

        List<AnalysisDetail> analyses = new ArrayList<>();
        boolean mlSaysMontage = false;
        try {
            analyses.add(recaptureDetector.analyze(mat));
            analyses.add(photocopyDetector.analyze(mat));
            analyses.add(tamperingDetector.analyze(mat, analysisBytes, docType));
            analyses.add(structureValidator.validate(mat, docType, oldSide));

            if (montageMlEnabled && docType == DocumentType.COL_CC_OLD
                    && side == DocumentSide.FRONT && montageClassifier.isAvailable()) {
                mlSaysMontage = montageClassifier.isMontage(mat).orElse(false);
                if (mlSaysMontage) {
                    analyses.add(AnalysisDetail.builder()
                            .analyzer("PHOTO_SUBSTITUTION_ML")
                            .score(0.4)
                            .passed(false)
                            .verdict("El clasificador entrenado marca el retrato como foto sobrepuesta")
                            .build());
                }
            }
        } finally {
            mat.release();
        }

        // Suma ponderada normalizada (los pesos comparten valores y suman > 1.0)
        double totalWeight = analyses.stream()
                .mapToDouble(a -> analyzerWeight(a.getAnalyzer()))
                .sum();
        double sideScore = analyses.stream()
                .mapToDouble(a -> a.getScore() * analyzerWeight(a.getAnalyzer()))
                .sum() / Math.max(totalWeight, 0.001);

        // Regla de veto: un detector crítico reprobado arrastra el score de la cara.
        // Cards: 0.60 (+0.05 de margen). Pasaportes: 0.35 (zona fotográfica genera
        // falsos positivos).
        boolean isPassport = docType.isPassport();
        double vetoThreshold = isPassport ? 0.35 : 0.60;
        boolean veto = false;
        for (AnalysisDetail a : analyses) {
            boolean critical = "RECAPTURE_DETECTION".equals(a.getAnalyzer())
                    || "TAMPERING_DETECTION".equals(a.getAnalyzer())
                    || "PHOTOCOPY_DETECTION".equals(a.getAnalyzer());
            if (critical && a.getScore() < vetoThreshold) {
                sideScore = Math.min(sideScore, a.getScore() + (isPassport ? 0.0 : 0.05));
                veto = true;
            }
        }

        // MRZ crítica para pasaportes (se analiza sobre la cara única del pasaporte)
        if (isPassport && side == DocumentSide.FRONT) {
            Mat mrzMat = imageLoader.load(rawBytes, "_mrz.jpg");
            VerificationResult.MrzResult mrz;
            try {
                mrz = mrzAnalyzer.analyzeMrz(mrzMat);
            } finally {
                mrzMat.release();
            }
            if (mrz != null) {
                double mrzScore = mrz.isValid() ? 1.0 : (mrz.isDetected() ? 0.4 : 0.1);
                sideScore = (sideScore * 0.65) + (mrzScore * 0.35);
                if (mrz.isWrongFormat()) {
                    // MRZ de OTRO tipo de documento → no es el pasaporte declarado
                    sideScore = Math.min(sideScore, 0.30);
                    veto = true;
                } else if (!mrz.isDetected()) {
                    // Pasaporte sin MRZ legible nunca aprueba automáticamente
                    sideScore = Math.min(sideScore, 0.60);
                }
                analyses.add(AnalysisDetail.builder()
                        .analyzer("MRZ_PRESENCE")
                        .score(mrzScore)
                        .passed(mrz.isValid())
                        .verdict(mrz.isValid() ? "MRZ válida"
                                : mrz.isDetected() ? "MRZ detectada con errores" : "MRZ no detectada")
                        .build());
            }
        }

        // El chequeo ML manda a REVIEW (cap 0.65, bajo el umbral de decisión 0.70)
        // sin activar veto: con dataset pequeño, un falso positivo no debe rechazar.
        if (mlSaysMontage) {
            sideScore = Math.min(sideScore, 0.65);
        }

        sideScore = Math.max(0.0, Math.min(1.0, sideScore));

        List<AuthenticityResult.Check> checks = analyses.stream()
                .map(a -> new AuthenticityResult.Check(a.getAnalyzer(), round(a.getScore()), a.isPassed()))
                .toList();

        return new AuthenticityResult(round(sideScore), veto, checks);
    }

    private double analyzerWeight(String analyzer) {
        return switch (analyzer) {
            case "RECAPTURE_DETECTION" -> weightRecapture;
            case "PHOTOCOPY_DETECTION" -> weightRecapture;   // mismo peso que recaptura
            case "TAMPERING_DETECTION" -> weightTampering;
            case "DOCUMENT_STRUCTURE" -> weightStructure;
            default -> 0.25;
        };
    }

    private static double round(double val) {
        return Math.round(val * 1000.0) / 1000.0;
    }

    /** Re-codifica el recorte a JPEG (calidad alta) para los análisis basados en bytes (ELA). */
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
