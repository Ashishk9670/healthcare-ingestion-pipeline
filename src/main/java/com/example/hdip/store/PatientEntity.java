package com.example.hdip.store;

import com.example.hdip.crypto.EncryptedStringConverter;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "patient")
public class PatientEntity {
    @Id
    private UUID id = UUID.randomUUID();

    /** HMAC of the MRN: deterministic lookup key; the plaintext MRN is only stored encrypted. */
    @Column(name = "mrn_hash", nullable = false, unique = true, length = 64)
    private String mrnHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mrn_enc", nullable = false, columnDefinition = "text")
    private String mrn;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "family_name_enc", columnDefinition = "text")
    private String familyName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "given_name_enc", columnDefinition = "text")
    private String givenName;

    /** ISO-8601 date, encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "birth_date_enc", columnDefinition = "text")
    private String birthDate;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ssn_enc", columnDefinition = "text")
    private String ssn;

    @Column(length = 16)
    private String gender;

    @Column(name = "age_band", length = 16)
    private String ageBand;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ObservationEntity> observations = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DiagnosisEntity> diagnoses = new ArrayList<>();

    public UUID getId() { return id; }
    public String getMrnHash() { return mrnHash; }
    public void setMrnHash(String v) { this.mrnHash = v; }
    public String getMrn() { return mrn; }
    public void setMrn(String v) { this.mrn = v; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String v) { this.familyName = v; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String v) { this.givenName = v; }
    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String v) { this.birthDate = v; }
    public String getSsn() { return ssn; }
    public void setSsn(String v) { this.ssn = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getAgeBand() { return ageBand; }
    public void setAgeBand(String v) { this.ageBand = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public List<ObservationEntity> getObservations() { return observations; }
    public List<DiagnosisEntity> getDiagnoses() { return diagnoses; }
}
