package org.reactive.ssh.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.common.io.IoReadFuture;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

@Service
@Slf4j
public class SshClientService {

    private final SshClient client;
    // Store sessions mapped by connection ID.
    private final Map<String, ClientSession> sessionPool = new ConcurrentHashMap<>();

    public SshClientService() {
        this.client = SshClient.setUpDefaultClient();
        this.client.start();
    }

    public Mono<ClientSession> connect(String host, Integer port, String username, String password) {
        return Mono.create(sink -> {
            ConnectFuture connectFuture = null;
            try {
                connectFuture = client.connect(username, host, port);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            connectFuture.addListener(connectListener(host, password, sink));
        });
    }

    private SshFutureListener<ConnectFuture> connectListener(String host, String password, MonoSink<ClientSession> sink) {
        return future -> {
            if (future.isConnected()) {
                try {
                    ClientSession session = future.getSession();
                    session.addPasswordIdentity(password);
                    session.auth()
                        .addListener(authenticationListener(host, sink, session));
                } catch (IOException e) {
                    sink.error(e);
                }
            } else {
                sink.error(new Exception("Connection failed"));
            }
        };
    }

    private SshFutureListener<AuthFuture> authenticationListener(String host, MonoSink<ClientSession> sink, ClientSession session) {
        return authFuture -> {
            if (authFuture.isSuccess()) {
                sessionPool.put(host, session);
                //session.setServerAliveInterval(60); // Send keep-alive every 60 seconds
                sink.success(session);
            } else {
                sink.error(new Exception("Authentication failed"));
            }
        };
    }

    public Mono<String> executeCommand(ClientSession session, String command) {
        return Mono.create(sink -> {
            try (ClientChannel channel = session.createChannel(ClientChannel.CHANNEL_SUBSYSTEM, "netconf");
                 ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
                 ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
                 ByteArrayOutputStream responseStream = new ByteArrayOutputStream()) {

                channel.setStreaming(StreamingChannel.Streaming.Async);
                channel.open()
                    .verify(SSH_CHANNEL_OPEN_TIMEOUT);
                channel.setOut(responseStream);

                OpenFuture open = channel.open();
                open.addListener(channelMessageListener(sink, channel, responseStream, baosOut, baosErr));

                IoOutputStream asyncIn = channel.getAsyncIn();
                asyncIn.writeBuffer(new ByteArrayBuffer(command.getBytes(StandardCharsets.UTF_8)))
                    .addListener(writingListener(command, sink, asyncIn, channel));
            } catch (IOException e) {
                sink.error(e);
            }
        });
    }

    private SshFutureListener<OpenFuture> channelMessageListener(MonoSink<String> sink, ClientChannel channel, ByteArrayOutputStream responseStream,
        ByteArrayOutputStream baosOut, ByteArrayOutputStream baosErr) {
        return new SshFutureListener<>() {
            @Override
            public void operationComplete(OpenFuture future) {
                channel.getAsyncOut()
                    .read(new ByteArrayBuffer())
                    .addListener(readMessageListener());
                channel.getAsyncErr()
                    .read(new ByteArrayBuffer())
                    .addListener(readErrorListener());
            }

            private SshFutureListener<IoReadFuture> readErrorListener() {
                return new SshFutureListener<>() {
                    @Override
                    public void operationComplete(IoReadFuture future) {
                        try {
                            future.verify(10_000);
                            Buffer buffer = future.getBuffer();
                            baosErr.write(buffer.array(), buffer.rpos(), buffer.available());
                            buffer.rpos(buffer.rpos() + buffer.available());
                            buffer.compact();
                            channel.getAsyncErr()
                                .read(buffer)
                                .addListener(this);
                        } catch (IOException e) {
                            sink.error(e);
                            if (!channel.isClosing()) {
                                log.error("Error reading", e);
                                channel.close(true);
                            }
                        }
                    }
                };
            }

            private SshFutureListener<IoReadFuture> readMessageListener() {
                return new SshFutureListener<>() {
                    @Override
                    public void operationComplete(IoReadFuture future) {
                        try {
                            future.verify(READ_MESSAGE_TIMEOUT);

                            if (future.isDone()) {
                                sink.success(responseStream.toString());
                                channel.close(false);
                            }

                            Buffer buffer = future.getBuffer();
                            baosOut.write(buffer.array(), buffer.rpos(), buffer.available());
                            buffer.rpos(buffer.rpos() + buffer.available());
                            buffer.compact();
                            channel.getAsyncOut()
                                .read(buffer)
                                .addListener(this);
                        } catch (IOException e) {
                            sink.error(e);
                            if (!channel.isClosing()) {
                                log.error("Error reading", e);
                                channel.close(true);
                            }
                        }
                    }
                };
            }
        };
    }

    private SshFutureListener<IoWriteFuture> writingListener(String command, MonoSink<String> sink, IoOutputStream asyncIn, ClientChannel channel) {
        return new SshFutureListener<IoWriteFuture>() {
            @Override
            public void operationComplete(IoWriteFuture future) {
                try {
                    if (future.isWritten()) {
                        asyncIn.writeBuffer(new ByteArrayBuffer(command.getBytes(StandardCharsets.UTF_8)))
                            .addListener(this);
                    } else {
                        throw new SshException("Error writing", future.getException());
                    }
                } catch (IOException e) {
                    sink.error(e);
                    if (!channel.isClosing()) {
                        channel.close(true);
                    }
                }
            }
        };
    }

    public void closeSession(String host) {
        ClientSession session = sessionPool.remove(host);
        if (session != null) {
            session.close(false);
        }
    }

    public void stopClient() {
        client.stop();
    }

    public ClientSession getSession(String host) {
        return sessionPool.get(host);
    }

    public static final int SSH_CHANNEL_OPEN_TIMEOUT = 2_000;
    public static final int READ_MESSAGE_TIMEOUT = 10_000;
}

