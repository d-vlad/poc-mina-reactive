package org.reactive.ssh.service;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.UnknownCommandFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class SshClientServiceTest {

    private final SshClientService client = new SshClientService();

    @Test
    public void testSSHDServer() throws Exception {
        setupSSH();
        client.connect("localhost", 2222, "user", "pass")
            .subscribe(session -> client.executeCommand(session, "ls")
                .subscribe(System.out::println));
    }

    private void setupSSH() throws IOException {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(2222); // You can set 0 for random available port
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get("hostkey.ser")));

        sshServer.setPasswordAuthenticator((username, password, session) -> "user".equals(username) && "pass".equals(password));
        sshServer.setCommandFactory(UnknownCommandFactory.INSTANCE);
        sshServer.start();
    }
}
