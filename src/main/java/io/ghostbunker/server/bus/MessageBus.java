package io.ghostbunker.server.bus;

/**
 * Cross-node envelope fan-out. The reference single-node server uses a no-op implementation;
 * future releases may publish opaque ciphertext bytes to a pub/sub layer without storing them.
 */
public interface MessageBus {

  /**
   * Notify other nodes that an encrypted envelope was accepted for {@code roomId}.
   * Implementations must not persist {@code envelopeBytes}.
   */
  void publish(String roomId, byte[] envelopeBytes);
}
