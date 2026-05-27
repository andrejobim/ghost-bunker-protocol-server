package io.ghostbunker.server.room;

import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;

/**
 * Room membership on this node. Cross-node fan-out uses {@link io.ghostbunker.server.bus.MessageBus}
 * without persisting room state or message payloads.
 */
public interface RoomRegistry {

  Room getOrCreate(String roomId);

  Optional<Room> get(String roomId);

  void join(String roomId, WebSocketSession session);

  void leave(String roomId, WebSocketSession session);

  void leaveAll(WebSocketSession session);

  int activeRoomCount();
}
