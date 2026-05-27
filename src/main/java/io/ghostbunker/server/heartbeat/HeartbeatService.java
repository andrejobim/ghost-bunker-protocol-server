package io.ghostbunker.server.heartbeat;

import io.ghostbunker.protocol.v1.DisconnectReason;
import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.protocol.v1.ErrorMessage;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Goodbye;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.Ping;
import io.ghostbunker.server.logging.SanitizedProtocolLogger;
import io.ghostbunker.server.observability.GhostBunkerMetrics;
import io.ghostbunker.server.protocol.GhostEnvelopeEncoder;
import io.ghostbunker.server.protocol.ProtocolLimits;
import io.ghostbunker.server.session.GhostSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class HeartbeatService {
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
    Thread t = new Thread(r, "ghostbunker-heartbeat");
    t.setDaemon(true);
    return t;
  });

  private final Clock clock = Clock.systemUTC();
  private final GhostEnvelopeEncoder encoder;
  private final SanitizedProtocolLogger logger;
  private final ProtocolLimits limits;
  private final GhostBunkerMetrics metrics;

  public HeartbeatService(
      SanitizedProtocolLogger logger,
      ProtocolLimits limits,
      GhostBunkerMetrics metrics
  ) {
    this.logger = logger;
    this.limits = limits;
    this.metrics = metrics;
    this.encoder = new GhostEnvelopeEncoder(limits);
  }

  public void start(GhostSession session) {
    scheduler.scheduleAtFixedRate(() -> tick(session), limits.pingIntervalMs(),
        limits.pingIntervalMs(), TimeUnit.MILLISECONDS);
    scheduler.schedule(() -> enforceHandshakeTimeout(session), limits.handshakeTimeoutMs(), TimeUnit.MILLISECONDS);
  }

  private void enforceHandshakeTimeout(GhostSession session) {
    if (!session.wsSession().isOpen()) return;
    if (session.state() == io.ghostbunker.server.session.GhostSessionState.AWAITING_HELLO) {
      sendHandshakeTimeoutError(session);
      closeWithGoodbye(session, DisconnectReason.PROTOCOL_ERROR);
    }
  }

  private void sendHandshakeTimeoutError(GhostSession session) {
    try {
      GhostEnvelope err = GhostEnvelope.newBuilder()
          .setProtocol(limits.expectedProtocol())
          .setVersion(limits.expectedVersion())
          .setMessageId(UUID.randomUUID().toString())
          .setTimestampMs(clock.millis())
          .setType(MessageType.ERROR)
          .setError(ErrorMessage.newBuilder()
              .setCode(ErrorCode.HANDSHAKE_TIMEOUT)
              .setMessage("handshake timeout")
              .build())
          .build();
      session.wsSession().sendMessage(new BinaryMessage(encoder.encode(err)));
      metrics.onErrorEmitted(ErrorCode.HANDSHAKE_TIMEOUT);
    } catch (Exception e) {
      logger.warn("handshake timeout error send failed (sanitized)");
    }
  }

  private void tick(GhostSession session) {
    if (!session.wsSession().isOpen()) return;
    long now = clock.millis();

    long idleFor = now - session.lastActivityMs();
    if (idleFor > limits.idleTimeoutMs()) {
      closeWithGoodbye(session, DisconnectReason.IDLE_TIMEOUT);
      return;
    }

    long lastPing = session.lastPingSentAtMs();
    long lastPong = session.lastPongAtMs();
    if (lastPing > 0 && (lastPong < lastPing) && (now - lastPing > limits.pongTimeoutMs())) {
      closeWithGoodbye(session, DisconnectReason.PONG_TIMEOUT);
      return;
    }

    sendPing(session);
  }

  private void sendPing(GhostSession session) {
    try {
      String nonce = UUID.randomUUID().toString();
      GhostEnvelope ping = GhostEnvelope.newBuilder()
          .setProtocol(limits.expectedProtocol())
          .setVersion(limits.expectedVersion())
          .setMessageId(UUID.randomUUID().toString())
          .setTimestampMs(clock.millis())
          .setType(MessageType.PING)
          .setPing(Ping.newBuilder().setNonce(nonce).build())
          .build();
      byte[] bytes = encoder.encode(ping);
      session.setLastPingSentAtMs(clock.millis());
      session.wsSession().sendMessage(new BinaryMessage(bytes));
    } catch (Exception e) {
      logger.warn("heartbeat ping failed (sanitized)");
      safeClose(session, CloseStatus.SERVER_ERROR);
    }
  }

  public void closeWithGoodbye(GhostSession session, DisconnectReason reason) {
    String sanitizedMessage = GoodbyeReasonMessages.canonical(reason);
    metrics.onGoodbyeEmitted(reason);
    try {
      GhostEnvelope goodbye = GhostEnvelope.newBuilder()
          .setProtocol(limits.expectedProtocol())
          .setVersion(limits.expectedVersion())
          .setMessageId(UUID.randomUUID().toString())
          .setTimestampMs(clock.millis())
          .setType(MessageType.GOODBYE)
          .setGoodbye(Goodbye.newBuilder().setReason(reason).setMessage(sanitizedMessage).build())
          .build();
      WebSocketSession target = session.rawWsSession();
      if (target.isOpen()) {
        synchronized (target) {
          target.sendMessage(new BinaryMessage(encoder.encode(goodbye)));
        }
      }
    } catch (Exception ignored) {
      // best-effort
    } finally {
      scheduler.schedule(() -> safeClose(session, CloseStatus.NORMAL), 50, TimeUnit.MILLISECONDS);
    }
  }

  private void safeClose(GhostSession session, CloseStatus status) {
    try {
      session.rawWsSession().close(status);
    } catch (Exception ignored) {
      // best-effort
    }
  }
}
