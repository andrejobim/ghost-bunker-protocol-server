package io.ghostbunker.server.session;

import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Optional;

/**
 * Tracks live WebSocket sessions on this node. A distributed deployment may back this with
 * a cluster-wide session directory while keeping ciphertext and payloads off shared storage.
 */
public interface SessionRegistry {

  GhostSession create(WebSocketSession decoratedWsSession, WebSocketSession rawWsSession);

  Optional<GhostSession> get(WebSocketSession wsSession);

  void remove(WebSocketSession wsSession);

  /** Immutable snapshot of live sessions (for graceful shutdown). */
  Collection<GhostSession> snapshot();

  int activeCount();
}
