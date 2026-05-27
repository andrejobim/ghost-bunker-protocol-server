package io.ghostbunker.server.manual;

import com.google.protobuf.ByteString;
import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.ClientCapabilities;
import io.ghostbunker.protocol.v1.ErrorMessage;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.Pong;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ManualGhostBunkerClient {

    private static final String DEFAULT_URL = "ws://localhost:8080/ghost-bunker";
    private static final String PROTOCOL = "ghost-bunker";
    private static final String VERSION = "0.1";
    private static final String ROOM_ID = "sala1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CountDownLatch done = new CountDownLatch(1);

    private volatile WebSocket webSocket;
    private volatile boolean joinedRoom = false;
    private volatile boolean messageSent = false;

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : DEFAULT_URL;

        System.out.println("Connecting to " + url);

        ManualGhostBunkerClient client = new ManualGhostBunkerClient();
        client.start(url);
    }

    private void start(String url) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();

        this.webSocket = httpClient.newWebSocketBuilder()
                .subprotocols("ghost-bunker.v0.1")
                .buildAsync(URI.create(url), new Listener())
                .join();

        sendHello();

        boolean completed = done.await(30, TimeUnit.SECONDS);

        if (!completed) {
            System.out.println("Manual test timeout.");
        }

        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "manual test finished").join();
        } catch (Exception ignored) {
            // nothing
        }
    }

    private void sendHello() {
        GhostEnvelope envelope = baseEnvelope(MessageType.HELLO)
                .setHello(Hello.newBuilder()
                        .setClientName("manual-java-client")
                        .setNickname("Andre")
                        .setCapabilities(ClientCapabilities.newBuilder()
                                .setE2EeSupported(true)
                                .addSupportedCipherSuites(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
                                .setMaxCiphertextBytesSupported(16 * 1024)
                                .build())
                        .build())
                .build();

        send(envelope);
        System.out.println("CLIENT -> HELLO");
    }

    private void sendJoinRoom() {
        GhostEnvelope envelope = baseEnvelope(MessageType.JOIN_ROOM)
                .setRoomId(ROOM_ID)
                .setRequestId(randomId())
                .setJoinRoom(JoinRoom.newBuilder()
                        .setRoomId(ROOM_ID)
                        .build())
                .build();

        send(envelope);
        System.out.println("CLIENT -> JOIN_ROOM " + ROOM_ID);
    }

    private void sendEncryptedMessage() {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        // Fake ciphertext apenas para testar roteamento/validação.
        // O servidor não descriptografa.
        byte[] fakeCiphertext = ("fake-ciphertext-" + randomId()).getBytes();

        String clientMessageId = randomId();

        GhostEnvelope envelope = baseEnvelope(MessageType.SEND_ENCRYPTED_MESSAGE)
                .setRoomId(ROOM_ID)
                .setRequestId(randomId())
                .setSendEncryptedMessage(SendEncryptedMessage.newBuilder()
                        .setClientMessageId(clientMessageId)
                        .setKeyId("manual-test-key")
                        .setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
                        .setNonce(ByteString.copyFrom(nonce))
                        .setCiphertext(ByteString.copyFrom(fakeCiphertext))
                        .setAadVersion(1)
                        .build())
                .build();

        send(envelope);
        messageSent = true;

        System.out.println("CLIENT -> SEND_ENCRYPTED_MESSAGE clientMessageId=" + clientMessageId);
    }

    private GhostEnvelope.Builder baseEnvelope(MessageType type) {
        return GhostEnvelope.newBuilder()
                .setProtocol(PROTOCOL)
                .setVersion(VERSION)
                .setMessageId(randomId())
                .setTimestampMs(Instant.now().toEpochMilli())
                .setType(type);
    }

    private void send(GhostEnvelope envelope) {
        byte[] bytes = envelope.toByteArray();

        webSocket.sendBinary(ByteBuffer.wrap(bytes), true).join();
    }

    private static String randomId() {
        return UUID.randomUUID().toString();
    }

    private final class Listener implements WebSocket.Listener {

        private ByteBuffer partialBinary;

        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("WebSocket opened.");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                ByteBuffer complete = collect(data, last);

                if (complete != null) {
                    byte[] bytes = new byte[complete.remaining()];
                    complete.get(bytes);

                    GhostEnvelope envelope = GhostEnvelope.parseFrom(bytes);
                    handleEnvelope(envelope);
                }
            } catch (Exception e) {
                System.out.println("Failed to parse binary GhostEnvelope: " + e.getMessage());
                done.countDown();
            } finally {
                webSocket.request(1);
            }

            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("Unexpected text frame from server. length=" + data.length());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket closed. code=" + statusCode + ", reason=" + sanitize(reason));
            done.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("WebSocket error: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            done.countDown();
        }

        private ByteBuffer collect(ByteBuffer data, boolean last) {
            if (last && partialBinary == null) {
                return data;
            }

            if (partialBinary == null) {
                partialBinary = ByteBuffer.allocate(data.remaining());
                partialBinary.put(data);
            } else {
                ByteBuffer expanded = ByteBuffer.allocate(partialBinary.position() + data.remaining());
                partialBinary.flip();
                expanded.put(partialBinary);
                expanded.put(data);
                partialBinary = expanded;
            }

            if (!last) {
                return null;
            }

            partialBinary.flip();
            ByteBuffer complete = partialBinary;
            partialBinary = null;
            return complete;
        }

        private void handleEnvelope(GhostEnvelope envelope) {
            MessageType type = envelope.getType();

            System.out.println("SERVER -> " + type);

            switch (type) {
                case WELCOME -> {
                    System.out.println("WELCOME received. displayName=" + sanitize(envelope.getWelcome().getDisplayName()));
                    sendJoinRoom();
                }

                case ROOM_JOINED -> {
                    joinedRoom = true;
                    System.out.println("Joined room: " + sanitize(envelope.getRoomJoined().getRoomId()));
                    sendEncryptedMessage();
                }

                case MESSAGE_ACCEPTED -> {
                    System.out.println("Message accepted. serverMessageId="
                            + sanitize(envelope.getMessageAccepted().getServerMessageId()));
                    done.countDown();
                }

                case ENCRYPTED_MESSAGE -> {
                    System.out.println("Encrypted message received. serverMessageId="
                            + sanitize(envelope.getEncryptedMessage().getServerMessageId()));
                }

                case PING -> {
                    String nonce = envelope.getPing().getNonce();

                    GhostEnvelope pong = baseEnvelope(MessageType.PONG)
                            .setPong(Pong.newBuilder()
                                    .setNonce(nonce)
                                    .build())
                            .build();

                    send(pong);
                    System.out.println("CLIENT -> PONG");
                }

                case ERROR -> {
                    ErrorMessage error = envelope.getError();
                    System.out.println("ERROR received. code=" + error.getCode()
                            + ", message=" + sanitize(error.getMessage()));
                    done.countDown();
                }

                case GOODBYE -> {
                    System.out.println("GOODBYE received. reason=" + envelope.getGoodbye().getReason()
                            + ", message=" + sanitize(envelope.getGoodbye().getMessage()));
                    done.countDown();
                }

                default -> {
                    System.out.println("Unhandled server message type: " + type);
                    if (joinedRoom && !messageSent) {
                        sendEncryptedMessage();
                    }
                }
            }
        }

        private String sanitize(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }

            return value.replaceAll("[\\r\\n\\t]", " ");
        }
    }
}