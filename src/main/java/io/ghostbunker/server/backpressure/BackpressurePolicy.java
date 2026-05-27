package io.ghostbunker.server.backpressure;

import io.ghostbunker.server.session.GhostSession;

/**
 * Outbound queue limits per connection. Prevents slow peers from unbounded buffering.
 */
public interface BackpressurePolicy {

  boolean canEnqueue(GhostSession session, int messageBytes);
}
