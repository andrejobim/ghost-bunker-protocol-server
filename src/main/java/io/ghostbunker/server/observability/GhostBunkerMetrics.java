package io.ghostbunker.server.observability;

import io.ghostbunker.protocol.v1.DisconnectReason;
import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.server.room.RoomRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity-free aggregate metrics for staging operators. Tags are limited to fixed enums
 * ({@link ErrorCode}, {@link DisconnectReason}) — never session, user, room, nickname, or IP.
 */
@Component
public class GhostBunkerMetrics {
  private final Counter connectionsOpened;
  private final Counter connectionsClosed;
  private final AtomicLong activeConnections = new AtomicLong();
  private final Counter bytesRouted;
  private final Counter slowClientCloses;
  private final Map<ErrorCode, Counter> errorsByCode;
  private final Map<DisconnectReason, Counter> goodbyeByReason;
  private final RoomRegistry roomRegistry;

  public GhostBunkerMetrics(
      MeterRegistry registry,
      RoomRegistry roomRegistry
  ) {
    this.roomRegistry = roomRegistry;

    connectionsOpened = Counter.builder("ghostbunker.connections.opened")
        .description("Total WebSocket connections accepted")
        .register(registry);
    connectionsClosed = Counter.builder("ghostbunker.connections.closed")
        .description("Total WebSocket connections closed")
        .register(registry);
    Gauge.builder("ghostbunker.connections.active", activeConnections, AtomicLong::get)
        .description("Currently open WebSocket connections")
        .register(registry);
    bytesRouted = Counter.builder("ghostbunker.bytes.routed")
        .description("Total ciphertext envelope bytes fan-out to peers")
        .register(registry);
    slowClientCloses = Counter.builder("ghostbunker.connections.slow_client_closed")
        .description("Connections closed because outbound backpressure was exceeded")
        .register(registry);
    Gauge.builder("ghostbunker.rooms.active", roomRegistry, RoomRegistry::activeRoomCount)
        .description("Rooms with at least one connected participant")
        .register(registry);

    errorsByCode = new EnumMap<>(ErrorCode.class);
    for (ErrorCode code : ErrorCode.values()) {
      if (code == ErrorCode.UNRECOGNIZED) {
        continue;
      }
      errorsByCode.put(code, Counter.builder("ghostbunker.errors.emitted")
          .tag("code", code.name())
          .description("Protocol ERROR envelopes emitted")
          .register(registry));
    }

    goodbyeByReason = new EnumMap<>(DisconnectReason.class);
    for (DisconnectReason reason : DisconnectReason.values()) {
      if (reason == DisconnectReason.UNRECOGNIZED) {
        continue;
      }
      goodbyeByReason.put(reason, Counter.builder("ghostbunker.goodbye.emitted")
          .tag("reason", reason.name())
          .description("GOODBYE envelopes emitted before close")
          .register(registry));
    }
  }

  public void onConnectionOpened() {
    connectionsOpened.increment();
    activeConnections.incrementAndGet();
  }

  public void onConnectionClosed() {
    connectionsClosed.increment();
    activeConnections.updateAndGet(v -> Math.max(0, v - 1));
  }

  public void onBytesRouted(long bytes) {
    if (bytes > 0) {
      bytesRouted.increment(bytes);
    }
  }

  public void onErrorEmitted(ErrorCode code) {
    Counter counter = errorsByCode.get(code);
    if (counter != null) {
      counter.increment();
    }
  }

  public void onGoodbyeEmitted(DisconnectReason reason) {
    Counter counter = goodbyeByReason.get(reason);
    if (counter != null) {
      counter.increment();
    }
  }

  public void onSlowClientClosed() {
    slowClientCloses.increment();
  }

  /** Exposed for tests verifying gauge wiring. */
  public long activeConnectionCount() {
    return activeConnections.get();
  }

  public long activeRoomCount() {
    return roomRegistry.activeRoomCount();
  }
}
