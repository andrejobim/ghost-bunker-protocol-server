package io.ghostbunker.server.presence;

/**
 * Ephemeral room occupancy counts for protocol responses (e.g. {@code ROOM_JOINED.online_count}).
 * Must not persist identity, IP, or message content.
 */
public interface PresenceRegistry {

  int onlineCount(String roomId);
}
