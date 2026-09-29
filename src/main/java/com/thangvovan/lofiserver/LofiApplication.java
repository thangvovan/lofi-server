package com.thangvovan.lofiserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LofiProperties.class)
public class LofiApplication {
    public static void main(String[] args) {
        SpringApplication.run(LofiApplication.class, args);
    }
}
