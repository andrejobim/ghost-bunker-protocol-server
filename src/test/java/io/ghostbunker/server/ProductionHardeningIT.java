package io.ghostbunker.server;

import io.ghostbunker.protocol.v1.DisconnectReason;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.MessageType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import io.ghostbunker.server.shutdown.GracefulShutdownService;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "ghostbunker.websocket.allowed-origins=*",
    "ghostbunker.websocket.required-subprotocol=ghost-bunker.v0.1",
    "ghostbunker.shutdown.grace-period-ms=500"
})
class ProductionHardeningIT {

  private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
      "ip", "session_id", "user_id", "room_id", "nickname"
  );

  @LocalServerPort
  int port;

  @Autowired
  MeterRegistry meterRegistry;

  @Autowired
  GracefulShutdownService gracefulShutdownService;

  @Test
  void rejects_handshake_without_required_subprotocol() {
    WsTestClient client = new WsTestClient();
    assertThatThrownBy(() -> client.connect(wsUrl()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void accepts_handshake_with_required_subprotocol() throws Exception {
    WsTestClient client = subprotocolClient();
    WebSocketSession session = client.connect(wsUrl());
    client.sendBinary(session, TestEnvelopes.hello("alice").toByteArray());
    GhostEnvelope welcome = awaitType(client, MessageType.WELCOME);
    assertThat(welcome.getWelcome().getSessionId()).isNotBlank();
    session.close();
  }

  @Test
  @DirtiesContext
  void graceful_shutdown_emits_server_shutdown_goodbye() throws Exception {
    WsTestClient client = new WsTestClient(true, 0, subprotocolHeaders());
    WebSocketSession session = client.connect(wsUrl());
    client.sendBinary(session, TestEnvelopes.hello("alice").toByteArray());
    awaitType(client, MessageType.WELCOME);

    gracefulShutdownService.onShutdown();

    GhostEnvelope goodbye = awaitType(client, MessageType.GOODBYE, 5_000);
    assertThat(goodbye.getGoodbye().getReason()).isEqualTo(DisconnectReason.SERVER_SHUTDOWN);
    assertThat(goodbye.getGoodbye().getMessage()).isEqualTo("server shutdown");
  }

  @Test
  void metrics_do_not_use_forbidden_tags() {
    List<String> violations = meterRegistry.getMeters().stream()
        .map(Meter::getId)
        .flatMap(id -> id.getTags().stream())
        .map(tag -> tag.getKey().toLowerCase())
        .filter(FORBIDDEN_TAG_KEYS::contains)
        .distinct()
        .collect(Collectors.toList());
    assertThat(violations)
        .as("metrics must not label by ip/session_id/user_id/room_id/nickname")
        .isEmpty();
  }

  private static WsTestClient subprotocolClient() {
    return new WsTestClient(subprotocolHeaders());
  }

  private static WebSocketHttpHeaders subprotocolHeaders() {
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.setSecWebSocketProtocol(List.of("ghost-bunker.v0.1"));
    return headers;
  }

  private String wsUrl() {
    return "ws://localhost:" + port + "/ghost-bunker";
  }

  private static GhostEnvelope awaitType(WsTestClient client, MessageType type) throws InterruptedException {
    return awaitType(client, type, 2_000);
  }

  private static GhostEnvelope awaitType(WsTestClient client, MessageType type, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      for (byte[] raw : client.drainBinaryMessages()) {
        try {
          GhostEnvelope env = GhostEnvelope.parseFrom(raw);
          if (env.getType() == type) {
            return env;
          }
        } catch (Exception ignored) {
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("timeout waiting for " + type);
  }
}
