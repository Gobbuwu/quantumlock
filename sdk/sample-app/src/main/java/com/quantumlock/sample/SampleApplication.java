package com.quantumlock.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample app for the QuantumLock SDK. Set QUANTUMLOCK_API_URL and QUANTUMLOCK_API_KEY, then run it: it
 * generates an ML-KEM-1024 key pair, agrees on a shared secret and exits with 0 when both sides match.
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SampleApplication.class, args)));
    }
}
