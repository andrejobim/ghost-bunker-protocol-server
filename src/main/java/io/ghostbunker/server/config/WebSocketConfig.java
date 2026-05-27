package io.ghostbunker.server.config;

import io.ghostbunker.server.handler.GhostBunkerWebSocketHandler;
import io.ghostbunker.server.protocol.ProtocolLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final GhostBunkerWebSocketHandler handler;
  private final ProtocolLimits limits;
  private final GhostBunkerProperties properties;

  public WebSocketConfig(
      GhostBunkerWebSocketHandler handler,
      ProtocolLimits limits,
      GhostBunkerProperties properties
  ) {
    this.handler = handler;
    this.limits = limits;
    this.properties = properties;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    String[] origins = properties.getWebsocket().getAllowedOrigins().toArray(String[]::new);
    var registration = registry.addHandler(handler, "/ghost-bunker")
        .setAllowedOrigins(origins);
    if (properties.getWebsocket().enforcesSubprotocol()) {
      registration.addInterceptors(new SubprotocolHandshakeInterceptor(properties));
    }
  }

  /**
   * Configures the embedded WebSocket container so that binary frames slightly larger than the
   * protocol envelope limit are still delivered to the handler. Without this, Tomcat's default
   * 8KB buffer would close the connection at the transport layer before the handler can reject
   * the frame with a sanitized protocol-level ERROR (BAD_ENVELOPE / CIPHERTEXT_TOO_LARGE).
   *
   * We keep a small headroom over {@code maxEnvelopeBytes} so that oversize frames are accepted
   * by the container, decoded enough to be rejected, and the protocol error path can run.
   */
  @Bean
  public ServletServerContainerFactoryBean createWebSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    int containerBufferBytes = limits.maxEnvelopeBytes() + 16 * 1024;
    container.setMaxBinaryMessageBufferSize(containerBufferBytes);
    container.setMaxTextMessageBufferSize(1024);
    return container;
  }
}
