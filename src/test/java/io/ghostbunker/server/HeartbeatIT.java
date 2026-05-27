package io.ghostbunker.server;

import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeat liveness integration test. The slow-client backpressure behaviour is covered by
 * deterministic unit tests in {@link BackpressureUnitTest} rather than here, because reliably
 * triggering it from a real WebSocket client on localhost depends on Tyrus's threading model and
 * kernel TCP buffer sizing, neither of which is contractually part of the protocol.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "ghostbunker.limits.ping-interval-ms=100",
    "ghostbunker.limits.pong-timeout-ms=200",
    "ghostbunker.limits.idle-timeout-ms=1000"
})
class HeartbeatIT {
  @LocalServerPort
  int port;

  @Test
  @Timeout(5)
  void ping_pong_keeps_connection_alive() throws Exception {
    WsTestClient c = new WsTestClient(true, 0);
    WebSocketSession s = c.connect("ws://localhost:" + port + "/ghost-bunker");

    // Wait for at least one server ping and ensure we are still connected after a short while.
    GhostEnvelope ping = awaitType(c, MessageType.PING, 1500);
    assertThat(ping.getPing().getNonce()).isNotBlank();

    Thread.sleep(500);
    assertThat(s.isOpen()).isTrue();
  }

  private GhostEnvelope awaitType(WsTestClient c, MessageType type, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    java.util.EnumMap<MessageType, Integer> seen = new java.util.EnumMap<>(MessageType.class);
    while (System.currentTimeMillis() < deadline) {
      for (byte[] m : c.drainBinaryMessages()) {
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

