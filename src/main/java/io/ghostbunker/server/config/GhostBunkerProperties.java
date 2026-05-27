package io.ghostbunker.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ghostbunker")
public class GhostBunkerProperties {
  private final Limits limits = new Limits();
  private final WebSocket websocket = new WebSocket();
  private final Shutdown shutdown = new Shutdown();

  public Limits getLimits() {
    return limits;
  }

  public WebSocket getWebsocket() {
    return websocket;
  }

  public Shutdown getShutdown() {
    return shutdown;
  }

  public static class WebSocket {
    /**
     * Origins permitted for the WebSocket handshake ({@code Origin} header). Use {@code *}
     * only for local development. Staging/production should list explicit HTTPS origins.
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of("*"));

    /**
     * When non-blank, the handshake must request this RFC 6455 subprotocol
     * ({@code Sec-WebSocket-Protocol}), e.g. {@code ghost-bunker.v0.1}.
     */
    private String requiredSubprotocol = "";

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
      this.allowedOrigins = allowedOrigins != null ? new ArrayList<>(allowedOrigins) : new ArrayList<>();
    }

    public String getRequiredSubprotocol() {
      return requiredSubprotocol;
    }

    public void setRequiredSubprotocol(String requiredSubprotocol) {
      this.requiredSubprotocol = requiredSubprotocol != null ? requiredSubprotocol : "";
    }

    public boolean enforcesSubprotocol() {
      return requiredSubprotocol != null && !requiredSubprotocol.isBlank();
    }
  }

  public static class Shutdown {
    /** Grace period (ms) after broadcasting SERVER_SHUTDOWN before the JVM exits. */
    private int gracePeriodMs = 2_000;

    public int getGracePeriodMs() {
      return gracePeriodMs;
    }

    public void setGracePeriodMs(int gracePeriodMs) {
      this.gracePeriodMs = gracePeriodMs;
    }
  }

  public static class Limits {
    private String expectedProtocol = "ghost-bunker";
    private String expectedVersion = "0.1";

    private int maxEnvelopeBytes = 64 * 1024;
    private int maxCiphertextBytes = 16 * 1024;

    private int maxNicknameChars = 32;
    private int maxRoomIdChars = 64;

    private int maxRoomsPerConnection = 5;
    private int maxMessagesPerMinute = 20;
    private int maxCommandsPerMinute = 60;

    private int maxOutboundQueueMessages = 100;
    private int maxOutboundPendingBytes = 1024 * 1024;

    private int handshakeTimeoutMs = 5_000;
    private int pingIntervalMs = 30_000;
    private int pongTimeoutMs = 10_000;
    private int idleTimeoutMs = 90_000;

    private int maxViolationsInWindow = 3;
    private int violationWindowMs = 60_000;

    private int sendTimeLimitMs = 2_000;

    public String getExpectedProtocol() {
      return expectedProtocol;
    }

    public void setExpectedProtocol(String expectedProtocol) {
      this.expectedProtocol = expectedProtocol;
    }

    public String getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(String expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    public int getMaxEnvelopeBytes() {
      return maxEnvelopeBytes;
    }

    public void setMaxEnvelopeBytes(int maxEnvelopeBytes) {
      this.maxEnvelopeBytes = maxEnvelopeBytes;
    }

    public int getMaxCiphertextBytes() {
      return maxCiphertextBytes;
    }

    public void setMaxCiphertextBytes(int maxCiphertextBytes) {
      this.maxCiphertextBytes = maxCiphertextBytes;
    }

    public int getMaxNicknameChars() {
      return maxNicknameChars;
    }

    public void setMaxNicknameChars(int maxNicknameChars) {
      this.maxNicknameChars = maxNicknameChars;
    }

    public int getMaxRoomIdChars() {
      return maxRoomIdChars;
    }

    public void setMaxRoomIdChars(int maxRoomIdChars) {
      this.maxRoomIdChars = maxRoomIdChars;
    }

    public int getMaxRoomsPerConnection() {
      return maxRoomsPerConnection;
    }

    public void setMaxRoomsPerConnection(int maxRoomsPerConnection) {
      this.maxRoomsPerConnection = maxRoomsPerConnection;
    }

    public int getMaxMessagesPerMinute() {
      return maxMessagesPerMinute;
    }

    public void setMaxMessagesPerMinute(int maxMessagesPerMinute) {
      this.maxMessagesPerMinute = maxMessagesPerMinute;
    }

    public int getMaxCommandsPerMinute() {
      return maxCommandsPerMinute;
    }

    public void setMaxCommandsPerMinute(int maxCommandsPerMinute) {
      this.maxCommandsPerMinute = maxCommandsPerMinute;
    }

    public int getMaxOutboundQueueMessages() {
      return maxOutboundQueueMessages;
    }

    public void setMaxOutboundQueueMessages(int maxOutboundQueueMessages) {
      this.maxOutboundQueueMessages = maxOutboundQueueMessages;
    }

    public int getMaxOutboundPendingBytes() {
      return maxOutboundPendingBytes;
    }

    public void setMaxOutboundPendingBytes(int maxOutboundPendingBytes) {
      this.maxOutboundPendingBytes = maxOutboundPendingBytes;
    }

    public int getHandshakeTimeoutMs() {
      return handshakeTimeoutMs;
    }

    public void setHandshakeTimeoutMs(int handshakeTimeoutMs) {
      this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    public int getPingIntervalMs() {
      return pingIntervalMs;
    }

    public void setPingIntervalMs(int pingIntervalMs) {
      this.pingIntervalMs = pingIntervalMs;
    }

    public int getPongTimeoutMs() {
      return pongTimeoutMs;
    }

    public void setPongTimeoutMs(int pongTimeoutMs) {
      this.pongTimeoutMs = pongTimeoutMs;
    }

    public int getIdleTimeoutMs() {
      return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(int idleTimeoutMs) {
      this.idleTimeoutMs = idleTimeoutMs;
    }

    public int getMaxViolationsInWindow() {
      return maxViolationsInWindow;
    }

    public void setMaxViolationsInWindow(int maxViolationsInWindow) {
      this.maxViolationsInWindow = maxViolationsInWindow;
    }

    public int getViolationWindowMs() {
      return violationWindowMs;
    }

    public void setViolationWindowMs(int violationWindowMs) {
      this.violationWindowMs = violationWindowMs;
    }

    public int getSendTimeLimitMs() {
      return sendTimeLimitMs;
    }

    public void setSendTimeLimitMs(int sendTimeLimitMs) {
      this.sendTimeLimitMs = sendTimeLimitMs;
    }
  }
}

