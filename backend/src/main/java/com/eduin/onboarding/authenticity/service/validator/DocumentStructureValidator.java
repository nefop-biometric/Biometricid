package com.eduin.onboarding.authenticity.service.validator;

import com.eduin.onboarding.authenticity.model.AnalysisDetail;
import com.eduin.onboarding.authenticity.model.DocumentSide;
import com.eduin.onboarding.authenticity.model.DocumentType;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Valida la estructura visual de cada tipo de documento:
 * - Relación de aspecto (ISO 7810 ID-1: 85.6×53.98mm → 1.586:1)
 * - Perfil de color dominante (CC OLD=amarilla, CC NEW=blanca, etc.)
 * - Contraste mínimo (imagen no en blanco ni quemada)
 */
@Component
public class DocumentStructureValidator {

    private static final double ASPECT_RATIO_TOLERANCE = 0.10;
    private static final double ID1_ASPECT = 85.6 / 53.98; // ~1.586

    public AnalysisDetail validate(Mat image, DocumentType docType, DocumentSide side) {
        List<String> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        double aspectScore   = validateAspectRatio(image, docType, findings);
        double colorScore    = validateColorProfile(image, docType, findings, warnings);
        double contrastScore = validateContrast(image, findings);

        double score = aspectScore * 0.30 + colorScore * 0.40 + contrastScore * 0.30;

        return AnalysisDetail.builder()
                .analyzer("DOCUMENT_STRUCTURE")
                .score(round(score))
                .passed(score >= 0.60)
                .verdict(score >= 0.60
                        ? "Estructura consistente con " + docType.getDescription()
                        : "Estructura no coincide con " + docType.getDescription())
                .findings(findings)
                .warnings(warnings)
                .build();
    }

    // ── Relación de aspecto ───────────────────────────────────────────────────

    private double validateAspectRatio(Mat image, DocumentType docType, List<String> findings) {
        if (docType.isPassport()) return 1.0;

        double ratio    = (double) image.cols() / image.rows();
        double diffNorm = Math.abs(ratio - ID1_ASPECT) / ID1_ASPECT;
        double diffRot  = Math.abs((1.0 / ratio) - ID1_ASPECT) / ID1_ASPECT;
        double diff     = Math.min(diffNorm, diffRot);

        if (diff > ASPECT_RATIO_TOLERANCE) {
            findings.add(String.format(
                    "Relación de aspecto inusual: %.2f (esperado ~%.2f ±10%%)", ratio, ID1_ASPECT));
            return Math.max(0.3, 1.0 - diff * 3);
        }
        return 1.0;
    }

    // ── Perfil de color ───────────────────────────────────────────────────────

    private double validateColorProfile(Mat image, DocumentType docType,
                                        List<String> findings, List<String> warnings) {
        Mat hsv   = new Mat();
        Mat meanM = new Mat();
        Mat stdM  = new Mat();
        cvtColor(image, hsv, COLOR_BGR2HSV);
        meanStdDev(hsv, meanM, stdM);

        double hue, sat, val;
        try (DoubleIndexer idx = meanM.createIndexer()) {
            hue = idx.get(0L);
            sat = idx.get(1L);
            val = idx.get(2L);
        } catch (Exception e) { hue = sat = val = 0; }

        hsv.release(); meanM.release(); stdM.release();

        if (val < 80) {
            findings.add(String.format(
                    "Imagen muy oscura (brillo=%.0f). Posible mala iluminación.", val));
            return 0.4;
        }

        double score = 1.0;
        switch (docType) {
            case COL_CC_OLD -> {
                boolean isYellow = hue >= 12 && hue <= 50 && sat > 30;
                if (!isYellow) {
                    findings.add(String.format(
                            "Cédula Amarilla (CC OLD) debe tener fondo amarillo/beige (hue=%.0f, sat=%.0f)", hue, sat));
                    score -= 0.3;
                }
            }
            case COL_CC_NEW, COL_CE, COL_TI, COL_PPT,
                 ESP_DNI_OLD, ESP_DNI_NEW,
                 ECU_DNI_OLD, ECU_DNI_NEW,
                 PER_DNI_OLD, PER_DNI_NEW,
                 PAN_DNI_OLD, PAN_DNI_NEW -> {
                if (sat > 70) {
                    warnings.add(String.format(
                            "Saturación alta (%.0f). Este documento debería ser principalmente blanco/gris.", sat));
                    score -= 0.15;
                }
            }
            default -> { /* pasaporte: sin validación de color */ }
        }
        return Math.max(0.2, score);
    }

    // ── Contraste mínimo ─────────────────────────────────────────────────────

    private double validateContrast(Mat image, List<String> findings) {
        Mat gray  = new Mat();
        Mat meanM = new Mat();
        Mat stdM  = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        meanStdDev(gray, meanM, stdM);

        double std;
        try (DoubleIndexer idx = stdM.createIndexer()) {
            std = idx.get(0L);
        } catch (Exception e) { std = 30; }

        gray.release(); meanM.release(); stdM.release();

        if (std < 20) {
            findings.add(String.format(
                    "Contraste muy bajo (stdDev=%.1f). Imagen sobreexpuesta, subexpuesta o en blanco.", std));
            return Math.max(0.1, std / 20.0);
        }
        return 1.0;
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
