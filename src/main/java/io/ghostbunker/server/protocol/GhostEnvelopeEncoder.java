package io.ghostbunker.server.protocol;

import io.ghostbunker.protocol.v1.GhostEnvelope;

public final class GhostEnvelopeEncoder {
  private final ProtocolLimits limits;

  public GhostEnvelopeEncoder(ProtocolLimits limits) {
    this.limits = limits;
  }

  public byte[] encode(GhostEnvelope envelope) {
    if (envelope == null) {
      throw new IllegalArgumentException("envelope is null");
    }
    byte[] bytes = envelope.toByteArray();
    if (bytes.length > limits.maxEnvelopeBytes()) {
      throw new IllegalArgumentException("encoded envelope too large");
    }
    return bytes;
  }
}

