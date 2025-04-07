package org.reactive.ssh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class AppConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI().info(new Info().title("Reactive SSH Client API")
            .version("1.0")
            .description("API documentation for a reactive SSH client using Apache Mina SSH and Spring WebFlux."));
    }

}
