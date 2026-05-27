package io.ghostbunker.server.backpressure;

import io.ghostbunker.server.protocol.ProtocolLimits;
import io.ghostbunker.server.session.GhostSession;
import org.springframework.stereotype.Component;

@Component
public class DefaultBackpressurePolicy implements BackpressurePolicy {
  private final ProtocolLimits limits;

  public DefaultBackpressurePolicy(ProtocolLimits limits) {
    this.limits = limits;
  }

  @Override
  public boolean canEnqueue(GhostSession session, int messageBytes) {
    if (messageBytes <= 0) return false;
    if (session.outboundQueuedMessages() + 1 > limits.maxOutboundQueueMessages()) return false;
    return session.outboundPendingBytes() + messageBytes <= limits.maxOutboundPendingBytes();
  }
}
