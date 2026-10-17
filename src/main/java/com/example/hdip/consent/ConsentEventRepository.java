package com.example.hdip.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConsentEventRepository extends JpaRepository<ConsentEventEntity, UUID> {
}
