package com.eduin.onboarding.session;

import com.eduin.onboarding.catalog.DocumentSide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentCaptureRepository extends JpaRepository<DocumentCapture, UUID> {

    List<DocumentCapture> findBySessionId(UUID sessionId);

    Optional<DocumentCapture> findBySessionIdAndSide(UUID sessionId, DocumentSide side);
}
