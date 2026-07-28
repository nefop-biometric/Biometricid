package com.eduin.onboarding.session;

import com.eduin.onboarding.catalog.DocumentSide;
import com.eduin.onboarding.session.dto.CreateSessionRequest;
import com.eduin.onboarding.session.dto.CreateSessionResponse;
import com.eduin.onboarding.session.dto.SessionDetailResponse;
import com.eduin.onboarding.session.dto.SideResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/onboarding/sessions")
@Tag(name = "Onboarding", description = "Ciclo de vida de la sesión de onboarding")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea una sesión de onboarding para un tipo de documento")
    public CreateSessionResponse create(@Valid @RequestBody CreateSessionRequest request) {
        return sessionService.createSession(request);
    }

    @PostMapping(value = "/{sessionId}/documents/{side}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Sube y procesa una cara del documento (clasificación → OCR → antifraude)")
    public SideResultResponse uploadSide(@PathVariable UUID sessionId,
                                         @PathVariable DocumentSide side,
                                         @RequestPart("image") MultipartFile image,
                                         @RequestParam(defaultValue = "false") boolean replace) {
        // MultipartFile solo se puede leer UNA vez: se materializa a byte[] aquí y no se vuelve a tocar
        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la imagen recibida", e);
        }
        return sessionService.processSide(sessionId, side, imageBytes, replace);
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Estado de la sesión y resultado consolidado")
    public SessionDetailResponse get(@PathVariable UUID sessionId) {
        return sessionService.getSession(sessionId);
    }
}
