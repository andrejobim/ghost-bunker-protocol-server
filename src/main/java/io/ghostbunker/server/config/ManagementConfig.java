package io.ghostbunker.server.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Privacy-Max observability: aggregate metrics only, with no identifier-bearing tags.
 */
@Configuration
public class ManagementConfig {

  private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
      "ip",
      "remote_address",
      "remoteaddress",
      "address",
      "remote_addr",
      "remoteaddr",
      "user_id",
      "userid",
      "session_id",
      "sessionid",
      "room_id",
      "roomid",
      "nickname",
      "key_id",
      "keyid",
      "message_id",
      "messageid",
      "request_id",
      "requestid",
      "ciphertext",
      "payload_size",
      "payloadsize"
  );

  @Bean
  MeterRegistryCustomizer<MeterRegistry> ghostBunkerMeterFilter() {
  return registry -> registry.config().meterFilter(new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(
          Meter.Id id,
          DistributionStatisticConfig config
      ) {
        return config;
      }

      @Override
      public Meter.Id map(Meter.Id id) {
        for (String tagKey : id.getTags().stream().map(t -> t.getKey()).toList()) {
          if (FORBIDDEN_TAG_KEYS.contains(tagKey.toLowerCase())) {
            throw new IllegalStateException(
                "Privacy-Max: metric '" + id.getName() + "' uses forbidden tag '" + tagKey + "'");
          }
        }
        return id;
      }
    });
  }
}
