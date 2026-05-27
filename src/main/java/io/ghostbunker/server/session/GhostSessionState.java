package io.ghostbunker.server.session;

public enum GhostSessionState {
  CONNECTED,
  AWAITING_HELLO,
  ESTABLISHED,
  IN_ROOMS,
  CLOSING,
  CLOSED
}

