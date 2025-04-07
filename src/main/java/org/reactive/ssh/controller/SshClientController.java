package org.reactive.ssh.controller;

import org.apache.sshd.client.session.ClientSession;
import org.reactive.ssh.service.SshClientService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/ssh")
public class SshClientController {

    private final SshClientService sshService;

    public SshClientController(SshClientService sshService) {
        this.sshService = sshService;
    }

    @GetMapping("/connect")
    public Mono<String> connect(@RequestParam(name = "host") String host, @RequestParam(name = "port", defaultValue = "22") Integer port,
        @RequestParam(name = "username") String username, @RequestParam(name = "password") String password) {
        return sshService.connect(host, port, username, password)
            .map(session -> "Connected to " + host)
            .onErrorResume(e -> Mono.just("Error connecting to " + host + ": " + e.getMessage()));
    }

    @GetMapping("/executeCommand")
    public Mono<String> sendHello(@RequestParam(name = "host") String host, @RequestParam(name = "command") String command) {
        ClientSession session = sshService.getSession(host);
        if (session != null) {
            return sshService.executeCommand(session, command)
                .onErrorResume(e -> Mono.just("Error sending hello to " + host + ": " + e.getMessage()));
        } else {
            return Mono.just("No active session for " + host);
        }
    }

    @GetMapping("/close")
    public String closeSession(@RequestParam(name = "host") String host) {
        sshService.closeSession(host);
        return "Session closed successfully!";
    }

}
