package io.ghostbunker.server.session;

import io.ghostbunker.server.protocol.ProtocolLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionRegistry implements SessionRegistry {
  private final Map<String, GhostSession> sessionsByWsId = new ConcurrentHashMap<>();
  private final Clock clock;
  private final ProtocolLimits limits;

  @Autowired
  public InMemorySessionRegistry(ProtocolLimits limits) {
    this.clock = Clock.systemUTC();
    this.limits = limits;
  }

  public InMemorySessionRegistry(Clock clock, ProtocolLimits limits) {
    this.clock = clock;
    this.limits = limits;
  }

  @Override
  public GhostSession create(WebSocketSession decoratedWsSession, WebSocketSession rawWsSession) {
    String sessionId = UUID.randomUUID().toString();
    String userId = UUID.randomUUID().toString();
    GhostSession session = new GhostSession(
        decoratedWsSession,
        rawWsSession,
        sessionId,
        userId,
        clock,
        limits.maxCommandsPerMinute(),
        limits.maxMessagesPerMinute(),
        limits.violationWindowMs()
    );
    sessionsByWsId.put(decoratedWsSession.getId(), session);
    return session;
  }

  @Override
  public Optional<GhostSession> get(WebSocketSession wsSession) {
    return Optional.ofNullable(sessionsByWsId.get(wsSession.getId()));
  }

  @Override
  public void remove(WebSocketSession wsSession) {
    sessionsByWsId.remove(wsSession.getId());
  }

  @Override
  public java.util.Collection<GhostSession> snapshot() {
    return java.util.List.copyOf(sessionsByWsId.values());
  }

  @Override
  public int activeCount() {
    return sessionsByWsId.size();
  }
}
