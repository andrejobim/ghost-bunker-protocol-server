package io.ghostbunker.server.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Optionally enforces {@link GhostBunkerProperties.WebSocket#getRequiredSubprotocol()} during
 * the WebSocket upgrade.
 */
final class SubprotocolHandshakeInterceptor implements HandshakeInterceptor {
  private final String requiredSubprotocol;

  SubprotocolHandshakeInterceptor(GhostBunkerProperties properties) {
    this.requiredSubprotocol = properties.getWebsocket().getRequiredSubprotocol().trim();
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes
  ) {
    if (requiredSubprotocol.isEmpty()) {
      return true;
    }
    String offered = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
    if (offered == null || offered.isBlank()) {
      return false;
    }
    List<String> protocols = Arrays.stream(offered.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
    if (!protocols.contains(requiredSubprotocol)) {
      return false;
    }
    attributes.put("ghostbunker.negotiatedSubprotocol", requiredSubprotocol);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception
  ) {
    if (exception != null || requiredSubprotocol.isEmpty()) {
      return;
    }
    response.getHeaders().set("Sec-WebSocket-Protocol", requiredSubprotocol);
  }
}
