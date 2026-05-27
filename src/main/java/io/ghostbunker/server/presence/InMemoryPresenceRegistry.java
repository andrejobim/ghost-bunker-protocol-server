package io.ghostbunker.server.presence;

import io.ghostbunker.server.room.RoomRegistry;
import org.springframework.stereotype.Component;

@Component
public class InMemoryPresenceRegistry implements PresenceRegistry {
  private final RoomRegistry roomRegistry;

  public InMemoryPresenceRegistry(RoomRegistry roomRegistry) {
    this.roomRegistry = roomRegistry;
  }

  @Override
  public int onlineCount(String roomId) {
    return roomRegistry.get(roomId).map(r -> r.onlineCount()).orElse(0);
  }
}
