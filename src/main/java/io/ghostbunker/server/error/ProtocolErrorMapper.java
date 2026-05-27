package io.ghostbunker.server.error;

import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.protocol.v1.ErrorMessage;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.server.protocol.ProtocolLimits;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
public class ProtocolErrorMapper {
  private final Clock clock = Clock.systemUTC();
  private final ProtocolLimits limits;

  public ProtocolErrorMapper(ProtocolLimits limits) {
    this.limits = limits;
  }

  public GhostEnvelope error(ErrorCode code, String sanitizedMessage, String requestId, Integer retryAfterMs) {
    ErrorMessage.Builder error = ErrorMessage.newBuilder()
        .setCode(code)
        .setMessage(sanitize(sanitizedMessage));
    if (requestId != null && !requestId.isBlank()) {
      error.setRequestId(requestId);
    }
    if (retryAfterMs != null && retryAfterMs > 0) {
      error.setRetryAfterMs(retryAfterMs);
    }

    return GhostEnvelope.newBuilder()
        .setProtocol(limits.expectedProtocol())
        .setVersion(limits.expectedVersion())
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(clock.millis())
        .setType(MessageType.ERROR)
        .setError(error.build())
        .build();
  }

  private String sanitize(String msg) {
    if (msg == null) return "";
    String trimmed = msg.trim();
    if (trimmed.length() > 160) return trimmed.substring(0, 160);
    return trimmed;
  }
}

