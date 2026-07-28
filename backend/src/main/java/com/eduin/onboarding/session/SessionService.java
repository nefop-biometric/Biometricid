package com.eduin.onboarding.session;

import com.eduin.onboarding.catalog.DocumentCatalog;
import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.catalog.DocumentTypeSpec;
import com.eduin.onboarding.common.error.ApiException;
import com.eduin.onboarding.common.error.ErrorCode;
import com.eduin.onboarding.decision.DecisionService;
import com.eduin.onboarding.processing.AuthenticityAnalyzer;
import com.eduin.onboarding.processing.AuthenticityResult;
import com.eduin.onboarding.processing.ClassificationResult;
import com.eduin.onboarding.processing.OcrEngine;
import com.eduin.onboarding.processing.OcrResult;
import com.eduin.onboarding.session.dto.CreateSessionRequest;
import com.eduin.onboarding.session.dto.CreateSessionResponse;
import com.eduin.onboarding.session.dto.SessionDetailResponse;
import com.eduin.onboarding.session.dto.SideResultResponse;
import com.eduin.onboarding.storage.ImageStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private final DocumentCatalog catalog;
    private final OnboardingSessionRepository sessionRepository;
    private final DocumentCaptureRepository captureRepository;
    private final ImageStorageService imageStorage;
    private final OcrEngine ocrEngine;
    private final AuthenticityAnalyzer authenticityAnalyzer;
    private final DecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;

    public SessionService(DocumentCatalog catalog,
                          OnboardingSessionRepository sessionRepository,
                          DocumentCaptureRepository captureRepository,
                          ImageStorageService imageStorage,
                          OcrEngine ocrEngine,
                          AuthenticityAnalyzer authenticityAnalyzer,
                          DecisionService decisionService,
                          ObjectMapper objectMapper,
                          @Value("${app.session.ttl-minutes}") long ttlMinutes) {
        this.catalog = catalog;
        this.sessionRepository = sessionRepository;
        this.captureRepository = captureRepository;
        this.imageStorage = imageStorage;
        this.ocrEngine = ocrEngine;
        this.authenticityAnalyzer = authenticityAnalyzer;
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
    }

    @Transactional
    public CreateSessionResponse createSession(CreateSessionRequest request) {
        DocumentTypeSpec spec = catalog.find(request.documentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                        "Tipo de documento no soportado: " + request.documentType()));

        Instant now = Instant.now();
        OnboardingSession session = new OnboardingSession(UUID.randomUUID(), spec.code(), now, now.plus(sessionTtl));
        if (request.metadata() != null) {
            if (request.metadata().geolocation() != null) {
                session.setGeolocationLat(request.metadata().geolocation().lat());
                session.setGeolocationLng(request.metadata().geolocation().lng());
            }
            session.setUserAgent(request.metadata().userAgent());
        }
        sessionRepository.save(session);

        return new CreateSessionResponse(session.getId(), spec.code(), spec.sides(),
                session.getStatus(), session.getExpiresAt());
    }

    @Transactional
    public SideResultResponse processSide(UUID sessionId, DocumentSide side, byte[] imageBytes, boolean replace) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ApiException(ErrorCode.IMAGE_EMPTY, "La imagen está vacía", sessionId);
        }

        OnboardingSession session = loadActiveSession(sessionId);
        DocumentTypeSpec spec = specOf(session);

        if (!spec.sides().contains(side)) {
            throw new ApiException(ErrorCode.INVALID_SIDE,
                    "El documento " + spec.code() + " no requiere la cara " + side, sessionId);
        }

        DocumentCapture existing = captureRepository.findBySessionIdAndSide(sessionId, side).orElse(null);
        if (existing != null && !replace) {
            throw new ApiException(ErrorCode.SIDE_ALREADY_PROCESSED,
                    "La cara " + side + " ya fue procesada. Use ?replace=true para recapturar.", sessionId);
        }

        ImageStorageService.StoredImage stored = imageStorage.store(sessionId, side, imageBytes);

        // Pipeline: OCR (clasificación + extracción en una pasada) → antifraude
        OcrEngine.SideOcrOutcome ocrOutcome = ocrEngine.process(imageBytes, spec, side);
        ClassificationResult classification = ocrOutcome.classification();
        if (classification != null && !classification.matchesSession()) {
            throw new ApiException(ErrorCode.DOCUMENT_TYPE_MISMATCH,
                    "La imagen corresponde a " + classification.detectedType()
                            + " pero la sesión es " + spec.code(),
                    sessionId, Map.of("detectedType", classification.detectedType()));
        }
        OcrResult ocr = ocrOutcome.ocr();
        AuthenticityResult authenticity = authenticityAnalyzer.analyze(imageBytes, spec, side);

        DocumentCapture capture = existing != null ? existing
                : new DocumentCapture(UUID.randomUUID(), sessionId, side, stored.path(), stored.sha256(), Instant.now());
        capture.setImagePath(stored.path());
        capture.setImageSha256(stored.sha256());
        capture.setCreatedAt(Instant.now());
        if (classification != null) {
            capture.setDetectedType(classification.detectedType());
            capture.setClassificationConfidence(classification.confidence());
        }
        capture.setOcrJson(toJson(ocr));
        if (authenticity != null) {
            capture.setAuthenticityJson(toJson(authenticity));
            capture.setAuthenticityScore(authenticity.score());
            capture.setVeto(authenticity.veto());
        }
        captureRepository.save(capture);

        updateSessionStatus(session, spec);

        return new SideResultResponse(side, classification, ocr, authenticity, session.getStatus());
    }

    @Transactional
    public SessionDetailResponse getSession(UUID sessionId) {
        OnboardingSession session = loadSession(sessionId);
        expireIfNeeded(session);

        List<DocumentCapture> captures = captureRepository.findBySessionId(sessionId);
        Map<DocumentSide, SessionDetailResponse.SideSummary> sides = new EnumMap<>(DocumentSide.class);
        for (DocumentCapture c : captures) {
            sides.put(c.getSide(), new SessionDetailResponse.SideSummary(
                    c.getDetectedType(), c.getClassificationConfidence(),
                    c.getAuthenticityScore(), c.getVeto(), c.getCreatedAt()));
        }

        SessionDetailResponse.Consolidated consolidated = null;
        if (session.getStatus() == SessionStatus.COMPLETED && !captures.isEmpty()) {
            DecisionService.Evaluation eval = decisionService.evaluate(specOf(session), captures);
            consolidated = new SessionDetailResponse.Consolidated(
                    eval.fields(), eval.crossChecks(), eval.authenticityScore());
        }

        SessionDetailResponse.Decision decision = session.getDecisionOutcome() == null ? null
                : new SessionDetailResponse.Decision(session.getDecisionOutcome(),
                        session.getDecisionReasons() == null ? List.of()
                                : List.of(session.getDecisionReasons().split(",")));

        return new SessionDetailResponse(session.getId(), session.getDocumentType(), session.getStatus(),
                session.getCreatedAt(), session.getExpiresAt(), sides, consolidated, decision);
    }

    private OnboardingSession loadSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
                        "Sesión no encontrada: " + sessionId, sessionId));
    }

    private OnboardingSession loadActiveSession(UUID sessionId) {
        OnboardingSession session = loadSession(sessionId);
        expireIfNeeded(session);
        if (session.getStatus() == SessionStatus.EXPIRED) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED,
                    "La sesión expiró el " + session.getExpiresAt(), sessionId);
        }
        return session;
    }

    private void expireIfNeeded(OnboardingSession session) {
        if (session.getStatus() != SessionStatus.COMPLETED
                && session.getStatus() != SessionStatus.EXPIRED
                && session.isExpired(Instant.now())) {
            session.setStatus(SessionStatus.EXPIRED);
        }
    }

    private void updateSessionStatus(OnboardingSession session, DocumentTypeSpec spec) {
        List<DocumentCapture> captures = captureRepository.findBySessionId(session.getId());
        Map<DocumentSide, Boolean> captured = new LinkedHashMap<>();
        captures.forEach(c -> captured.put(c.getSide(), true));
        boolean allCaptured = spec.sides().stream().allMatch(captured::containsKey);
        session.setStatus(allCaptured ? SessionStatus.COMPLETED : SessionStatus.IN_PROGRESS);

        if (allCaptured) {
            DecisionService.Evaluation eval = decisionService.evaluate(spec, captures);
            session.setDecisionOutcome(eval.outcome());
            session.setDecisionReasons(eval.reasons().isEmpty() ? null : String.join(",", eval.reasons()));
        }
    }

    private DocumentTypeSpec specOf(OnboardingSession session) {
        return catalog.find(session.getDocumentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                        "El tipo de la sesión ya no existe en el catálogo: " + session.getDocumentType(),
                        session.getId()));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el resultado", e);
        }
    }
}
