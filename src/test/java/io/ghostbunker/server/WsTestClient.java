package io.ghostbunker.server;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class WsTestClient {
  private final WebSocketClient client = new StandardWebSocketClient();
  private final List<byte[]> binaryMessages = new CopyOnWriteArrayList<>();
  private final CompletableFuture<WebSocketSession> sessionFuture = new CompletableFuture<>();
  private final CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();
  private final boolean autoPong;
  private final long inboundDelayMs;

  WsTestClient() {
    this(false, 0);
  }

  WsTestClient(boolean autoPong, long inboundDelayMs) {
    this.autoPong = autoPong;
    this.inboundDelayMs = inboundDelayMs;
  }

  public WebSocketSession connect(String url) throws Exception {
    client.execute(new AbstractWebSocketHandler() {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) {
        sessionFuture.complete(session);
      }

      @Override
      protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer buf = message.getPayload().slice();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        binaryMessages.add(out);

        if (inboundDelayMs > 0) {
          try {
            Thread.sleep(inboundDelayMs);
          } catch (InterruptedException ignored) {}
        }

        if (autoPong) {
          tryAutoPong(session, out);
        }
      }

      @Override
      protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Protocol should not use text frames.
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeFuture.complete(status);
      }
    }, new WebSocketHttpHeaders(), URI.create(url));

    return sessionFuture.get(3, TimeUnit.SECONDS);
  }

  public void sendBinary(WebSocketSession session, byte[] bytes) throws Exception {
    session.sendMessage(new BinaryMessage(bytes));
  }

  public List<byte[]> drainBinaryMessages() {
    return new ArrayList<>(binaryMessages);
  }

  public CloseStatus awaitClose(long timeoutMs) throws Exception {
    return closeFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  private void tryAutoPong(WebSocketSession session, byte[] bytes) {
    try {
      io.ghostbunker.protocol.v1.GhostEnvelope env = io.ghostbunker.protocol.v1.GhostEnvelope.parseFrom(bytes);
      if (env.getType() == io.ghostbunker.protocol.v1.MessageType.PING && env.hasPing()) {
        io.ghostbunker.protocol.v1.GhostEnvelope pong = io.ghostbunker.protocol.v1.GhostEnvelope.newBuilder()
            .setProtocol("ghost-bunker")
            .setVersion("0.1")
            .setMessageId(java.util.UUID.randomUUID().toString())
            .setTimestampMs(System.currentTimeMillis())
            .setType(io.ghostbunker.protocol.v1.MessageType.PONG)
            .setPong(io.ghostbunker.protocol.v1.Pong.newBuilder().setNonce(env.getPing().getNonce()).build())
            .build();
        session.sendMessage(new BinaryMessage(pong.toByteArray()));
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }
}

