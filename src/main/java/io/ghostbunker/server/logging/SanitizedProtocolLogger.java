package io.ghostbunker.server.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SanitizedProtocolLogger {
  private static final Logger log = LoggerFactory.getLogger(SanitizedProtocolLogger.class);

  public void info(String msg) {
    log.info(msg);
  }

  public void warn(String msg) {
    log.warn(msg);
  }

  public void error(String msg, Throwable t) {
    log.error(msg, t);
  }
}

