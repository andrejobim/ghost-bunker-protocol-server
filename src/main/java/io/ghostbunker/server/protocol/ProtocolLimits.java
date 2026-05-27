package io.ghostbunker.server.protocol;

import io.ghostbunker.server.config.GhostBunkerProperties;
import org.springframework.stereotype.Component;

@Component
public class ProtocolLimits {
  private final GhostBunkerProperties.Limits limits;

  public ProtocolLimits(GhostBunkerProperties properties) {
    this.limits = properties.getLimits();
  }

  public String expectedProtocol() {
    return limits.getExpectedProtocol();
  }

  public String expectedVersion() {
    return limits.getExpectedVersion();
  }

  public int maxEnvelopeBytes() {
    return limits.getMaxEnvelopeBytes();
  }

  public int maxCiphertextBytes() {
    return limits.getMaxCiphertextBytes();
  }

  public int maxNicknameChars() {
    return limits.getMaxNicknameChars();
  }

  public int maxRoomIdChars() {
    return limits.getMaxRoomIdChars();
  }

  public int maxRoomsPerConnection() {
    return limits.getMaxRoomsPerConnection();
  }

  public int maxMessagesPerMinute() {
    return limits.getMaxMessagesPerMinute();
  }

  public int maxCommandsPerMinute() {
    return limits.getMaxCommandsPerMinute();
  }

  public int maxOutboundQueueMessages() {
    return limits.getMaxOutboundQueueMessages();
  }

  public int maxOutboundPendingBytes() {
    return limits.getMaxOutboundPendingBytes();
  }

  public int handshakeTimeoutMs() {
    return limits.getHandshakeTimeoutMs();
  }

  public int pingIntervalMs() {
    return limits.getPingIntervalMs();
  }

  public int pongTimeoutMs() {
    return limits.getPongTimeoutMs();
  }

  public int idleTimeoutMs() {
    return limits.getIdleTimeoutMs();
  }

  public int maxViolationsInWindow() {
    return limits.getMaxViolationsInWindow();
  }

  public int violationWindowMs() {
    return limits.getViolationWindowMs();
  }

  public int sendTimeLimitMs() {
    return limits.getSendTimeLimitMs();
  }
}

