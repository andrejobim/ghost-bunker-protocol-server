package io.ghostbunker.server.rate;

import io.ghostbunker.server.session.GhostSession;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRateLimitStore implements RateLimitStore {

  @Override
  public boolean allowCommand(GhostSession session) {
    return session.tryIncrementCommands();
  }

  @Override
  public boolean allowMessage(GhostSession session) {
    return session.tryIncrementMessages();
  }
}
