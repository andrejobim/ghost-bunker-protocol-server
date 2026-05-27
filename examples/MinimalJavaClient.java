package io.ghostbunker.examples;

import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.EncryptedMessage;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal Java client:
 * - Connects to ws://localhost:8080/ghost-bunker
 * - Sends HELLO, JOIN_ROOM, SEND_ENCRYPTED_MESSAGE (ciphertext only)
 * - Prints received envelope types (does not decrypt)
 *
 * Compile/run example (from repo root, after `mvn -q test` or `mvn -q package`):
 * - This file is a simple example and isn't wired to Maven by default.
 */
public final class MinimalJavaClient {
  public static void main(String[] args) throws Exception {
    String url = args.length > 0 ? args[0] : "ws://localhost:8080/ghost-bunker";
    String roomId = args.length > 1 ? args[1] : "room1";

    CountDownLatch connected = new CountDownLatch(1);

    WebSocket ws = HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .buildAsync(URI.create(url), new WebSocket.Listener() {
          @Override
          public void onOpen(WebSocket webSocket) {
            System.out.println("connected");
            connected.countDown();
            webSocket.request(1);
          }

          @Override
          public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
              byte[] bytes = new byte[data.remaining()];
              data.get(bytes);
              GhostEnvelope env = GhostEnvelope.parseFrom(bytes);
              System.out.println("recv type=" + env.getType());

              if (env.getType() == MessageType.ENCRYPTED_MESSAGE && env.hasEncryptedMessage()) {
                EncryptedMessage em = env.getEncryptedMessage();
                System.out.println("encrypted_message room_id=" + env.getRoomId()
                    + " from=" + redact(em.getFromUserId())
                    + " ciphertext_bytes=" + em.getCiphertext().size());
              }
            } catch (Exception e) {
              System.out.println("failed to parse envelope: " + e.getMessage());
            } finally {
              webSocket.request(1);
            }
            return null;
          }

          @Override
          public CompletionStage<?> onError(WebSocket webSocket, Throwable error) {
            System.out.println("ws error: " + error.getClass().getSimpleName());
            return null;
          }
        }).join();

    connected.await();

    ws.sendBinary(ByteBuffer.wrap(hello("alice").toByteArray()), true).join();
    Thread.sleep(100);

    ws.sendBinary(ByteBuffer.wrap(join(roomId).toByteArray()), true).join();
    Thread.sleep(100);

    // Example ciphertext (dummy bytes). Real clients MUST encrypt before sending.
    byte[] ciphertext = new byte[] {1, 2, 3, 4, 5};
    ws.sendBinary(ByteBuffer.wrap(sendEncrypted(roomId, ciphertext).toByteArray()), true).join();

    Thread.sleep(5_000);
    ws.abort();
  }

  private static GhostEnvelope hello(String nickname) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.HELLO)
        .setHello(Hello.newBuilder().setClientName("minimal-java").setNickname(nickname).build())
        .build();
  }

  private static GhostEnvelope join(String roomId) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.JOIN_ROOM)
        .setRoomId(roomId)
        .setJoinRoom(JoinRoom.newBuilder().setRoomId(roomId).build())
        .build();
  }

  private static GhostEnvelope sendEncrypted(String roomId, byte[] ciphertext) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setRequestId("req-" + UUID.randomUUID())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.SEND_ENCRYPTED_MESSAGE)
        .setRoomId(roomId)
        .setSendEncryptedMessage(SendEncryptedMessage.newBuilder()
            .setClientMessageId("c-" + UUID.randomUUID())
            .setKeyId("k1")
            .setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
            .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[] {9,9,9,9,9,9,9,9,9,9,9,9}))
            .setCiphertext(com.google.protobuf.ByteString.copyFrom(ciphertext))
            .build())
        .build();
  }

  private static String redact(String id) {
    if (id == null) return "";
    if (id.length() <= 8) return id;
    return id.substring(0, 8) + "...";
  }
}

