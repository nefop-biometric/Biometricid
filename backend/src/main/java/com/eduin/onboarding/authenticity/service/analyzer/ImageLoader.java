package com.eduin.onboarding.authenticity.service.analyzer;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ImageLoader {

    public Mat load(MultipartFile file) throws IOException {
        Path tmp = Files.createTempFile("tdoc_", "_" + file.getOriginalFilename());
        try {
            file.transferTo(tmp.toFile());
            Mat mat = opencv_imgcodecs.imread(tmp.toString(), opencv_imgcodecs.IMREAD_COLOR);
            if (mat.empty()) {
                throw new IllegalArgumentException("No se pudo decodificar la imagen: " + file.getOriginalFilename());
            }
            return mat;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Mat load(byte[] bytes, String hint) throws IOException {
        Path tmp = Files.createTempFile("tdoc_", hint);
        try {
            Files.write(tmp, bytes);
            Mat mat = opencv_imgcodecs.imread(tmp.toString(), opencv_imgcodecs.IMREAD_COLOR);
            if (mat.empty()) {
                throw new IllegalArgumentException("No se pudo decodificar la imagen");
            }
            return mat;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
