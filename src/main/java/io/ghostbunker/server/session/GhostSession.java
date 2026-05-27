package io.ghostbunker.server.session;

import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GhostSession {
  private final WebSocketSession wsSession;
  private final WebSocketSession rawWsSession;
  private final String sessionId;
  private final String userId;
  private final Clock clock;
  private final int maxCommandsPerMinute;
  private final int maxMessagesPerMinute;
  private final int violationWindowMs;

  private volatile GhostSessionState state;
  private volatile String displayName;

  private final Set<String> rooms = ConcurrentHashMap.newKeySet();

  private final AtomicLong lastActivityMs = new AtomicLong();
  private final AtomicLong lastPingSentAtMs = new AtomicLong(0);
  private final AtomicLong lastPongAtMs = new AtomicLong(0);

  private final AtomicInteger outboundQueuedMessages = new AtomicInteger(0);
  private final AtomicInteger outboundPendingBytes = new AtomicInteger(0);

  private final AtomicLong commandWindowStartMs = new AtomicLong(0);
  private final AtomicInteger commandsInWindow = new AtomicInteger(0);

  private final AtomicLong messageWindowStartMs = new AtomicLong(0);
  private final AtomicInteger messagesInWindow = new AtomicInteger(0);

  private final Deque<Long> protocolViolationsMs = new ArrayDeque<>();

  private final AtomicBoolean slowClientFlagged = new AtomicBoolean(false);

  public GhostSession(WebSocketSession wsSession,
                      WebSocketSession rawWsSession,
                      String sessionId,
                      String userId,
                      Clock clock,
                      int maxCommandsPerMinute,
                      int maxMessagesPerMinute,
                      int violationWindowMs) {
    this.wsSession = wsSession;
    this.rawWsSession = rawWsSession != null ? rawWsSession : wsSession;
    this.sessionId = sessionId;
    this.userId = userId;
    this.clock = clock;
    this.maxCommandsPerMinute = maxCommandsPerMinute;
    this.maxMessagesPerMinute = maxMessagesPerMinute;
    this.violationWindowMs = violationWindowMs;
    long now = clock.millis();
    this.state = GhostSessionState.AWAITING_HELLO;
    this.lastActivityMs.set(now);
    this.commandWindowStartMs.set(now);
    this.messageWindowStartMs.set(now);
  }

  /** Convenience constructor where the raw session is the same as the decorated one. */
  public GhostSession(WebSocketSession wsSession,
                      String sessionId,
                      String userId,
                      Clock clock,
                      int maxCommandsPerMinute,
                      int maxMessagesPerMinute,
                      int violationWindowMs) {
    this(wsSession, wsSession, sessionId, userId, clock,
        maxCommandsPerMinute, maxMessagesPerMinute, violationWindowMs);
  }

  public WebSocketSession wsSession() {
    return wsSession;
  }

  /**
   * Returns the underlying (undecorated) {@link WebSocketSession}. Used for control frames such
   * as GOODBYE that must reach the peer even when the outbound buffer enforced by
   * {@code ConcurrentWebSocketSessionDecorator} has already overflowed.
   */
  public WebSocketSession rawWsSession() {
    return rawWsSession;
  }

  public String sessionId() {
    return sessionId;
  }

  public String userId() {
    return userId;
  }

  public Clock clock() {
    return clock;
  }

  public GhostSessionState state() {
    return state;
  }

  public void setState(GhostSessionState state) {
    this.state = state;
  }

  public String displayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Set<String> rooms() {
    return rooms;
  }

  public long lastActivityMs() {
    return lastActivityMs.get();
  }

  public void touchActivity() {
    lastActivityMs.set(clock.millis());
  }

  public long lastPingSentAtMs() {
    return lastPingSentAtMs.get();
  }

  public void setLastPingSentAtMs(long value) {
    lastPingSentAtMs.set(value);
  }

  public long lastPongAtMs() {
    return lastPongAtMs.get();
  }

  public void setLastPongAtMs(long value) {
    lastPongAtMs.set(value);
  }

  public boolean tryIncrementCommands() {
    return tryIncrementSlidingWindow(commandWindowStartMs, commandsInWindow, maxCommandsPerMinute);
  }

  public boolean tryIncrementMessages() {
    return tryIncrementSlidingWindow(messageWindowStartMs, messagesInWindow, maxMessagesPerMinute);
  }

  private boolean tryIncrementSlidingWindow(AtomicLong windowStartMs, AtomicInteger counter, int maxPerMinute) {
    long now = clock.millis();
    long start = windowStartMs.get();
    if (now - start >= 60_000) {
      windowStartMs.set(now);
      counter.set(0);
    }
    return counter.incrementAndGet() <= maxPerMinute;
  }

  public int outboundQueuedMessages() {
    return outboundQueuedMessages.get();
  }

  public int outboundPendingBytes() {
    return outboundPendingBytes.get();
  }

  public void onEnqueueOutbound(int messageBytes) {
    outboundQueuedMessages.incrementAndGet();
    outboundPendingBytes.addAndGet(messageBytes);
  }

  public void onDequeueOutbound(int messageBytes) {
    outboundQueuedMessages.decrementAndGet();
    outboundPendingBytes.addAndGet(-messageBytes);
  }

  public synchronized int recordProtocolViolationAndGetCountInWindow() {
    long now = clock.millis();
    protocolViolationsMs.addLast(now);
    while (!protocolViolationsMs.isEmpty() && now - protocolViolationsMs.peekFirst() > violationWindowMs) {
      protocolViolationsMs.removeFirst();
    }
    return protocolViolationsMs.size();
  }

  /**
   * Atomically flags this session as a slow client. Returns true on the first call and false on
   * subsequent calls so callers ensure GOODBYE is emitted only once per session.
   */
  public boolean markSlowClient() {
    return slowClientFlagged.compareAndSet(false, true);
  }
}

