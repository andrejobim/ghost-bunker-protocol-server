package io.ghostbunker.server.heartbeat;

import io.ghostbunker.protocol.v1.DisconnectReason;

import java.util.EnumMap;
import java.util.Map;

/**
 * Canonical, sanitized {@code Goodbye.message} strings keyed by {@link DisconnectReason}.
 *
 * <p>Privacy-Max: the GOODBYE message on the wire is fixed text derived from the reason enum.
 * Callers may pass a diagnostic string, but the canonical value here is preferred to keep wire
 * content predictable and free of client input.
 */
final class GoodbyeReasonMessages {
  private static final Map<DisconnectReason, String> CANONICAL;
  private static final String FALLBACK = "disconnect";

  static {
    Map<DisconnectReason, String> m = new EnumMap<>(DisconnectReason.class);
    m.put(DisconnectReason.DISCONNECT_REASON_UNSPECIFIED, FALLBACK);
    m.put(DisconnectReason.CLIENT_REQUEST,                "client requested");
    m.put(DisconnectReason.SERVER_SHUTDOWN,               "server shutdown");
    m.put(DisconnectReason.IDLE_TIMEOUT,                  "idle timeout");
    m.put(DisconnectReason.PONG_TIMEOUT,                  "pong timeout");
    m.put(DisconnectReason.PROTOCOL_ERROR,                "protocol error");
    m.put(DisconnectReason.TOO_MANY_VIOLATIONS,           "too many violations");
    m.put(DisconnectReason.POLICY_ERROR,                  "policy error");
    CANONICAL = m;
  }

  private GoodbyeReasonMessages() {}

  static String canonical(DisconnectReason reason) {
    return CANONICAL.getOrDefault(reason, FALLBACK);
  }
}
