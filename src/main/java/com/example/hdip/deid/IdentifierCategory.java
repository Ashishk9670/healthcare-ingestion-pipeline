package com.example.hdip.deid;

/** The 18 HIPAA Safe Harbor identifier categories, 45 CFR 164.514(b)(2)(i)(A)-(R). */
public enum IdentifierCategory {
    NAME("A", "Names"),
    GEOGRAPHIC("B", "Geographic subdivisions smaller than a state (street, city, county, precinct, ZIP; ZIP3 only if safe)"),
    DATE("C", "Dates (except year) related to an individual; ages over 89 and their dates"),
    PHONE("D", "Telephone numbers"),
    FAX("E", "Fax numbers"),
    EMAIL("F", "Email addresses"),
    SSN("G", "Social security numbers"),
    MRN("H", "Medical record numbers"),
    HEALTH_PLAN("I", "Health plan beneficiary numbers"),
    ACCOUNT("J", "Account numbers"),
    LICENSE("K", "Certificate/license numbers"),
    VEHICLE("L", "Vehicle identifiers and serial numbers, including license plates"),
    DEVICE("M", "Device identifiers and serial numbers"),
    URL("N", "Web URLs"),
    IP("O", "IP addresses"),
    BIOMETRIC("P", "Biometric identifiers, including finger and voice prints"),
    PHOTO("Q", "Full-face photographs and comparable images"),
    OTHER_UNIQUE_ID("R", "Any other unique identifying number, characteristic or code");

    private final String cfrItem;
    private final String description;

    IdentifierCategory(String cfrItem, String description) {
        this.cfrItem = cfrItem;
        this.description = description;
    }

    public String cfrItem() { return cfrItem; }
    public String description() { return description; }
}
