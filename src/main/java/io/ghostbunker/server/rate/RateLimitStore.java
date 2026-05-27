package io.ghostbunker.server.rate;

import io.ghostbunker.server.session.GhostSession;

/**
 * Per-connection command and message rate limits. A distributed store may coordinate limits
 * without recording user identity or message payloads.
 */
public interface RateLimitStore {

  boolean allowCommand(GhostSession session);

  boolean allowMessage(GhostSession session);
}
