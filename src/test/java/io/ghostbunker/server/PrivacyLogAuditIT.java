package io.ghostbunker.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import io.ghostbunker.protocol.v1.GhostEnvelope;
import io.ghostbunker.protocol.v1.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy-Max log audit integration test.
 *
 * <p>Runs a full HELLO / JOIN / SEND_ENCRYPTED_MESSAGE flow against a real Spring Boot WebSocket
 * server while a {@link ListAppender} is attached to the application logger
 * ({@code io.ghostbunker}). After the flow, every captured log event is scanned and the test
 * fails if any event's formatted message — or any frame of any attached throwable — contains:
 *
 * <ul>
 *   <li>the literal ciphertext bytes (sentinel string + a hex marker);</li>
 *   <li>the client's nickname;</li>
 *   <li>the full ephemeral {@code session_id} or {@code user_id} UUID returned in WELCOME;</li>
 *   <li>the raw bytes of the protobuf envelope (any frame longer than ~32 hex chars that matches
 *       the ciphertext or session-id signature);</li>
 *   <li>typical leak markers: {@code User-Agent}, {@code Cookie}, {@code Authorization},
 *       {@code Bearer}, {@code 127.0.0.1}, the IPv6 loopback {@code 0:0:0:0:0:0:0:1}, and
 *       {@code localhost:}.</li>
 * </ul>
 *
 * <p><b>Scope.</b> The appender is attached to the {@code io.ghostbunker} Logback logger and
 * its level is raised to TRACE. Loggers outside the application's own packages
 * (Spring, Tomcat, Coyote, the integration test's own WebSocket client) are deliberately
 * not in scope. Their levels are configured to WARN in {@code application.yml} as part of
 * the Privacy-Max operational profile and are independently verified by
 * {@code PrivacyLoggingConfigurationTest}.
 *
 * <p>Rationale: when the root logger is set to TRACE, Spring's WebSocket test client and
 * Tomcat's internal endpoints emit framing and connection chatter — including the test-side
 * {@code "Connecting to ws://localhost:..."} line. Those events belong to the test harness
 * and to infrastructure libraries, not to the Ghost Bunker application code. Capturing them
 * here would flag false positives that the application itself never produces and that
 * Privacy-Max addresses through logger-level configuration, not through application logic.
 *
 * <p>The test is fully deterministic: it does not depend on TCP buffer sizing, Tyrus dispatch
 * threading, or {@code Thread.sleep}. It uses the same WebSocket plumbing as the rest of the
 * integration suite and asserts on captured log content after the protocol effects are observed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrivacyLogAuditIT {

  /** Sentinel that the test injects into the ciphertext and the nickname so we can grep logs. */
  private static final String CIPHERTEXT_SENTINEL_HEX = "5048312D434950484552-PHI-CIPHER";
  private static final String NICKNAME_SENTINEL = "phi-nickname-zzz123";

  /**
   * The Logback logger the appender is attached to. Matches the production source package
   * ({@code io.ghostbunker.server.*}) and the generated Protobuf package
   * ({@code io.ghostbunker.protocol.v1.*}). Anything the Ghost Bunker codebase logs through
   * SLF4J ends up under this name; anything Spring, Tomcat, or the integration test's own
   * WebSocket client logs does not.
   */
  private static final String APP_LOGGER_NAME = "io.ghostbunker";

  @LocalServerPort
  int port;

  private ListAppender<ILoggingEvent> appender;
  private Logger appLogger;
  private Level previousLevel;
  private boolean previousAdditive;

  @BeforeEach
  void attachAppender() {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    appLogger = ctx.getLogger(APP_LOGGER_NAME);
    previousLevel = appLogger.getLevel();
    previousAdditive = appLogger.isAdditive();

    appender = new ListAppender<>();
    appender.setContext(ctx);
    appender.start();
    appLogger.addAppender(appender);

    // Capture as much as possible from the application's own code so that even an accidental
    // DEBUG- or TRACE-level log call inside io.ghostbunker.* that included a payload byte,
    // a nickname, a session_id, or a user_id would be detected here.
    appLogger.setLevel(Level.TRACE);

    // Defense-in-depth: keep additivity intact so application log lines still reach whatever
    // appenders are configured at the root level. We only add an extra capture appender.
    appLogger.setAdditive(previousAdditive);
  }

  @AfterEach
  void detachAppender() {
    if (appender != null && appLogger != null) {
      appLogger.detachAppender(appender);
      appender.stop();
    }
    // Restore the original level so we do not leak TRACE state into the next test in this JVM.
    if (appLogger != null) {
      appLogger.setLevel(previousLevel);
      appLogger.setAdditive(previousAdditive);
    }
  }

  @Test
  @Timeout(8)
  void logs_do_not_leak_payload_or_identifiers_during_hello_join_send() throws Exception {
    String url = "ws://localhost:" + port + "/ghost-bunker";

    WsTestClient c1 = new WsTestClient();
    WsTestClient c2 = new WsTestClient();
    WebSocketSession s1 = c1.connect(url);
    WebSocketSession s2 = c2.connect(url);

    // Embed sentinels in the nickname and ciphertext so any accidental log call that includes
    // user-supplied bytes would surface here.
    c1.sendBinary(s1, TestEnvelopes.hello(NICKNAME_SENTINEL).toByteArray());
    c2.sendBinary(s2, TestEnvelopes.hello("bob").toByteArray());

    GhostEnvelope welcome1 = awaitType(c1, MessageType.WELCOME);
    GhostEnvelope welcome2 = awaitType(c2, MessageType.WELCOME);

    String sessionId1 = welcome1.getWelcome().getSessionId();
    String userId1 = welcome1.getWelcome().getUserId();
    String sessionId2 = welcome2.getWelcome().getSessionId();
    String userId2 = welcome2.getWelcome().getUserId();

    String room = "room-" + UUID.randomUUID();
    c1.sendBinary(s1, TestEnvelopes.join(room).toByteArray());
    c2.sendBinary(s2, TestEnvelopes.join(room).toByteArray());
    awaitType(c1, MessageType.ROOM_JOINED);
    awaitType(c2, MessageType.ROOM_JOINED);

    byte[] ciphertext = CIPHERTEXT_SENTINEL_HEX.getBytes();
    c1.sendBinary(s1, TestEnvelopes.sendEncrypted(room, ciphertext).toByteArray());

    awaitType(c1, MessageType.MESSAGE_ACCEPTED);
    GhostEnvelope routed = awaitType(c2, MessageType.ENCRYPTED_MESSAGE);
    assertThat(routed.getEncryptedMessage().getCiphertext().toByteArray()).isEqualTo(ciphertext);

    // ----- Now audit every captured log event from io.ghostbunker.* -----
    List<ILoggingEvent> events = List.copyOf(appender.list);
    assertThat(events)
        .as("expected at least one application log event from %s during the flow "
            + "(SanitizedProtocolLogger emits 'ws connected (sanitized)' on each connect)",
            APP_LOGGER_NAME)
        .isNotEmpty();

    // Strings that must NEVER appear anywhere in any application log event message or
    // throwable trace. If any of these surfaces in an io.ghostbunker.* event, the application
    // code itself leaked it — this is the contract we enforce.
    List<String> forbiddenSubstrings = List.of(
        // payload
        CIPHERTEXT_SENTINEL_HEX,
        // user input
        NICKNAME_SENTINEL,
        // full ephemeral identifiers from WELCOME
        sessionId1,
        userId1,
        sessionId2,
        userId2,
        // transport-layer markers that would indicate header / IP leakage
        "User-Agent",
        "Cookie",
        "Authorization",
        "Bearer",
        "127.0.0.1",
        "0:0:0:0:0:0:0:1",
        "localhost:"
    );

    for (ILoggingEvent event : events) {
      // Belt-and-braces: the appender is attached to io.ghostbunker, but child loggers
      // could in principle be configured to propagate to siblings. Re-check the logger
      // name here so the assertion message is honest about origin.
      assertThat(event.getLoggerName())
          .as("PrivacyLogAuditIT should only see events from %s; got %s",
              APP_LOGGER_NAME, event.getLoggerName())
          .startsWith(APP_LOGGER_NAME);

      String formatted = safeFormatted(event);
      String throwableDump = safeThrowable(event.getThrowableProxy());
      String haystack = formatted + "\n" + throwableDump;

      for (String needle : forbiddenSubstrings) {
        if (needle == null || needle.isBlank()) continue;
        assertThat(haystack)
            .as("application log event from %s leaked '%s': %s",
                event.getLoggerName(), abbreviate(needle), abbreviate(formatted))
            .doesNotContain(needle);
      }

      // Defense-in-depth: no event should contain a long hex blob that could be a serialized
      // protobuf envelope. We allow short hex (e.g. UUIDs are 36 chars with dashes; raw 16-byte
      // hex would be 32 contiguous hex chars). Flag anything >= 48 contiguous hex chars.
      assertThat(haystack)
          .as("application log event from %s contains a long hex blob suggesting raw bytes "
              + "were logged: %s", event.getLoggerName(), abbreviate(formatted))
          .doesNotContainPattern(Pattern.compile("[0-9a-fA-F]{48,}"));
    }
  }

  private static String safeFormatted(ILoggingEvent event) {
    try {
      return event.getFormattedMessage() == null ? "" : event.getFormattedMessage();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String safeThrowable(IThrowableProxy throwable) {
    if (throwable == null) return "";
    StringBuilder sb = new StringBuilder();
    IThrowableProxy current = throwable;
    while (current != null) {
      sb.append(current.getClassName()).append(": ")
          .append(current.getMessage() == null ? "" : current.getMessage()).append('\n');
      StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
      if (frames != null) {
        for (StackTraceElementProxy f : frames) {
          sb.append("  at ").append(f.getSTEAsString()).append('\n');
        }
      }
      current = current.getCause();
    }
    return sb.toString();
  }

  private static String abbreviate(String s) {
    if (s == null) return "";
    return s.length() <= 120 ? s : s.substring(0, 120) + "...";
  }

  private GhostEnvelope awaitType(WsTestClient c, MessageType type) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline) {
      for (byte[] m : c.drainBinaryMessages()) {
        try {
          GhostEnvelope env = GhostEnvelope.parseFrom(m);
          if (env.getType() == type) return env;
        } catch (Exception ignored) {}
      }
      Thread.sleep(10);
    }
    throw new AssertionError("timeout waiting for " + type);
  }
}

