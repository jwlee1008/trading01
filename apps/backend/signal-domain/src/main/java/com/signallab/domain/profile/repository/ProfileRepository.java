package com.signallab.domain.profile.repository;

import com.signallab.domain.profile.entity.Profile;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {
    Optional<Profile> findByUserId(UUID userId);
    void save(Profile profile);
    void updateVisibility(UUID userId, boolean isPublic);
    void delete(UUID userId);
}
