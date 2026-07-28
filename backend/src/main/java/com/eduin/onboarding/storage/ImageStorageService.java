package com.eduin.onboarding.storage;

import com.eduin.onboarding.catalog.DocumentSide;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Guarda las imágenes capturadas en disco: captured-documents/{sessionId}/{side}.jpg
 * La base de datos guarda solo la ruta y el hash SHA-256.
 */
@Service
public class ImageStorageService {

    private final Path baseDir;

    public ImageStorageService(@Value("${app.storage.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    public StoredImage store(UUID sessionId, DocumentSide side, byte[] imageBytes) {
        try {
            Path sessionDir = baseDir.resolve(sessionId.toString());
            Files.createDirectories(sessionDir);
            Path imagePath = sessionDir.resolve(side.name() + ".jpg");
            Files.write(imagePath, imageBytes);
            return new StoredImage(imagePath.toString(), sha256(imageBytes));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la imagen de la sesión " + sessionId, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record StoredImage(String path, String sha256) {
    }
}
