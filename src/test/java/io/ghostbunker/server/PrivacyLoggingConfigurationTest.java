package io.ghostbunker.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy-Max logging configuration test.
 *
 * <p>{@link PrivacyLogAuditIT} captures only log events from {@code io.ghostbunker.*} — that is
 * the right scope for auditing the application's own code, but it deliberately does not check
 * what third-party libraries (Tomcat, Coyote, Spring Web, Spring WebSocket) emit. Those
 * libraries can produce identifiable lines (remote address, session URI, upgrade chatter)
 * at DEBUG/TRACE level if they are configured to do so. The Privacy-Max operational profile
 * pins their levels to WARN and disables Tomcat access logs so the operator cannot
 * accidentally re-enable identifiable third-party output without first changing
 * {@code application.yml}.
 *
 * <p>This test loads the real Spring Boot configuration (no overrides) and asserts the
 * effective logger levels and the access-log property match the Privacy-Max contract
 * documented in {@code docs/privacy-max-profile-v0.1.md}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {GhostBunkerReferenceServerApplication.class, PrivacyLoggingConfigurationTest.TestOverrides.class},
    properties = {
        "spring.main.allow-bean-definition-overriding=true"
    }
)
class PrivacyLoggingConfigurationTest {

  @Autowired
  Environment environment;

  /**
   * Test-only overrides to make the real application context boot in a mock servlet environment.
   *
   * <p>The production {@code WebSocketConfig} registers a {@code ServletServerContainerFactoryBean}
   * which requires the {@code ServletContext} attribute {@code jakarta.websocket.server.ServerContainer}
   * to be present. In production Tomcat provides it; in mock servlet tests we provide a minimal
   * stub so the bean can initialize without changing production logic.
   */
  @TestConfiguration
  static class TestOverrides {
    @Bean
    ServletServerContainerFactoryBean createWebSocketContainer() {
      // In production, Tomcat provides the ServerContainer in the ServletContext.
      // This test runs with web-application-type=none and only validates logging defaults,
      // so we override the bean with a no-op initializer.
      return new ServletServerContainerFactoryBean() {
        @Override
        public void afterPropertiesSet() {
          // no-op
        }
      };
    }
  }

  @Test
  void tomcat_logger_is_at_least_warn() {
    assertEffectiveLevelAtLeast("org.apache.tomcat", Level.WARN);
  }

  @Test
  void coyote_logger_is_at_least_warn() {
    assertEffectiveLevelAtLeast("org.apache.coyote", Level.WARN);
  }

  @Test
  void spring_web_logger_is_at_least_warn() {
    assertEffectiveLevelAtLeast("org.springframework.web", Level.WARN);
  }

  @Test
  void spring_web_socket_logger_is_at_least_warn() {
    assertEffectiveLevelAtLeast("org.springframework.web.socket", Level.WARN);
  }

  @Test
  void tomcat_access_log_is_explicitly_disabled() {
    // Spring Boot maps server.tomcat.accesslog.enabled into the embedded container. We assert
    // it is explicitly set to "false" in application.yml so the contract is documented at the
    // configuration layer and would survive a Spring Boot default change.
    String value = environment.getProperty("server.tomcat.accesslog.enabled");
    assertThat(value)
        .as("server.tomcat.accesslog.enabled must be explicitly pinned to 'false' for "
            + "Privacy-Max; got '%s' from application.yml", value)
        .isNotNull()
        .isEqualToIgnoringCase("false");
  }

  /**
   * Asserts that the effective Logback level for {@code loggerName} is at least as restrictive
   * as {@code minimum}. "At least as restrictive" means the effective level's integer code is
   * greater than or equal to the minimum's integer code (Logback uses higher numbers for more
   * restrictive levels: OFF &gt; ERROR &gt; WARN &gt; INFO &gt; DEBUG &gt; TRACE &gt; ALL).
   * If the logger has no level of its own, the inherited effective level is used.
   */
  private static void assertEffectiveLevelAtLeast(String loggerName, Level minimum) {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = ctx.getLogger(loggerName);
    Level effective = logger.getEffectiveLevel();
    assertThat(effective)
        .as("Privacy-Max requires logger '%s' to be at least %s; effective level is %s",
            loggerName, minimum, effective)
        .isNotNull();
    assertThat(effective.toInt())
        .as("Privacy-Max requires logger '%s' to be at least %s (level code %d); "
            + "effective level is %s (level code %d)",
            loggerName, minimum, minimum.toInt(), effective, effective.toInt())
        .isGreaterThanOrEqualTo(minimum.toInt());
  }
}
