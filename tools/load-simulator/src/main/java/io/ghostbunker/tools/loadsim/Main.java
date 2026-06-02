package io.ghostbunker.tools.loadsim;

import com.google.protobuf.ByteString;
import io.ghostbunker.protocol.v1.CipherSuite;
import io.ghostbunker.protocol.v1.ErrorCode;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.Goodbye;
import io.ghostbunker.protocol.v1.Hello;
import io.ghostbunker.protocol.v1.JoinRoom;
import io.ghostbunker.protocol.v1.MessageType;
import io.ghostbunker.protocol.v1.Ping;
import io.ghostbunker.protocol.v1.Pong;
import io.ghostbunker.protocol.v1.SendEncryptedMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
  private static final String SUBPROTOCOL = "ghost-bunker.v0.1";
  private static final String PROTOCOL = "ghost-bunker";
  private static final String VERSION = "0.1";

  public static void main(String[] args) throws Exception {
    Config cfg = Config.parse(args);
    System.out.println("Ghost Bunker load simulator (non-production)");
    System.out.println("Target: " + cfg.serverUrl);
    System.out.println("Clients: " + cfg.clients + " | Rooms: " + cfg.rooms + " | Messages/client: " + cfg.messagesPerClient);

    Stats stats = new Stats();

    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    List<ClientWorker> workers = new ArrayList<>(cfg.clients);
    for (int i = 0; i < cfg.clients; i++) {
      workers.add(new ClientWorker(http, cfg, stats, i));
    }

    long startedAt = System.nanoTime();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = workers.stream()
          .map(w -> CompletableFuture.runAsync(() -> {
            try {
              w.run();
            } catch (Exception e) {
              stats.clientFailures.incrementAndGet();
              stats.connectFailuresByCause.computeIfAbsent(rootCauseKey(e), k -> new AtomicLong()).incrementAndGet();
            }
          }, executor))
          .toList();
      for (CompletableFuture<Void> f : futures) f.join();
    }

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
    System.out.println();
    System.out.println(stats.render(elapsedMs));
  }

  static final class Config {
    final URI serverUrl;
    final int clients;
    final int rooms;
    final int messagesPerClient;
    final int ciphertextBytes;
    final int connectTimeoutMs;
    final int runTimeoutMs;

    private Config(
        URI serverUrl,
        int clients,
        int rooms,
        int messagesPerClient,
        int ciphertextBytes,
        int connectTimeoutMs,
        int runTimeoutMs
    ) {
      this.serverUrl = serverUrl;
      this.clients = clients;
      this.rooms = rooms;
      this.messagesPerClient = messagesPerClient;
      this.ciphertextBytes = ciphertextBytes;
      this.connectTimeoutMs = connectTimeoutMs;
      this.runTimeoutMs = runTimeoutMs;
    }

    static Config parse(String[] args) {
      String url = "ws://localhost:8080/ghost-bunker";
      int clients = 20;
      int rooms = 5;
      int messagesPerClient = 10;
      int ciphertextBytes = 128;
      int connectTimeoutMs = 5_000;
      int runTimeoutMs = 60_000;

      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        String next = (i + 1) < args.length ? args[i + 1] : null;
        switch (a) {
          case "--url" -> {
            url = requireValue(a, next);
            i++;
          }
          case "--clients" -> {
            clients = Integer.parseInt(requireValue(a, next));
            i++;
          }
          case "--rooms" -> {
            rooms = Integer.parseInt(requireValue(a, next));
            i++;
          }
          case "--messages-per-client" -> {
            messagesPerClient = Integer.parseInt(requireValue(a, next));
            i++;
          }
          case "--ciphertext-bytes" -> {
            ciphertextBytes = Integer.parseInt(requireValue(a, next));
            i++;
          }
          case "--connect-timeout-ms" -> {
            connectTimeoutMs = Integer.parseInt(requireValue(a, next));
            i++;
          }
          case "--run-timeout-ms" -> {
            runTimeoutMs = Integer.parseInt(requireValue(a, next));
            i++;
          }
          default -> {
            // ignore unknown flags to keep this tool simple for ad-hoc runs
          }
        }
      }

      return new Config(
          URI.create(url),
          Math.max(1, clients),
          Math.max(1, rooms),
          Math.max(0, messagesPerClient),
          Math.max(16, ciphertextBytes),
          Math.max(500, connectTimeoutMs),
          Math.max(5_000, runTimeoutMs)
      );
    }

    private static String requireValue(String flag, String value) {
      if (value == null || value.startsWith("--")) {
        throw new IllegalArgumentException("Missing value for " + flag);
      }
      return value;
    }
  }

  static final class ClientWorker implements WebSocket.Listener {
    private final HttpClient http;
    private final Config cfg;
    private final Stats stats;
    private final int clientIndex;
    private final SecureRandom rng = new SecureRandom();

    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch welcomed = new CountDownLatch(1);
    private volatile WebSocket ws;
    private final ScheduledExecutorService watchdog;

    ClientWorker(HttpClient http, Config cfg, Stats stats, int clientIndex) {
      this.http = http;
      this.cfg = cfg;
      this.stats = stats;
      this.clientIndex = clientIndex;
      this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "loadsim-watchdog-" + clientIndex);
        t.setDaemon(true);
        return t;
      });
    }

    void run() throws Exception {
      stats.clientsStarted.incrementAndGet();

      WebSocket socket;
      try {
        socket = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
            .subprotocols(SUBPROTOCOL)
            .buildAsync(cfg.serverUrl, this)
            .join();
      } catch (CompletionException ce) {
        stats.connectFailuresByCause.computeIfAbsent(rootCauseKey(ce), k -> new AtomicLong()).incrementAndGet();
        throw ce;
      }
      this.ws = socket;

      watchdog.schedule(() -> {
        try {
          socket.abort();
        } catch (Exception ignored) {
        }
      }, cfg.runTimeoutMs, TimeUnit.MILLISECONDS);

      sendHello();

      // Best-effort: wait for handshake before sending room traffic.
      welcomed.await(2, TimeUnit.SECONDS);

      String roomId = "room-" + (clientIndex % cfg.rooms);
      sendJoin(roomId);

      for (int i = 0; i < cfg.messagesPerClient; i++) {
        sendEncrypted(roomId, i);
      }

      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
      } catch (Exception ignored) {
      }

      done.await(2, TimeUnit.SECONDS);
      watchdog.shutdownNow();
    }

    private void sendHello() {
      Hello hello = Hello.newBuilder()
          .setClientName("load-simulator")
          .setNickname("anon")
          .build();
      GhostEnvelope env = baseEnvelope(MessageType.HELLO)
          .setHello(hello)
          .build();
      send(env);
      stats.helloSent.incrementAndGet();
    }

    private void sendJoin(String roomId) {
      JoinRoom join = JoinRoom.newBuilder().setRoomId(roomId).build();
      GhostEnvelope env = baseEnvelope(MessageType.JOIN_ROOM)
          .setRoomId(roomId)
          .setJoinRoom(join)
          .build();
      send(env);
      stats.joinSent.incrementAndGet();
    }

    private void sendEncrypted(String roomId, int seq) {
      byte[] nonce = new byte[12];
      rng.nextBytes(nonce);
      byte[] ciphertext = new byte[cfg.ciphertextBytes];
      rng.nextBytes(ciphertext);

      SendEncryptedMessage sem = SendEncryptedMessage.newBuilder()
          .setClientMessageId("m-" + clientIndex + "-" + seq)
          .setKeyId("synthetic")
          .setCipherSuite(CipherSuite.PBKDF2_HMAC_SHA256_AES_256_GCM)
          .setNonce(ByteString.copyFrom(nonce))
          .setCiphertext(ByteString.copyFrom(ciphertext))
          .setAadVersion(1)
          .build();

      GhostEnvelope env = baseEnvelope(MessageType.SEND_ENCRYPTED_MESSAGE)
          .setRoomId(roomId)
          .setSendEncryptedMessage(sem)
          .build();
      send(env);
      stats.sendEncryptedSent.incrementAndGet();
    }

    private GhostEnvelope.Builder baseEnvelope(MessageType type) {
      return GhostEnvelope.newBuilder()
          .setProtocol(PROTOCOL)
          .setVersion(VERSION)
          .setMessageId(UUID.randomUUID().toString())
          .setTimestampMs(System.currentTimeMillis())
          .setType(type);
    }

    private void send(GhostEnvelope env) {
      byte[] bytes = env.toByteArray();
      // Never log bytes; they contain ciphertext and/or identifiers.
      ws.sendBinary(ByteBuffer.wrap(bytes), true);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      stats.connected.incrementAndGet();
      webSocket.request(1);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      if (data != null && data.remaining() > 0) {
        handleFrame(data.slice());
      }
      webSocket.request(1);
      return null;
    }

    private void handleFrame(ByteBuffer buf) {
      byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      GhostEnvelope env;
      try {
        env = GhostEnvelope.parseFrom(bytes);
      } catch (Exception e) {
        stats.decodeFailures.incrementAndGet();
        return;
      }

      switch (env.getType()) {
        case WELCOME -> {
          stats.welcomeReceived.incrementAndGet();
          welcomed.countDown();
        }
        case ROOM_JOINED -> stats.roomJoinedReceived.incrementAndGet();
        case MESSAGE_ACCEPTED -> stats.messageAcceptedReceived.incrementAndGet();
        case ENCRYPTED_MESSAGE -> stats.encryptedMessageReceived.incrementAndGet();
        case ERROR -> {
          ErrorCode code = env.getError().getCode();
          stats.errorsByCode.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
        }
        case GOODBYE -> {
          Goodbye gb = env.getGoodbye();
          stats.goodbyeReasons.computeIfAbsent(gb.getReason(), k -> new AtomicLong()).incrementAndGet();
        }
        case PING -> {
          Ping ping = env.getPing();
          if (ping != null) {
            sendPong(ping.getNonce());
          }
          stats.pingsReceived.incrementAndGet();
        }
        default -> {
          // ignore
        }
      }
    }

    private void sendPong(String nonce) {
      Pong pong = Pong.newBuilder().setNonce(Objects.requireNonNullElse(nonce, "")).build();
      GhostEnvelope env = baseEnvelope(MessageType.PONG)
          .setPong(pong)
          .build();
      send(env);
      stats.pongsSent.incrementAndGet();
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      stats.closed.incrementAndGet();
      done.countDown();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      stats.clientFailures.incrementAndGet();
      done.countDown();
    }
  }

  static final class Stats {
    final AtomicLong clientsStarted = new AtomicLong();
    final AtomicLong connected = new AtomicLong();
    final AtomicLong closed = new AtomicLong();
    final AtomicLong clientFailures = new AtomicLong();
    final AtomicLong decodeFailures = new AtomicLong();

    final AtomicLong helloSent = new AtomicLong();
    final AtomicLong joinSent = new AtomicLong();
    final AtomicLong sendEncryptedSent = new AtomicLong();

    final AtomicLong welcomeReceived = new AtomicLong();
    final AtomicLong roomJoinedReceived = new AtomicLong();
    final AtomicLong messageAcceptedReceived = new AtomicLong();
    final AtomicLong encryptedMessageReceived = new AtomicLong();

    final AtomicLong pingsReceived = new AtomicLong();
    final AtomicLong pongsSent = new AtomicLong();

    final Map<ErrorCode, AtomicLong> errorsByCode = new EnumMap<>(ErrorCode.class);
    final Map<io.ghostbunker.protocol.v1.DisconnectReason, AtomicLong> goodbyeReasons =
        new EnumMap<>(io.ghostbunker.protocol.v1.DisconnectReason.class);

    final Map<String, AtomicLong> connectFailuresByCause = new java.util.concurrent.ConcurrentHashMap<>();

    String render(long elapsedMs) {
      StringBuilder sb = new StringBuilder();
      sb.append("Results (aggregate only)\n");
      sb.append("Elapsed: ").append(elapsedMs).append(" ms\n");
      sb.append("Clients started: ").append(clientsStarted.get()).append("\n");
      sb.append("Connected: ").append(connected.get()).append("\n");
      sb.append("Closed: ").append(closed.get()).append("\n");
      sb.append("Client failures: ").append(clientFailures.get()).append("\n");
      sb.append("Decode failures: ").append(decodeFailures.get()).append("\n");

      if (!connectFailuresByCause.isEmpty()) {
        sb.append("\nConnect failures (aggregate)\n");
        connectFailuresByCause.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
            .forEach(e -> sb.append(e.getKey()).append(": ").append(e.getValue().get()).append("\n"));
      }

      sb.append("\nSent\n");
      sb.append("HELLO: ").append(helloSent.get()).append("\n");
      sb.append("JOIN_ROOM: ").append(joinSent.get()).append("\n");
      sb.append("SEND_ENCRYPTED_MESSAGE: ").append(sendEncryptedSent.get()).append("\n");
      sb.append("PONG: ").append(pongsSent.get()).append("\n");
      sb.append("\nReceived\n");
      sb.append("WELCOME: ").append(welcomeReceived.get()).append("\n");
      sb.append("ROOM_JOINED: ").append(roomJoinedReceived.get()).append("\n");
      sb.append("MESSAGE_ACCEPTED: ").append(messageAcceptedReceived.get()).append("\n");
      sb.append("ENCRYPTED_MESSAGE: ").append(encryptedMessageReceived.get()).append("\n");
      sb.append("PING: ").append(pingsReceived.get()).append("\n");

      if (!errorsByCode.isEmpty()) {
        sb.append("\nErrors by code\n");
        for (var e : errorsByCode.entrySet()) {
          sb.append(e.getKey().name()).append(": ").append(e.getValue().get()).append("\n");
        }
      }
      if (!goodbyeReasons.isEmpty()) {
        sb.append("\nGoodbye by reason\n");
        for (var e : goodbyeReasons.entrySet()) {
          sb.append(e.getKey().name()).append(": ").append(e.getValue().get()).append("\n");
        }
      }
      return sb.toString();
    }
  }

  private static Throwable unwrap(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null
        && (cur instanceof CompletionException || cur instanceof java.util.concurrent.ExecutionException)) {
      cur = cur.getCause();
    }
    return cur;
  }

  private static String rootCauseKey(Throwable t) {
    Throwable root = unwrap(t);
    String cls = root.getClass().getSimpleName();
    String msg = root.getMessage();
    if (msg == null || msg.isBlank()) return cls;
    // Keep output short and reduce accidental identifiers.
    msg = msg.replaceAll("\\s+", " ").trim();
    if (msg.length() > 140) msg = msg.substring(0, 140) + "...";
    msg = msg.replaceAll("ws(s)?://[^\\s)]+", "<ws-url>");
    msg = msg.replaceAll("(\\d{1,3}\\.){3}\\d{1,3}", "<ip>");
    msg = msg.replaceAll("localhost", "<host>");
    return cls + " - " + msg;
  }
}

