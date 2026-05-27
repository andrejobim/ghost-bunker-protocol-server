package io.ghostbunker.server.shutdown;

import io.ghostbunker.protocol.v1.DisconnectReason;
import io.ghostbunker.server.config.GhostBunkerProperties;
import io.ghostbunker.server.heartbeat.HeartbeatService;
import io.ghostbunker.server.logging.SanitizedProtocolLogger;
import io.ghostbunker.server.session.GhostSession;
import io.ghostbunker.server.session.SessionRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * On JVM shutdown, broadcasts {@link DisconnectReason#SERVER_SHUTDOWN} GOODBYE to every open
 * session before the process exits.
 */
@Component
public class GracefulShutdownService {
  private final SessionRegistry sessionRegistry;
  private final HeartbeatService heartbeatService;
  private final GhostBunkerProperties properties;
  private final SanitizedProtocolLogger logger;

  public GracefulShutdownService(
      SessionRegistry sessionRegistry,
      HeartbeatService heartbeatService,
      GhostBunkerProperties properties,
      SanitizedProtocolLogger logger
  ) {
    this.sessionRegistry = sessionRegistry;
    this.heartbeatService = heartbeatService;
    this.properties = properties;
    this.logger = logger;
  }

  @PreDestroy
  public void onShutdown() {
    List<GhostSession> sessions = new ArrayList<>(sessionRegistry.snapshot());
    if (sessions.isEmpty()) {
      return;
    }
    logger.info("graceful shutdown started (sanitized)");
    for (GhostSession session : sessions) {
      if (session.wsSession().isOpen()) {
        heartbeatService.closeWithGoodbye(session, DisconnectReason.SERVER_SHUTDOWN);
      }
    }
    int graceMs = properties.getShutdown().getGracePeriodMs();
    if (graceMs > 0) {
      try {
        Thread.sleep(graceMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    logger.info("graceful shutdown finished (sanitized)");
  }
}
