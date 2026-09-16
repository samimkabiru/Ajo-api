package com.theninjadev.ajoapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AjoApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AjoApiApplication.class, args);
    }

}
