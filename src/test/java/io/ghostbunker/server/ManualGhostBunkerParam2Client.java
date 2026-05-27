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

public class ManualGhostBunkerParam2Client {

    /*
     * CONFIG
     *
     * Para testar roteamento:
     *
     * 1) Rode primeiro com:
     *    NICKNAME = "Andre-A"
     *    SEND_MESSAGE_AFTER_JOIN = false
     *    KEEP_ALIVE_SECONDS = 60
     *
     * 2) Rode outra instância com:
     *    NICKNAME = "Andre-B"
     *    SEND_MESSAGE_AFTER_JOIN = true
     *
     * O primeiro cliente deve receber ENCRYPTED_MESSAGE.
     */
    private static final String WS_URL = "ws://localhost:8080/ghost-bunker";
    private static final String NICKNAME = "Andre-B";
    private static final String ROOM_ID = "sala1";

    private static final boolean SEND_MESSAGE_AFTER_JOIN = true;
    private static final int KEEP_ALIVE_SECONDS = 60;

    private static final String KEY_ID = "manual-test-key";
    private static final String FAKE_CIPHERTEXT_PREFIX = "fake-ciphertext-from-";

    private static final String PROTOCOL = "ghost-bunker";
    private static final String VERSION = "0.1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CountDownLatch done = new CountDownLatch(1);

    private volatile WebSocket webSocket;
    private volatile boolean joinedRoom = false;
    private volatile boolean messageSent = false;

    public static void main(String[] args) throws Exception {
        System.out.println("Connecting to " + WS_URL);
        System.out.println("nickname=" + NICKNAME);
        System.out.println("roomId=" + ROOM_ID);
        System.out.println("sendMessageAfterJoin=" + SEND_MESSAGE_AFTER_JOIN);

        ManualGhostBunkerParam2Client client = new ManualGhostBunkerParam2Client();
        client.start();
    }

    private void start() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();

        this.webSocket = httpClient.newWebSocketBuilder()
                .subprotocols("ghost-bunker.v0.1")
                .buildAsync(URI.create(WS_URL), new Listener())
                .join();

        sendHello();

        boolean completed = done.await(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            System.out.println("Manual client finished by keep-alive timeout.");
        }

        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "manual test finished").join();
        } catch (Exception ignored) {
            // ignore close errors in manual client
        }
    }

    private void sendHello() {
        GhostEnvelope envelope = baseEnvelope(MessageType.HELLO)
                .setHello(Hello.newBuilder()
                        .setClientName("manual-java-client-param")
                        .setNickname(NICKNAME)
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

        byte[] fakeCiphertext = (FAKE_CIPHERTEXT_PREFIX + NICKNAME + "-" + randomId()).getBytes();

        String clientMessageId = randomId();

        GhostEnvelope envelope = baseEnvelope(MessageType.SEND_ENCRYPTED_MESSAGE)
                .setRoomId(ROOM_ID)
                .setRequestId(randomId())
                .setSendEncryptedMessage(SendEncryptedMessage.newBuilder()
                        .setClientMessageId(clientMessageId)
                        .setKeyId(KEY_ID)
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
                System.out.println("Failed to parse binary GhostEnvelope: " + e.getClass().getSimpleName()
                        + ": " + sanitize(e.getMessage()));
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
            MessageType type = envelope.getType();

            System.out.println("SERVER -> " + type);

            switch (type) {
                case WELCOME -> {
                    System.out.println("WELCOME received. displayName="
                            + sanitize(envelope.getWelcome().getDisplayName()));
                    sendJoinRoom();
                }

                case ROOM_JOINED -> {
                    joinedRoom = true;
                    System.out.println("Joined room: " + sanitize(envelope.getRoomJoined().getRoomId()));

                    if (SEND_MESSAGE_AFTER_JOIN) {
                        sendEncryptedMessage();
                    } else {
                        System.out.println("Listener mode active. Waiting for ENCRYPTED_MESSAGE...");
                    }
                }

                case MESSAGE_ACCEPTED -> {
                    System.out.println("Message accepted. serverMessageId="
                            + sanitize(envelope.getMessageAccepted().getServerMessageId()));

                    if (SEND_MESSAGE_AFTER_JOIN) {
                        done.countDown();
                    }
                }

                case ENCRYPTED_MESSAGE -> {
                    System.out.println("Encrypted message received.");
                    System.out.println("serverMessageId="
                            + sanitize(envelope.getEncryptedMessage().getServerMessageId()));
                    System.out.println("fromUserId="
                            + sanitize(envelope.getEncryptedMessage().getFromUserId()));
                    System.out.println("cipherSuite="
                            + envelope.getEncryptedMessage().getCipherSuite());
                    System.out.println("ciphertextBytes="
                            + envelope.getEncryptedMessage().getCiphertext().size());

                    done.countDown();
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

                default -> System.out.println("Unhandled server message type: " + type);
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
