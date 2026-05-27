package io.ghostbunker.server.room;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRoomRegistry implements RoomRegistry {
  private final Map<String, Room> rooms = new ConcurrentHashMap<>();

  @Override
  public Room getOrCreate(String roomId) {
    return rooms.computeIfAbsent(roomId, Room::new);
  }

  @Override
  public Optional<Room> get(String roomId) {
    return Optional.ofNullable(rooms.get(roomId));
  }

  @Override
  public void join(String roomId, WebSocketSession session) {
    getOrCreate(roomId).add(session);
  }

  @Override
  public void leave(String roomId, WebSocketSession session) {
    Room room = rooms.get(roomId);
    if (room == null) return;
    room.removeById(session.getId());
    if (room.isEmpty()) {
      rooms.remove(roomId, room);
    }
  }

  @Override
  public void leaveAll(WebSocketSession session) {
    for (Room room : rooms.values()) {
      room.removeById(session.getId());
    }
    rooms.entrySet().removeIf(e -> e.getValue().isEmpty());
  }

  @Override
  public int activeRoomCount() {
    return rooms.size();
  }
}

