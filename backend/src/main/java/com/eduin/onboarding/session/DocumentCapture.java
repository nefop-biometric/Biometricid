package com.eduin.onboarding.session;

import com.eduin.onboarding.catalog.DocumentSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_capture")
public class DocumentCapture {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private DocumentSide side;

    @Column(name = "image_path", nullable = false, length = 512)
    private String imagePath;

    @Column(name = "image_sha256", nullable = false, length = 64)
    private String imageSha256;

    @Column(name = "detected_type", length = 32)
    private String detectedType;

    @Column(name = "classification_confidence")
    private Double classificationConfidence;

    @Column(name = "ocr_json")
    private String ocrJson;

    @Column(name = "authenticity_json")
    private String authenticityJson;

    @Column(name = "authenticity_score")
    private Double authenticityScore;

    @Column(name = "veto")
    private Boolean veto;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentCapture() {
        // JPA
    }

    public DocumentCapture(UUID id, UUID sessionId, DocumentSide side, String imagePath,
                           String imageSha256, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.side = side;
        this.imagePath = imagePath;
        this.imageSha256 = imageSha256;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public DocumentSide getSide() {
        return side;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getImageSha256() {
        return imageSha256;
    }

    public void setImageSha256(String imageSha256) {
        this.imageSha256 = imageSha256;
    }

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public Double getClassificationConfidence() {
        return classificationConfidence;
    }

    public void setClassificationConfidence(Double classificationConfidence) {
        this.classificationConfidence = classificationConfidence;
    }

    public String getOcrJson() {
        return ocrJson;
    }

    public void setOcrJson(String ocrJson) {
        this.ocrJson = ocrJson;
    }

    public String getAuthenticityJson() {
        return authenticityJson;
    }

    public void setAuthenticityJson(String authenticityJson) {
        this.authenticityJson = authenticityJson;
    }

    public Double getAuthenticityScore() {
        return authenticityScore;
    }

    public void setAuthenticityScore(Double authenticityScore) {
        this.authenticityScore = authenticityScore;
    }

    public Boolean getVeto() {
        return veto;
    }

    public void setVeto(Boolean veto) {
        this.veto = veto;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
