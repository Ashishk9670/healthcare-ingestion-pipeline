package com.example.hdip.store;

import com.example.hdip.crypto.EncryptedStringConverter;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "observation")
public class ObservationEntity {
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private PatientEntity patient;

    private String code;
    private String system;
    private String display;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value_text_enc", columnDefinition = "text")
    private String valueText;

    @Column(name = "value_numeric")
    private BigDecimal valueNumeric;

    private String unit;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    public UUID getId() { return id; }
    public PatientEntity getPatient() { return patient; }
    public void setPatient(PatientEntity v) { this.patient = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getSystem() { return system; }
    public void setSystem(String v) { this.system = v; }
    public String getDisplay() { return display; }
    public void setDisplay(String v) { this.display = v; }
    public String getValueText() { return valueText; }
    public void setValueText(String v) { this.valueText = v; }
    public BigDecimal getValueNumeric() { return valueNumeric; }
    public void setValueNumeric(BigDecimal v) { this.valueNumeric = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant v) { this.effectiveAt = v; }
}
