package com.instanthelp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InstantHelpApplication {
    public static void main(String[] args) {
        SpringApplication.run(InstantHelpApplication.class, args);
    }
}
