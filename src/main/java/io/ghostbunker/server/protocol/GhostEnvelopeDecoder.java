package io.ghostbunker.server.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import io.ghostbunker.protocol.v1.GhostEnvelope;

public final class GhostEnvelopeDecoder {
  private final ProtocolLimits limits;

  public GhostEnvelopeDecoder(ProtocolLimits limits) {
    this.limits = limits;
  }

  public GhostEnvelope decode(byte[] bytes) throws InvalidProtocolBufferException {
    if (bytes == null) {
      throw new InvalidProtocolBufferException("null payload");
    }
    if (bytes.length > limits.maxEnvelopeBytes()) {
      throw new InvalidProtocolBufferException("envelope too large");
    }
    return GhostEnvelope.parseFrom(bytes);
  }
}

