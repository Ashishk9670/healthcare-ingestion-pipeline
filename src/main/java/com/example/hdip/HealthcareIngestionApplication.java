package com.example.hdip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Healthcare ingestion pipeline. SYNTHETIC DATA ONLY - never feed real PHI into this project. */
@SpringBootApplication
public class HealthcareIngestionApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthcareIngestionApplication.class, args);
    }
}
