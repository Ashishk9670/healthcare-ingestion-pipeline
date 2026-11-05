package com.example.hdip.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsentStateRepository extends JpaRepository<ConsentStateEntity, UUID> {
    Optional<ConsentStateEntity> findByMrnHashAndScope(String mrnHash, ConsentScope scope);
}
