package io.ghostbunker.server;

import io.ghostbunker.server.session.GhostSession;
import io.ghostbunker.server.session.GhostSessionState;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GhostSessionUnitTest {
  @Test
  void rate_limiter_blocks_after_limit_within_window() {
    WebSocketSession ws = mock(WebSocketSession.class);
    MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));

    GhostSession s = new GhostSession(ws, "sid", "uid", clock, 3, 2, 60_000);
    s.setState(GhostSessionState.ESTABLISHED);

    assertThat(s.tryIncrementCommands()).isTrue();
    assertThat(s.tryIncrementCommands()).isTrue();
    assertThat(s.tryIncrementCommands()).isTrue();
    assertThat(s.tryIncrementCommands()).isFalse();

    assertThat(s.tryIncrementMessages()).isTrue();
    assertThat(s.tryIncrementMessages()).isTrue();
    assertThat(s.tryIncrementMessages()).isFalse();

    clock.addMillis(60_000);
    assertThat(s.tryIncrementCommands()).isTrue();
    assertThat(s.tryIncrementMessages()).isTrue();
  }

  static final class MutableClock extends Clock {
    private final AtomicLong nowMs;

    MutableClock(Instant initial) {
      this.nowMs = new AtomicLong(initial.toEpochMilli());
    }

    void addMillis(long delta) {
      nowMs.addAndGet(delta);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(nowMs.get());
    }
  }
}

