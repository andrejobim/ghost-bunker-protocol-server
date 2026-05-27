package io.ghostbunker.server;

import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;

import java.time.Instant;
import java.util.UUID;

final class TestEnvelopes {
  private TestEnvelopes() {}

  static GhostEnvelope hello(String nickname) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.HELLO)
        .setHello(Hello.newBuilder().setNickname(nickname).setClientName("it").build())
        .build();
  }

  static GhostEnvelope join(String roomId) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.JOIN_ROOM)
        .setRoomId(roomId)
        .setJoinRoom(JoinRoom.newBuilder().setRoomId(roomId).build())
        .build();
  }

  static GhostEnvelope sendEncrypted(String roomId, byte[] ciphertext) {
    return GhostEnvelope.newBuilder()
        .setProtocol("ghost-bunker")
        .setVersion("0.1")
        .setMessageId(UUID.randomUUID().toString())
        .setRequestId("req-" + UUID.randomUUID())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(MessageType.SEND_ENCRYPTED_MESSAGE)
        .setRoomId(roomId)
        .setSendEncryptedMessage(SendEncryptedMessage.newBuilder()
            .setClientMessageId("c-" + UUID.randomUUID())
            .setKeyId("k1")
            .setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
            .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[] {9,9,9,9,9,9,9,9,9,9,9,9}))
            .setCiphertext(com.google.protobuf.ByteString.copyFrom(ciphertext))
            .build())
        .build();
  }
}

