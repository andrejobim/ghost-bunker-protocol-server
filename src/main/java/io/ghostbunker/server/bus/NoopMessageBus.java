package io.ghostbunker.server.bus;

import org.springframework.stereotype.Component;

/** Single-node default: no cross-node fan-out. */
@Component
public class NoopMessageBus implements MessageBus {

  @Override
  public void publish(String roomId, byte[] envelopeBytes) {
    // Intentionally empty — local delivery is handled by MessageRouter on this JVM.
  }
}
