package com.signallab.domain.profile.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Profile(
    UUID userId,
    UUID publicProfileId,
    String nickname,
    boolean isPublic,
    OffsetDateTime riskDisclosureAcceptedAt,
    String termsVersion,
    String privacyVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime deletedAt
) {}
