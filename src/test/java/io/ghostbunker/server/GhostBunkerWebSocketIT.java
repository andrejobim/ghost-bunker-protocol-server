package io.ghostbunker.server;

import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;

import java.util.Arrays;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GhostBunkerWebSocketIT {
  @LocalServerPort
  int port;

  @Value("${server.ssl.enabled:false}")
  boolean sslEnabled;

  @Test
  void hello_then_join_then_send_routes_without_decrypting() throws Exception {
    WsTestClient c1 = new WsTestClient();
    WsTestClient c2 = new WsTestClient();
    String url = "ws://localhost:" + port + "/ghost-bunker";

    WebSocketSession s1 = c1.connect(url);
    WebSocketSession s2 = c2.connect(url);

    c1.sendBinary(s1, hello("alice").toByteArray());
    c2.sendBinary(s2, hello("bob").toByteArray());

    awaitAtLeast(c1, 1);
    awaitAtLeast(c2, 1);

    c1.sendBinary(s1, join("room1").toByteArray());
    c2.sendBinary(s2, join("room1").toByteArray());

    awaitType(c1, MessageType.ROOM_JOINED);
    awaitType(c2, MessageType.ROOM_JOINED);

    byte[] ciphertext = new byte[] {1,2,3,4,5};
    c1.sendBinary(s1, sendEncrypted("room1", ciphertext).toByteArray());

    GhostEnvelope accepted = awaitType(c1, MessageType.MESSAGE_ACCEPTED);
    assertThat(accepted.getMessageAccepted().getRoomId()).isEqualTo("room1");

    GhostEnvelope routed = awaitType(c2, MessageType.ENCRYPTED_MESSAGE);
    assertThat(routed.getEncryptedMessage().getCiphertext().toByteArray()).isEqualTo(ciphertext);
    assertThat(routed.getEncryptedMessage().getFromUserId()).isNotBlank();
  }

  @Test
  void nickname_with_emoji_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, hello("bob🙂").toByteArray());

    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isIn(ErrorCode.BAD_METADATA, ErrorCode.PROTOCOL_VIOLATION);
  }

  @Test
  void nickname_non_ascii_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, hello("josé").toByteArray());

    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isIn(ErrorCode.BAD_METADATA, ErrorCode.PROTOCOL_VIOLATION);
  }

  @Test
  void join_before_welcome_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, join("room1").toByteArray());

    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isIn(ErrorCode.PROTOCOL_VIOLATION, ErrorCode.BAD_METADATA);
  }

  @Test
  void unsupported_version_is_rejected_with_error_code() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");

    GhostEnvelope badHello = GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.2")
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.HELLO)
        .setHello(Hello.newBuilder().setNickname("alice").setClientName("it").build())
        .build();

    c.sendBinary(s, badHello.toByteArray());
    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
  }

  @Test
  void too_many_rooms_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, hello("alice").toByteArray());
    awaitType(c, MessageType.WELCOME);

    for (int i = 1; i <= 5; i++) {
      c.sendBinary(s, join("r" + i).toByteArray());
      awaitType(c, MessageType.ROOM_JOINED);
    }

    c.sendBinary(s, join("r6").toByteArray());
    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.TOO_MANY_ROOMS);
  }

  @Test
  @Timeout(5)
  void oversized_envelope_is_rejected_and_connection_closed() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");

    byte[] huge = new byte[64 * 1024 + 10];
    c.sendBinary(s, huge);

    GhostEnvelope err = awaitType(c, MessageType.ERROR, 2_000);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.BAD_ENVELOPE);
    c.awaitClose(2_000);
  }

  @Test
  @Timeout(10)
  void handshake_timeout_sends_error_then_closes_with_protocol_error_reason() throws Exception {
    WsTestClient c = new WsTestClient();
    c.connect("ws://localhost:" + port + "/ghost-bunker");

    // Wait for handshake timeout (5s) to trigger.
    GhostEnvelope err = awaitType(c, MessageType.ERROR, 7_000);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.HANDSHAKE_TIMEOUT);
    assertThat(err.getError().getMessage()).isEqualTo("handshake timeout");

    GhostEnvelope goodbye = awaitType(c, MessageType.GOODBYE, 2_000);
    assertThat(goodbye.getGoodbye().getReason().name()).isEqualTo("PROTOCOL_ERROR");
    assertThat(goodbye.getGoodbye().getMessage()).isEqualTo("protocol error");

    c.awaitClose(2_000);
  }

  @Test
  @Timeout(5)
  void invalid_protobuf_sends_bad_envelope_then_closes() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");

    byte[] junk = new byte[128];
    Arrays.fill(junk, (byte) 0x7F);
    c.sendBinary(s, junk);

    GhostEnvelope err = awaitType(c, MessageType.ERROR, 2_000);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.BAD_ENVELOPE);

    GhostEnvelope goodbye = awaitType(c, MessageType.GOODBYE, 2_000);
    assertThat(goodbye.getGoodbye().getReason().name()).isEqualTo("PROTOCOL_ERROR");
    c.awaitClose(2_000);
  }

  @Test
  void send_before_join_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, hello("alice").toByteArray());
    awaitType(c, MessageType.WELCOME);

    c.sendBinary(s, sendEncrypted("room1", new byte[] {1,2,3}).toByteArray());
    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.PROTOCOL_VIOLATION);
  }

  @Test
  void ciphertext_above_limit_is_rejected() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");
    c.sendBinary(s, hello("alice").toByteArray());
    awaitType(c, MessageType.WELCOME);
    c.sendBinary(s, join("room1").toByteArray());
    awaitType(c, MessageType.ROOM_JOINED);

    byte[] big = new byte[16 * 1024 + 1];
    c.sendBinary(s, sendEncrypted("room1", big).toByteArray());

    GhostEnvelope err = awaitType(c, MessageType.ERROR);
    assertThat(err.getError().getCode()).isEqualTo(ErrorCode.CIPHERTEXT_TOO_LARGE);
  }

  @Test
  @Timeout(5)
  void repeated_protocol_violations_close_connection() throws Exception {
    WsTestClient c = new WsTestClient();
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");

    // JOIN before HELLO is a protocol violation; after 3 in window we should close.
    c.sendBinary(s, join("room1").toByteArray());
    awaitType(c, MessageType.ERROR);
    c.sendBinary(s, join("room1").toByteArray());
    awaitType(c, MessageType.ERROR);
    c.sendBinary(s, join("room1").toByteArray());
    awaitType(c, MessageType.ERROR);

    GhostEnvelope goodbye = awaitType(c, MessageType.GOODBYE, 2000);
    assertThat(goodbye.getGoodbye().getReason().name()).isEqualTo("TOO_MANY_VIOLATIONS");
    c.awaitClose(2000);
  }

  private GhostEnvelope hello(String nickname) {
    return TestEnvelopes.hello(nickname);
  }

  private GhostEnvelope join(String roomId) {
    return TestEnvelopes.join(roomId);
  }

  private GhostEnvelope sendEncrypted(String roomId, byte[] ciphertext) {
    return TestEnvelopes.sendEncrypted(roomId, ciphertext);
  }

  private void awaitAtLeast(WsTestClient c, int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline) {
      if (c.drainBinaryMessages().size() >= n) return;
      Thread.sleep(10);
    }
  }

  private GhostEnvelope awaitType(WsTestClient c, MessageType type) throws InterruptedException {
    return awaitType(c, type, 2000);
  }

  private GhostEnvelope awaitType(WsTestClient c, MessageType type, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    java.util.EnumMap<MessageType, Integer> seen = new java.util.EnumMap<>(MessageType.class);
    while (System.currentTimeMillis() < deadline) {
      List<byte[]> msgs = c.drainBinaryMessages();
      for (byte[] m : msgs) {
        try {
          GhostEnvelope env = GhostEnvelope.parseFrom(m);
          seen.put(env.getType(), seen.getOrDefault(env.getType(), 0) + 1);
          if (env.getType() == type) return env;
        } catch (Exception ignored) {}
      }
      Thread.sleep(10);
    }
    throw new AssertionError("timeout waiting for " + type + "; seen=" + seen);
  }
}

