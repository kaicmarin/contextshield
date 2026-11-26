package com.contextshield.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreApiApplication {
    public static void main(String[] args) {
        // Horário de Brasília por padrão: o motor usa a hora da compra (sinal de horário atípico).
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(System.getenv().getOrDefault("APP_TZ", "America/Sao_Paulo")));
        SpringApplication.run(CoreApiApplication.class, args);
    }
}
