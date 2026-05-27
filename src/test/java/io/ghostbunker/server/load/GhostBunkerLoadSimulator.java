package io.ghostbunker.server.load;

import com.google.protobuf.ByteString;
import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.Pong;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic load generator using Protobuf {@link GhostEnvelope} clients (no web client).
 *
 * <p>Run against a live server (local or staging):
 *
 * <pre>
 * mvn -q -DskipTests package
 * java -cp target/ghost-bunker-protocol-server-0.3.0-SNAPSHOT.jar:target/classes \
 *   io.ghostbunker.server.load.GhostBunkerLoadSimulator ws://localhost:8080/ghost-bunker 20 30
 * </pre>
 *
 * <p>Arguments: {@code url} {@code clients} {@code seconds}
 */
public final class GhostBunkerLoadSimulator {

  private static final String PROTOCOL = "ghost-bunker";
  private static final String VERSION = "0.1";
  private static final String SUBPROTOCOL = "ghost-bunker.v0.1";

  public static void main(String[] args) throws Exception {
    String url = args.length > 0 ? args[0] : "ws://localhost:8080/ghost-bunker";
    int clients = args.length > 1 ? Integer.parseInt(args[1]) : 10;
    int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
    String roomId = args.length > 3 ? args[3] : "load-room";

    System.out.printf("Load simulator: url=%s clients=%d duration=%ds room=%s%n",
        url, clients, seconds, roomId);

    ExecutorService pool = Executors.newFixedThreadPool(Math.min(clients, 64));
    CountDownLatch ready = new CountDownLatch(clients);
    AtomicInteger welcomes = new AtomicInteger();
    AtomicInteger joins = new AtomicInteger();
    AtomicInteger messagesAccepted = new AtomicInteger();
    AtomicLong routedBytes = new AtomicLong();

    List<ClientWorker> workers = new ArrayList<>();
    for (int i = 0; i < clients; i++) {
      ClientWorker worker = new ClientWorker(url, roomId, "load-" + i, ready, welcomes, joins,
          messagesAccepted, routedBytes);
      workers.add(worker);
      pool.submit(worker);
    }

    if (!ready.await(30, TimeUnit.SECONDS)) {
      System.err.println("Timed out waiting for clients to complete HELLO/JOIN");
    }

    long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < endAt) {
      for (ClientWorker w : workers) {
        w.maybeSend();
      }
      Thread.sleep(50);
    }

    pool.shutdown();
    pool.awaitTermination(15, TimeUnit.SECONDS);
    for (ClientWorker w : workers) {
      w.close();
    }

    System.out.printf("Results: welcomes=%d joins=%d accepted=%d routed_bytes=%d%n",
        welcomes.get(), joins.get(), messagesAccepted.get(), routedBytes.get());
  }

  private static final class ClientWorker implements Runnable {
    private final String url;
    private final String roomId;
    private final String nickname;
    private final CountDownLatch ready;
    private final AtomicInteger welcomes;
    private final AtomicInteger joins;
    private final AtomicInteger messagesAccepted;
    private final AtomicLong routedBytes;
    private volatile WebSocketSession session;
    private volatile boolean established;

    ClientWorker(
        String url,
        String roomId,
        String nickname,
        CountDownLatch ready,
        AtomicInteger welcomes,
        AtomicInteger joins,
        AtomicInteger messagesAccepted,
        AtomicLong routedBytes
    ) {
      this.url = url;
      this.roomId = roomId;
      this.nickname = nickname;
      this.ready = ready;
      this.welcomes = welcomes;
      this.joins = joins;
      this.messagesAccepted = messagesAccepted;
      this.routedBytes = routedBytes;
    }

    @Override
    public void run() {
      try {
        CompletableFuture<WebSocketSession> future = new CompletableFuture<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of(SUBPROTOCOL));
        new StandardWebSocketClient().execute(new AbstractWebSocketHandler() {
          @Override
          public void afterConnectionEstablished(WebSocketSession s) {
            future.complete(s);
          }

          @Override
          protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) {
            try {
              GhostEnvelope env = GhostEnvelope.parseFrom(message.getPayload().array());
              switch (env.getType()) {
                case WELCOME -> welcomes.incrementAndGet();
                case ROOM_JOINED -> joins.incrementAndGet();
                case MESSAGE_ACCEPTED -> messagesAccepted.incrementAndGet();
                case ENCRYPTED_MESSAGE -> routedBytes.addAndGet(env.getSerializedSize());
                case PING -> autoPong(s, env);
                default -> { }
              }
            } catch (Exception ignored) {
            }
          }
        }, headers, URI.create(url));

        session = future.get(10, TimeUnit.SECONDS);
        session.sendMessage(new BinaryMessage(hello(nickname).toByteArray()));
        awaitType(session, MessageType.WELCOME, 10);
        session.sendMessage(new BinaryMessage(join(roomId).toByteArray()));
        awaitType(session, MessageType.ROOM_JOINED, 10);
        established = true;
        ready.countDown();
      } catch (Exception e) {
        ready.countDown();
      }
    }

    void maybeSend() {
      if (!established || session == null || !session.isOpen()) {
        return;
      }
      try {
        byte[] ciphertext = new byte[] {1, 2, 3, 4};
        session.sendMessage(new BinaryMessage(sendEncrypted(roomId, ciphertext).toByteArray()));
      } catch (Exception ignored) {
      }
    }

    void close() {
      try {
        if (session != null && session.isOpen()) {
          session.close();
        }
      } catch (Exception ignored) {
      }
    }
  }

  private static void autoPong(WebSocketSession session, GhostEnvelope ping) {
    try {
      GhostEnvelope pong = baseEnvelope(MessageType.PONG)
          .setPong(Pong.newBuilder().setNonce(ping.getPing().getNonce()).build())
          .build();
      session.sendMessage(new BinaryMessage(pong.toByteArray()));
    } catch (Exception ignored) {
    }
  }

  private static void awaitType(WebSocketSession session, MessageType type, int seconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    // Polling receive is handled in handler; for simulator we rely on fire-and-forget after join.
    while (System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
  }

  private static GhostEnvelope hello(String nickname) {
    return baseEnvelope(MessageType.HELLO)
        .setHello(Hello.newBuilder().setNickname(nickname).setClientName("load-simulator").build())
        .build();
  }

  private static GhostEnvelope join(String roomId) {
    return baseEnvelope(MessageType.JOIN_ROOM)
        .setRoomId(roomId)
        .setJoinRoom(JoinRoom.newBuilder().setRoomId(roomId).build())
        .build();
  }

  private static GhostEnvelope sendEncrypted(String roomId, byte[] ciphertext) {
    return baseEnvelope(MessageType.SEND_ENCRYPTED_MESSAGE)
        .setRoomId(roomId)
        .setSendEncryptedMessage(SendEncryptedMessage.newBuilder()
            .setClientMessageId(UUID.randomUUID().toString())
            .setKeyId("load")
            .setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
            .setNonce(ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
            .setCiphertext(ByteString.copyFrom(ciphertext))
            .build())
        .build();
  }

  private static GhostEnvelope.Builder baseEnvelope(MessageType type) {
    return GhostEnvelope.newBuilder()
        .setProtocol(PROTOCOL)
        .setVersion(VERSION)
        .setMessageId(UUID.randomUUID().toString())
        .setTimestampMs(Instant.now().toEpochMilli())
        .setType(type);
  }
}
