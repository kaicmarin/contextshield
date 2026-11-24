package com.contextshield.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microsserviço de risco. Não tem banco: recebe o contexto completo da compra,
 * devolve sinais, score, decisão, envelope de segurança e perguntas de verificação.
 * Por ser sem estado, pode ter várias réplicas atrás de um balanceador.
 */
@SpringBootApplication
public class RiskEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskEngineApplication.class, args);
    }
}
