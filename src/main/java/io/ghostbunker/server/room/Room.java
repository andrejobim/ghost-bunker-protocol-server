package io.ghostbunker.server.room;

import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Room {
  private final String roomId;
  private final Map<String, WebSocketSession> participantsById = new ConcurrentHashMap<>();

  public Room(String roomId) {
    this.roomId = roomId;
  }

  public String roomId() {
    return roomId;
  }

  public Collection<WebSocketSession> participants() {
    return participantsById.values();
  }

  public int onlineCount() {
    return participantsById.size();
  }

  public void add(WebSocketSession session) {
    participantsById.put(session.getId(), session);
  }

  public void removeById(String sessionId) {
    participantsById.remove(sessionId);
  }

  public boolean isEmpty() {
    return participantsById.isEmpty();
  }
}

