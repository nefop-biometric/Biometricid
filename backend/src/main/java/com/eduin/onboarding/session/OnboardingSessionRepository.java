package com.eduin.onboarding.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OnboardingSessionRepository extends JpaRepository<OnboardingSession, UUID> {
}
