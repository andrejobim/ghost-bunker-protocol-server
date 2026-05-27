package io.ghostbunker.server;

import com.google.protobuf.ByteString;
import io.ghostbunker.protocol.v1.*;

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

public final class ManualGhostBunkerNegativeClient {

    /*
     * Escolha o cenário aqui e rode a classe.
     */
//    private static final Scenario SCENARIO = Scenario.NICKNAME_WITH_EMOJI;
//    private static final Scenario SCENARIO = Scenario.WRONG_VERSION;
//    private static final Scenario SCENARIO = Scenario.SEND_ENCRYPTED_BEFORE_JOIN_ROOM;
//    private static final Scenario SCENARIO = Scenario.CIPHERTEXT_TOO_LARGE;
//    private static final Scenario SCENARIO = Scenario.CIPHERTEXT_EMPTY;
//    private static final Scenario SCENARIO = Scenario.NONCE_MISSING;
//    private static final Scenario SCENARIO = Scenario.KEY_ID_MISSING;
//    private static final Scenario SCENARIO = Scenario.ENC_SUITE_MISSING;
    private static final Scenario SCENARIO = Scenario.JOIN_ROOM_BEFORE_HELLO;

    private static final String WS_URL = "ws://localhost:8080/ghost-bunker";
    private static final String PROTOCOL = "ghost-bunker";
    private static final String VERSION = "0.1";
    private static final String ROOM_ID = "sala1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CountDownLatch done = new CountDownLatch(1);
    private volatile WebSocket webSocket;

    public static void main(String[] args) throws Exception {
        System.out.println("Connecting to " + WS_URL);
        System.out.println("scenario=" + SCENARIO);

        ManualGhostBunkerNegativeClient client = new ManualGhostBunkerNegativeClient();
        client.start();
    }

    private void start() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();

        this.webSocket = httpClient.newWebSocketBuilder()
                .subprotocols("ghost-bunker.v0.1")
                .buildAsync(URI.create(WS_URL), new Listener())
                .join();

        runScenario();

        boolean completed = done.await(10, TimeUnit.SECONDS);

        if (!completed) {
            System.out.println("Manual negative test timeout.");
        }

        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "manual negative test finished").join();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void runScenario() {
        switch (SCENARIO) {
            case TEXT_FRAME -> sendTextFrame();

            case INVALID_PROTOBUF_BYTES -> sendInvalidBinary();

            case WRONG_PROTOCOL -> sendHello("wrong-protocol", VERSION, "Andre");

            case WRONG_VERSION -> sendHello(PROTOCOL, "9.9", "Andre");

            case NICKNAME_WITH_EMOJI -> sendHello(PROTOCOL, VERSION, "Andre😀");

            case NICKNAME_NON_ASCII -> sendHello(PROTOCOL, VERSION, "André");

            case JOIN_ROOM_BEFORE_HELLO -> sendJoinRoom();

            case SEND_ENCRYPTED_BEFORE_HELLO -> sendEncryptedMessage();

            case SEND_ENCRYPTED_BEFORE_JOIN_ROOM -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // O envio será disparado ao receber WELCOME no listener.
            }

            case CIPHERTEXT_EMPTY -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // Depois de ROOM_JOINED, envia ciphertext vazio.
            }

            case CIPHERTEXT_TOO_LARGE -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // Depois de ROOM_JOINED, envia ciphertext maior que 16 KB.
            }

            case NONCE_MISSING -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // Depois de ROOM_JOINED, envia sem nonce.
            }

            case KEY_ID_MISSING -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // Depois de ROOM_JOINED, envia sem key_id.
            }

            case ENC_SUITE_MISSING -> {
                sendHello(PROTOCOL, VERSION, "Andre");
                // Depois de ROOM_JOINED, envia sem enc_suite.
            }
        }
    }

    private void sendTextFrame() {
        webSocket.sendText("this is invalid text frame", true).join();
        System.out.println("CLIENT -> invalid text frame");
    }

    private void sendInvalidBinary() {
        byte[] invalid = new byte[]{1, 2, 3, 4, 5, 6};
        webSocket.sendBinary(ByteBuffer.wrap(invalid), true).join();
        System.out.println("CLIENT -> invalid protobuf bytes");
    }

    private void sendHello(String protocol, String version, String nickname) {
        GhostEnvelope envelope = GhostEnvelope.newBuilder()
                .setProtocol(protocol)
                .setVersion(version)
                .setMessageId(randomId())
                .setTimestampMs(now())
                .setType(MessageType.HELLO)
                .setHello(Hello.newBuilder()
                        .setClientName("manual-negative-client")
                        .setNickname(nickname)
                        .setCapabilities(ClientCapabilities.newBuilder()
                                .setE2EeSupported(true)
                                .addSupportedCipherSuites(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
                                .setMaxCiphertextBytesSupported(16 * 1024)
                                .build())
                        .build())
                .build();

        send(envelope);
        System.out.println("CLIENT -> HELLO nickname=" + sanitize(nickname)
                + ", protocol=" + protocol
                + ", version=" + version);
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
        System.out.println("CLIENT -> JOIN_ROOM before/after hello depending scenario");
    }

    private void sendEncryptedMessage() {
        sendEncryptedMessage(false, false, false, false);
    }

    private void sendEncryptedMessage(
            boolean emptyCiphertext,
            boolean tooLargeCiphertext,
            boolean missingNonce,
            boolean missingKeyId
    ) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        byte[] ciphertext;
        if (emptyCiphertext) {
            ciphertext = new byte[0];
        } else if (tooLargeCiphertext) {
            ciphertext = new byte[(16 * 1024) + 1];
            RANDOM.nextBytes(ciphertext);
        } else {
            ciphertext = ("fake-ciphertext-" + randomId()).getBytes();
        }

        SendEncryptedMessage.Builder payload = SendEncryptedMessage.newBuilder()
                .setClientMessageId(randomId())
                .setCiphertext(ByteString.copyFrom(ciphertext))
                .setAadVersion(1);

        if (!missingKeyId) {
            payload.setKeyId("manual-test-key");
        }

        if (!missingNonce) {
            payload.setNonce(ByteString.copyFrom(nonce));
        }

        if (SCENARIO != Scenario.ENC_SUITE_MISSING) {
            payload.setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM);
        }

        GhostEnvelope envelope = baseEnvelope(MessageType.SEND_ENCRYPTED_MESSAGE)
                .setRoomId(ROOM_ID)
                .setRequestId(randomId())
                .setSendEncryptedMessage(payload.build())
                .build();

        send(envelope);

        System.out.println("CLIENT -> SEND_ENCRYPTED_MESSAGE invalid scenario=" + SCENARIO
                + ", ciphertextBytes=" + ciphertext.length);
    }

    private GhostEnvelope.Builder baseEnvelope(MessageType type) {
        return GhostEnvelope.newBuilder()
                .setProtocol(PROTOCOL)
                .setVersion(VERSION)
                .setMessageId(randomId())
                .setTimestampMs(now())
                .setType(type);
    }

    private void send(GhostEnvelope envelope) {
        byte[] bytes = envelope.toByteArray();
        webSocket.sendBinary(ByteBuffer.wrap(bytes), true).join();
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static String randomId() {
        return UUID.randomUUID().toString();
    }

    private final class Listener implements WebSocket.Listener {

        private ByteBuffer partialBinary;
        private boolean welcomeReceived = false;
        private boolean roomJoined = false;

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
                System.out.println("Failed to parse server GhostEnvelope: "
                        + e.getClass().getSimpleName()
                        + ": " + sanitize(e.getMessage()));
                done.countDown();
            } finally {
                webSocket.request(1);
            }

            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("Unexpected text from server. length=" + data.length());
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
            System.out.println("WebSocket error: " + error.getClass().getSimpleName()
                    + ": " + sanitize(error.getMessage()));
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
            System.out.println("SERVER -> " + envelope.getType());

            switch (envelope.getType()) {
                case WELCOME -> {
                    welcomeReceived = true;
                    System.out.println("WELCOME received.");

                    if (SCENARIO == Scenario.SEND_ENCRYPTED_BEFORE_JOIN_ROOM) {
                        sendEncryptedMessage();
                    } else if (requiresJoinThenInvalidEncrypted()) {
                        sendJoinRoom();
                    }
                }

                case ROOM_JOINED -> {
                    roomJoined = true;
                    System.out.println("ROOM_JOINED received.");

                    switch (SCENARIO) {
                        case CIPHERTEXT_EMPTY -> sendEncryptedMessage(true, false, false, false);
                        case CIPHERTEXT_TOO_LARGE -> sendEncryptedMessage(false, true, false, false);
                        case NONCE_MISSING -> sendEncryptedMessage(false, false, true, false);
                        case KEY_ID_MISSING -> sendEncryptedMessage(false, false, false, true);
                        case ENC_SUITE_MISSING -> sendEncryptedMessage(false, false, false, false);
                        default -> {
                            // no-op
                        }
                    }
                }

                case ERROR -> {
                    System.out.println("ERROR code=" + envelope.getError().getCode()
                            + ", message=" + sanitize(envelope.getError().getMessage()));
                    done.countDown();
                }

                case GOODBYE -> {
                    System.out.println("GOODBYE reason=" + envelope.getGoodbye().getReason()
                            + ", message=" + sanitize(envelope.getGoodbye().getMessage()));
                    done.countDown();
                }

                case PING -> {
                    GhostEnvelope pong = baseEnvelope(MessageType.PONG)
                            .setPong(Pong.newBuilder()
                                    .setNonce(envelope.getPing().getNonce())
                                    .build())
                            .build();

                    send(pong);
                    System.out.println("CLIENT -> PONG");
                }

                default -> System.out.println("Unhandled server type=" + envelope.getType());
            }
        }

        private boolean requiresJoinThenInvalidEncrypted() {
            return SCENARIO == Scenario.CIPHERTEXT_EMPTY
                    || SCENARIO == Scenario.CIPHERTEXT_TOO_LARGE
                    || SCENARIO == Scenario.NONCE_MISSING
                    || SCENARIO == Scenario.KEY_ID_MISSING
                    || SCENARIO == Scenario.ENC_SUITE_MISSING;
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.replaceAll("[\\r\\n\\t]", " ");
    }

    private enum Scenario {
        TEXT_FRAME,
        INVALID_PROTOBUF_BYTES,
        WRONG_PROTOCOL,
        WRONG_VERSION,
        NICKNAME_WITH_EMOJI,
        NICKNAME_NON_ASCII,
        JOIN_ROOM_BEFORE_HELLO,
        SEND_ENCRYPTED_BEFORE_HELLO,
        SEND_ENCRYPTED_BEFORE_JOIN_ROOM,
        CIPHERTEXT_EMPTY,
        CIPHERTEXT_TOO_LARGE,
        NONCE_MISSING,
        KEY_ID_MISSING,
        ENC_SUITE_MISSING
    }
}