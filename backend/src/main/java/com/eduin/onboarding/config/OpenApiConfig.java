package com.eduin.onboarding.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI onboardingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Onboarding API")
                .description("Onboarding con documentos de identidad — COL, ESP, ECU, PER, PAN. "
                        + "Contrato completo en docs/02-contrato-api.md")
                .version("v1"));
    }
}
