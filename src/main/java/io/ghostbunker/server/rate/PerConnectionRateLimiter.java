package io.ghostbunker.server.rate;

import io.ghostbunker.server.session.GhostSession;
import org.springframework.stereotype.Component;

@Component
public class PerConnectionRateLimiter {
  public boolean allowCommand(GhostSession session) {
    return session.tryIncrementCommands();
  }

  public boolean allowMessage(GhostSession session) {
    return session.tryIncrementMessages();
  }
}

