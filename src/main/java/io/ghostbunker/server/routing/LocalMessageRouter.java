package io.ghostbunker.server.routing;

import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.server.room.RoomRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

@Component
public class LocalMessageRouter implements MessageRouter {
  private final RoomRegistry roomRegistry;

  public LocalMessageRouter(RoomRegistry roomRegistry) {
    this.roomRegistry = roomRegistry;
  }

  @Override
  public List<WebSocketSession> recipientsExcludingSender(String roomId, WebSocketSession sender) {
    return roomRegistry.get(roomId)
        .map(room -> {
          List<WebSocketSession> out = new ArrayList<>();
          for (WebSocketSession ws : room.participants()) {
            if (!ws.getId().equals(sender.getId())) {
              out.add(ws);
            }
          }
          return out;
        })
        .orElseGet(List::of);
  }

  @Override
  public String routeScope(GhostEnvelope env) {
    return env.getRoomId();
  }
}
