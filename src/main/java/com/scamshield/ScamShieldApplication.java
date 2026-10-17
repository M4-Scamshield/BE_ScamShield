package com.scamshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScamShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScamShieldApplication.class, args);
    }
}
