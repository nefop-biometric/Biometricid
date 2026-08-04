package com.eduin.onboarding.authenticity.ml;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

/**
 * Ubica la zona del retrato en un documento (recortado): rostro Haar expandido
 * a cabeza+hombros, con fallback a la zona fija de la cédula amarilla.
 * Compartido entre el entrenador del modelo de montaje y la inferencia.
 */
@Component
public class PhotoZoneLocator {

    /** Zona fija del retrato en COL_CC_OLD como fracción [x, y, w, h] del documento. */
    private static final double[] CC_OLD_ZONE = { 0.48, 0.12, 0.32, 0.80 };

    private volatile CascadeClassifier faceCascade;
    private volatile boolean cascadeInitTried = false;

    private CascadeClassifier cascade() {
        if (!cascadeInitTried) {
            synchronized (this) {
                if (!cascadeInitTried) {
                    try (InputStream in = getClass().getResourceAsStream(
                            "/cascades/haarcascade_frontalface_default.xml")) {
                        if (in != null) {
                            Path tmp = Files.createTempFile("haar_face_ml", ".xml");
                            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                            CascadeClassifier c = new CascadeClassifier(tmp.toString());
                            if (!c.empty()) faceCascade = c;
                        }
                    } catch (Exception ignored) { }
                    cascadeInitTried = true;
                }
            }
        }
        return faceCascade;
    }

    /** Zona del retrato (siempre dentro de la imagen; nunca null). */
    public Rect locate(Mat image) {
        Rect face = largestFace(image);
        int px, py, pw, ph;
        if (face != null) {
            px = face.x() - (int) (face.width() * 0.40);
            py = face.y() - (int) (face.height() * 0.55);
            pw = (int) (face.width() * 1.80);
            ph = (int) (face.height() * 2.60);
            face.close();
        } else {
            px = (int) (CC_OLD_ZONE[0] * image.cols());
            py = (int) (CC_OLD_ZONE[1] * image.rows());
            pw = (int) (CC_OLD_ZONE[2] * image.cols());
            ph = (int) (CC_OLD_ZONE[3] * image.rows());
        }
        px = Math.max(0, Math.min(px, image.cols() - 2));
        py = Math.max(0, Math.min(py, image.rows() - 2));
        pw = Math.max(2, Math.min(pw, image.cols() - px));
        ph = Math.max(2, Math.min(ph, image.rows() - py));
        return new Rect(px, py, pw, ph);
    }

    public boolean faceDetected(Mat image) {
        Rect face = largestFace(image);
        if (face == null) return false;
        face.close();
        return true;
    }

    private Rect largestFace(Mat image) {
        CascadeClassifier cascade = cascade();
        if (cascade == null) return null;
        Mat gray = new Mat();
        RectVector faces = new RectVector();
        try {
            cvtColor(image, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);
            int minDim = Math.min(image.cols(), image.rows());
            cascade.detectMultiScale(gray, faces, 1.1, 5, 0,
                    new Size(minDim / 10, minDim / 10), new Size());
            Rect best = null;
            long bestArea = 0;
            for (long i = 0; i < faces.size(); i++) {
                Rect f = faces.get(i);
                long area = (long) f.width() * f.height();
                if (area > bestArea) {
                    bestArea = area;
                    best = new Rect(f);
                }
            }
            return best;
        } finally {
            gray.release();
            faces.close();
        }
    }
}
