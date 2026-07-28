package com.eduin.onboarding.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "onboarding_session")
public class OnboardingSession {

    @Id
    private UUID id;

    @Column(name = "document_type", nullable = false, length = 32)
    private String documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status;

    @Column(name = "geolocation_lat")
    private Double geolocationLat;

    @Column(name = "geolocation_lng")
    private Double geolocationLng;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "decision_outcome", length = 16)
    private String decisionOutcome;

    @Column(name = "decision_reasons")
    private String decisionReasons;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected OnboardingSession() {
        // JPA
    }

    public OnboardingSession(UUID id, String documentType, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.documentType = documentType;
        this.status = SessionStatus.CREATED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getDocumentType() {
        return documentType;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public Double getGeolocationLat() {
        return geolocationLat;
    }

    public void setGeolocationLat(Double geolocationLat) {
        this.geolocationLat = geolocationLat;
    }

    public Double getGeolocationLng() {
        return geolocationLng;
    }

    public void setGeolocationLng(Double geolocationLng) {
        this.geolocationLng = geolocationLng;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getDecisionOutcome() {
        return decisionOutcome;
    }

    public void setDecisionOutcome(String decisionOutcome) {
        this.decisionOutcome = decisionOutcome;
    }

    public String getDecisionReasons() {
        return decisionReasons;
    }

    public void setDecisionReasons(String decisionReasons) {
        this.decisionReasons = decisionReasons;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
