package com.example.hdip.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {
    List<AuditEntry> findAllByOrderByTimestampDesc(Pageable pageable);
}
