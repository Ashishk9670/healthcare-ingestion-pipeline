package com.example.hdip.store;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "diagnosis")
public class DiagnosisEntity {
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private PatientEntity patient;

    private String code;
    private String system;
    private String display;

    public UUID getId() { return id; }
    public PatientEntity getPatient() { return patient; }
    public void setPatient(PatientEntity v) { this.patient = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getSystem() { return system; }
    public void setSystem(String v) { this.system = v; }
    public String getDisplay() { return display; }
    public void setDisplay(String v) { this.display = v; }
}
