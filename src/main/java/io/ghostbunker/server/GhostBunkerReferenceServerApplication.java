package io.ghostbunker.server;

import io.ghostbunker.server.config.GhostBunkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GhostBunkerProperties.class)
public class GhostBunkerReferenceServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(GhostBunkerReferenceServerApplication.class, args);
  }
}

