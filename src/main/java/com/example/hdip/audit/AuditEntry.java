package com.example.hdip.audit;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_entry")
public class AuditEntry {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private Instant timestamp = Instant.now();
    @Column(nullable = false)
    private String actor;
    private String roles;
    @Column(nullable = false)
    private String action;
    @Column(name = "resource_type")
    private String resourceType;
    @Column(name = "resource_id")
    private String resourceId;
    @Column(columnDefinition = "text")
    private String detail;

    protected AuditEntry() {
    }

    public AuditEntry(String actor, String roles, String action, String resourceType, String resourceId, String detail) {
        this.actor = actor;
        this.roles = roles;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getActor() { return actor; }
    public String getRoles() { return roles; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetail() { return detail; }
}
