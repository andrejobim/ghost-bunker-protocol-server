package io.ghostbunker.server.backpressure;

import io.ghostbunker.server.protocol.ProtocolLimits;
import io.ghostbunker.server.session.GhostSession;

public final class OutboundQueuePolicy {
  private final ProtocolLimits limits;

  public OutboundQueuePolicy(ProtocolLimits limits) {
    this.limits = limits;
  }

  public boolean canEnqueue(GhostSession session, int messageBytes) {
    if (messageBytes <= 0) return false;
    if (session.outboundQueuedMessages() + 1 > limits.maxOutboundQueueMessages()) return false;
    return session.outboundPendingBytes() + messageBytes <= limits.maxOutboundPendingBytes();
  }
}

