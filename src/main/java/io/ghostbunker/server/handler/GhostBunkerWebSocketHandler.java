package io.ghostbunker.server.handler;

import com.google.protobuf.InvalidProtocolBufferException;
import io.ghostbunker.protocol.v1.DisconnectReason;
import io.ghostbunker.protocol.v1.EncryptedMessage;
import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageAccepted;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.Pong;
import io.ghostbunker.protocol.v1.RoomJoined;
import io.ghostbunker.server.backpressure.OutboundQueuePolicy;
import io.ghostbunker.server.error.ProtocolErrorMapper;
import io.ghostbunker.server.heartbeat.HeartbeatService;
import io.ghostbunker.server.logging.SanitizedProtocolLogger;
import io.ghostbunker.server.protocol.GhostEnvelopeDecoder;
import io.ghostbunker.server.protocol.GhostEnvelopeEncoder;
import io.ghostbunker.server.protocol.ProtocolLimits;
import io.ghostbunker.server.rate.PerConnectionRateLimiter;
import io.ghostbunker.server.room.InMemoryRoomRegistry;
import io.ghostbunker.server.routing.MessageRouter;
import io.ghostbunker.server.session.GhostSession;
import io.ghostbunker.server.session.GhostSessionState;
import io.ghostbunker.server.session.InMemoryGhostSessionRegistry;
import io.ghostbunker.server.validation.ProtocolValidator;
import io.ghostbunker.server.validation.ProtocolValidator.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.UUID;

@Component
public class GhostBunkerWebSocketHandler extends BinaryWebSocketHandler {
  private final InMemoryGhostSessionRegistry sessionRegistry;
  private final InMemoryRoomRegistry roomRegistry;
  private final MessageRouter router;
  private final ProtocolValidator validator;
  private final PerConnectionRateLimiter rateLimiter;
  private final ProtocolErrorMapper errorMapper;
  private final HeartbeatService heartbeatService;
  private final SanitizedProtocolLogger logger;
  private final ProtocolLimits limits;

  private final GhostEnvelopeDecoder decoder;
  private final GhostEnvelopeEncoder encoder;
  private final OutboundQueuePolicy outboundPolicy;
  private final Clock clock = Clock.systemUTC();

  public GhostBunkerWebSocketHandler(
      InMemoryGhostSessionRegistry sessionRegistry,
      InMemoryRoomRegistry roomRegistry,
      MessageRouter router,
      ProtocolValidator validator,
      PerConnectionRateLimiter rateLimiter,
      ProtocolErrorMapper errorMapper,
      HeartbeatService heartbeatService,
      SanitizedProtocolLogger logger,
      ProtocolLimits limits
  ) {
    this.sessionRegistry = sessionRegistry;
    this.roomRegistry = roomRegistry;
    this.router = router;
    this.validator = validator;
    this.rateLimiter = rateLimiter;
    this.errorMapper = errorMapper;
    this.heartbeatService = heartbeatService;
    this.logger = logger;
    this.limits = limits;
    this.decoder = new GhostEnvelopeDecoder(limits);
    this.encoder = new GhostEnvelopeEncoder(limits);
    this.outboundPolicy = new OutboundQueuePolicy(limits);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
        session,
        limits.sendTimeLimitMs(),
        limits.maxOutboundPendingBytes()
    );
    GhostSession gs = sessionRegistry.create(decorated, session);
    gs.setState(GhostSessionState.AWAITING_HELLO);
    heartbeatService.start(gs);
    logger.info("ws connected (sanitized)");
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) throws Exception {
    GhostSession session = sessionRegistry.get(wsSession).orElse(null);
    if (session == null) {
      wsSession.close(CloseStatus.SERVER_ERROR);
      return;
    }

    session.touchActivity();

    if (!rateLimiter.allowCommand(session)) {
      sendError(session, ErrorCode.RATE_LIMITED_CONNECTION, "rate limited", null, 1_000);
      return;
    }

    byte[] bytes = toByteArray(message.getPayload());
    GhostEnvelope env;
    try {
      env = decoder.decode(bytes);
    } catch (InvalidProtocolBufferException e) {
      // Bad protobuf is a hard failure: emit a sanitized ERROR and close.
      sendError(session, ErrorCode.BAD_ENVELOPE, "invalid protobuf", null, null);
      heartbeatService.closeWithGoodbye(session, DisconnectReason.PROTOCOL_ERROR, "invalid protobuf");
      return;
    }

    try {
      validator.validateBaseEnvelope(env);
      validator.validateTypeMatchesPayload(env);
    } catch (ValidationException e) {
      ErrorCode code = "unsupported version".equalsIgnoreCase(e.getMessage()) ? ErrorCode.UNSUPPORTED_VERSION : ErrorCode.BAD_METADATA;
      onProtocolViolation(session, code, e.getMessage());
      return;
    }

    dispatch(session, env);
  }

  private byte[] toByteArray(ByteBuffer buf) {
    ByteBuffer dup = buf.slice();
    byte[] out = new byte[dup.remaining()];
    dup.get(out);
    return out;
  }

  private void dispatch(GhostSession session, GhostEnvelope env) throws IOException {
    switch (env.getType()) {
      case HELLO -> onHello(session, env);
      case JOIN_ROOM -> onJoinRoom(session, env);
      case SEND_ENCRYPTED_MESSAGE -> onSendEncryptedMessage(session, env);
      case MESSAGE_RECEIVED_ACK -> onAck(session, env);
      case PONG -> onPong(session, env);
      case DISCONNECT -> onDisconnect(session);
      case PING -> { /* ignore client ping; server drives heartbeat */ }
      default -> onProtocolViolation(session, ErrorCode.PROTOCOL_VIOLATION, "unexpected type for state");
    }
  }

  private void onHello(GhostSession session, GhostEnvelope env) throws IOException {
    if (session.state() != GhostSessionState.AWAITING_HELLO) {
      onProtocolViolation(session, ErrorCode.PROTOCOL_VIOLATION, "hello not allowed");
      return;
    }

    Hello hello = env.getHello();
    try {
      validator.validateNickname(hello.getNickname());
    } catch (ValidationException e) {
      onProtocolViolation(session, ErrorCode.BAD_METADATA, e.getMessage());
      return;
    }

    String nickname = hello.getNickname();
    String displayName = (nickname == null || nickname.isBlank()) ? "anon-" + session.userId().substring(0, 6) : nickname;
    session.setDisplayName(displayName);
    session.setState(GhostSessionState.ESTABLISHED);

    GhostEnvelope welcome = GhostEnvelope.newBuilder()
        .setProtocol(limits.expectedProtocol())
        .setVersion(limits.expectedVersion())
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(clock.millis())
        .setType(MessageType.WELCOME)
        .setWelcome(io.ghostbunker.protocol.v1.Welcome.newBuilder()
            .setSessionId(session.sessionId())
            .setUserId(session.userId())
            .setDisplayName(displayName)
            .setServerTimeMs(clock.millis())
            .setLimits(io.ghostbunker.protocol.v1.ProtocolLimits.newBuilder()
                .setMaxEnvelopeBytes(limits.maxEnvelopeBytes())
                .setMaxCiphertextBytes(limits.maxCiphertextBytes())
                .setMaxPlaintextBytesBeforeEncryption(0)
                .setMaxNicknameChars(limits.maxNicknameChars())
                .setMaxRoomIdChars(limits.maxRoomIdChars())
                .setMaxRoomsPerConnection(limits.maxRoomsPerConnection())
                .setHandshakeTimeoutMs(limits.handshakeTimeoutMs())
                .setPingIntervalMs(limits.pingIntervalMs())
                .setPongTimeoutMs(limits.pongTimeoutMs())
                .setIdleTimeoutMs(limits.idleTimeoutMs())
                .build())
            .setPingIntervalMs(limits.pingIntervalMs())
            .build())
        .build();

    send(session, welcome);
  }

  private void onJoinRoom(GhostSession session, GhostEnvelope env) throws IOException {
    if (session.state() != GhostSessionState.ESTABLISHED && session.state() != GhostSessionState.IN_ROOMS) {
      onProtocolViolation(session, ErrorCode.PROTOCOL_VIOLATION, "join not allowed");
      return;
    }

    JoinRoom join = env.getJoinRoom();
    String roomId = env.getRoomId();
    if (roomId == null || roomId.isBlank()) {
      roomId = join.getRoomId();
    }

    try {
      validator.validateRoomId(roomId);
    } catch (ValidationException e) {
      onProtocolViolation(session, ErrorCode.BAD_METADATA, e.getMessage());
      return;
    }

    if (session.rooms().size() >= limits.maxRoomsPerConnection() && !session.rooms().contains(roomId)) {
      sendError(session, ErrorCode.TOO_MANY_ROOMS, "too many rooms", env.getRequestId(), null);
      return;
    }

    roomRegistry.join(roomId, session.wsSession());
    session.rooms().add(roomId);
    session.setState(GhostSessionState.IN_ROOMS);

    int onlineCount = roomRegistry.get(roomId).map(r -> r.onlineCount()).orElse(1);
    GhostEnvelope roomJoined = GhostEnvelope.newBuilder()
        .setProtocol(limits.expectedProtocol())
        .setVersion(limits.expectedVersion())
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(clock.millis())
        .setType(MessageType.ROOM_JOINED)
        .setRoomId(roomId)
        .setRoomJoined(RoomJoined.newBuilder().setRoomId(roomId).setOnlineCount(onlineCount).build())
        .build();

    send(session, roomJoined);
  }

  private void onSendEncryptedMessage(GhostSession session, GhostEnvelope env) throws IOException {
    if (session.state() != GhostSessionState.IN_ROOMS) {
      onProtocolViolation(session, ErrorCode.PROTOCOL_VIOLATION, "send not allowed");
      return;
    }

    String roomId = env.getRoomId();
    try {
      validator.validateRoomId(roomId);
    } catch (ValidationException e) {
      onProtocolViolation(session, ErrorCode.BAD_METADATA, e.getMessage());
      return;
    }

    if (!session.rooms().contains(roomId)) {
      sendError(session, ErrorCode.NOT_IN_ROOM, "not in room", env.getRequestId(), null);
      return;
    }

    if (!rateLimiter.allowMessage(session)) {
      sendError(session, ErrorCode.RATE_LIMITED_CONNECTION, "rate limited", env.getRequestId(), 1_000);
      return;
    }

    try {
      validator.validateSendEncryptedMessage(env.getSendEncryptedMessage());
    } catch (ValidationException e) {
      ErrorCode code = "ciphertext too large".equalsIgnoreCase(e.getMessage()) ? ErrorCode.CIPHERTEXT_TOO_LARGE : ErrorCode.BAD_METADATA;
      onProtocolViolation(session, code, e.getMessage());
      return;
    }

    String serverMessageId = UUID.randomUUID().toString();

    GhostEnvelope accepted = GhostEnvelope.newBuilder()
        .setProtocol(limits.expectedProtocol())
        .setVersion(limits.expectedVersion())
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(clock.millis())
        .setType(MessageType.MESSAGE_ACCEPTED)
        .setRequestId(env.getRequestId())
        .setRoomId(roomId)
        .setMessageAccepted(MessageAccepted.newBuilder()
            .setRequestId(env.getRequestId())
            .setClientMessageId(env.getSendEncryptedMessage().getClientMessageId())
            .setServerMessageId(serverMessageId)
            .setRoomId(roomId)
            .setAcceptedAtMs(clock.millis())
            .build())
        .build();
    send(session, accepted);

    EncryptedMessage routed = EncryptedMessage.newBuilder()
        .setServerMessageId(serverMessageId)
        .setFromUserId(session.userId())
        .setKeyId(env.getSendEncryptedMessage().getKeyId())
        .setCipherSuite(env.getSendEncryptedMessage().getCipherSuite())
        .setNonce(env.getSendEncryptedMessage().getNonce())
        .setCiphertext(env.getSendEncryptedMessage().getCiphertext())
        .setSentAtMs(clock.millis())
        .setAadVersion(env.getSendEncryptedMessage().getAadVersion())
        .build();

    GhostEnvelope routedEnv = GhostEnvelope.newBuilder()
        .setProtocol(limits.expectedProtocol())
        .setVersion(limits.expectedVersion())
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(clock.millis())
        .setType(MessageType.ENCRYPTED_MESSAGE)
        .setRoomId(roomId)
        .setEncryptedMessage(routed)
        .build();

    for (WebSocketSession recipient : router.recipientsExcludingSender(roomId, session.wsSession())) {
      if (!recipient.isOpen()) continue;
      GhostSession recipientGs = sessionRegistry.get(recipient).orElse(null);
      if (recipientGs == null) continue;
      if (!send(recipientGs, routedEnv)) {
        onClientTooSlow(recipientGs);
      }
    }
  }

  private void onAck(GhostSession session, GhostEnvelope env) {
    if (session.state() != GhostSessionState.IN_ROOMS) return;
    // Accept and ignore (no persistence).
  }

  private void onPong(GhostSession session, GhostEnvelope env) {
    Pong pong = env.getPong();
    if (pong != null) {
      session.setLastPongAtMs(clock.millis());
    }
  }

  private void onDisconnect(GhostSession session) {
    heartbeatService.closeWithGoodbye(session, DisconnectReason.CLIENT_REQUEST, "client requested");
  }

  private void onClientTooSlow(GhostSession session) {
    if (session.markSlowClient()) {
      heartbeatService.closeWithGoodbye(session, DisconnectReason.POLICY_ERROR, "client too slow");
    }
  }

  private void onProtocolViolation(GhostSession session, ErrorCode code, String sanitized) throws IOException {
    sendError(session, code, sanitized, null, null);
    int violations = session.recordProtocolViolationAndGetCountInWindow();
    if (violations >= limits.maxViolationsInWindow()) {
      heartbeatService.closeWithGoodbye(session, DisconnectReason.TOO_MANY_VIOLATIONS, "too many violations");
    }
  }

  private void sendError(GhostSession session, ErrorCode code, String msg, String requestId, Integer retryAfterMs) throws IOException {
    GhostEnvelope err = errorMapper.error(code, msg, requestId, retryAfterMs);
    send(session, err);
  }

  private boolean send(GhostSession session, GhostEnvelope env) throws IOException {
    byte[] bytes = encoder.encode(env);
    if (!outboundPolicy.canEnqueue(session, bytes.length)) {
      return false;
    }
    session.onEnqueueOutbound(bytes.length);
    try {
      session.wsSession().sendMessage(new BinaryMessage(bytes));
      return true;
    } catch (RuntimeException e) {
      return false;
    } finally {
      session.onDequeueOutbound(bytes.length);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    sessionRegistry.get(session).ifPresent(gs -> gs.setState(GhostSessionState.CLOSED));
    roomRegistry.leaveAll(session);
    sessionRegistry.remove(session);
    logger.info("ws closed (sanitized)");
  }
}

