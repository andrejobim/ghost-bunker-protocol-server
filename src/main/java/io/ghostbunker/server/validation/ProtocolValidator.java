package io.ghostbunker.server.validation;

import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;
import io.ghostbunker.server.protocol.ProtocolLimits;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ProtocolValidator {
  private static final Pattern ASCII_VISIBLE = Pattern.compile("^[\\x21-\\x7E]+$");
  private final ProtocolLimits limits;

  public ProtocolValidator(ProtocolLimits limits) {
    this.limits = limits;
  }

  public void validateBaseEnvelope(GhostEnvelope env) {
    if (!limits.expectedProtocol().equals(env.getProtocol())) {
      throw new ValidationException("bad protocol");
    }
    if (!limits.expectedVersion().equals(env.getVersion())) {
      throw new ValidationException("unsupported version");
    }
    if (env.getType() == MessageType.MESSAGE_TYPE_UNSPECIFIED) {
      throw new ValidationException("missing type");
    }
  }

  public void validateNickname(String nickname) {
    if (nickname == null) return;
    if (nickname.isBlank()) return;
    if (nickname.length() > limits.maxNicknameChars()) {
      throw new ValidationException("nickname too long");
    }
    if (!ASCII_VISIBLE.matcher(nickname).matches()) {
      throw new ValidationException("nickname must be ASCII-visible");
    }
  }

  public void validateRoomId(String roomId) {
    if (roomId == null || roomId.isBlank()) {
      throw new ValidationException("room_id required");
    }
    if (roomId.length() > limits.maxRoomIdChars()) {
      throw new ValidationException("room_id too long");
    }
    if (!ASCII_VISIBLE.matcher(roomId).matches()) {
      throw new ValidationException("room_id must be ASCII-visible");
    }
  }

  public void validateSendEncryptedMessage(SendEncryptedMessage msg) {
    if (msg.getCiphertext() == null || msg.getCiphertext().isEmpty()) {
      throw new ValidationException("ciphertext required");
    }
    if (msg.getCiphertext().size() > limits.maxCiphertextBytes()) {
      throw new ValidationException("ciphertext too large");
    }
    if (msg.getNonce() == null || msg.getNonce().isEmpty()) {
      throw new ValidationException("nonce required");
    }
    if (msg.getKeyId() == null || msg.getKeyId().isBlank()) {
      throw new ValidationException("key_id required");
    }
    if (msg.getCipherSuite() == null || msg.getCipherSuite() == CipherSuite.CIPHER_SUITE_UNSPECIFIED) {
      throw new ValidationException("enc_suite required");
    }
  }

  public void validateTypeMatchesPayload(GhostEnvelope env) {
    MessageType t = env.getType();
    boolean ok = switch (t) {
      case HELLO -> env.hasHello();
      case WELCOME -> env.hasWelcome();
      case JOIN_ROOM -> env.hasJoinRoom();
      case ROOM_JOINED -> env.hasRoomJoined();
      case LEAVE_ROOM -> env.hasLeaveRoom();
      case ROOM_LEFT -> env.hasRoomLeft();
      case SEND_ENCRYPTED_MESSAGE -> env.hasSendEncryptedMessage();
      case MESSAGE_ACCEPTED -> env.hasMessageAccepted();
      case ENCRYPTED_MESSAGE -> env.hasEncryptedMessage();
      case MESSAGE_RECEIVED_ACK -> env.hasMessageReceivedAck();
      case PING -> env.hasPing();
      case PONG -> env.hasPong();
      case ERROR -> env.hasError();
      case DISCONNECT -> env.hasDisconnect();
      case GOODBYE -> env.hasGoodbye();
      default -> false;
    };
    if (!ok) {
      throw new ValidationException("type/payload mismatch");
    }
  }

  public static final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
      super(message);
    }
  }
}

