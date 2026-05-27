package io.ghostbunker.server.routing;

import io.ghostbunker.protocol.v1.GhostEnvelope;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * Resolves local delivery targets for an envelope. In a multi-node deployment, remote recipients
 * are reached via {@link io.ghostbunker.server.bus.MessageBus} instead of this registry alone.
 */
public interface MessageRouter {

  List<WebSocketSession> recipientsExcludingSender(String roomId, WebSocketSession sender);

  String routeScope(GhostEnvelope env);
}
