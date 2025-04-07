package org.reactive.ssh.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
public class SwaggerController {

    @GetMapping("/")
    public void redirectToSwaggerUI(ServerWebExchange exchange) {
        exchange.getResponse()
            .setStatusCode(HttpStatus.FOUND);
        exchange.getResponse()
            .getHeaders()
            .setLocation(URI.create("/webjars/swagger-ui/index.html"));
    }

}
