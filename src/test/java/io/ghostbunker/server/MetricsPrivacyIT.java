package io.ghostbunker.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsPrivacyIT {

  private static final List<String> FORBIDDEN_LABEL_KEYS = List.of(
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

  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate rest;

  @Test
  void prometheus_exposition_does_not_include_forbidden_label_keys() {
    String body = rest.getForObject("http://localhost:" + port + "/actuator/prometheus", String.class);
    assertThat(body).isNotNull();

    for (String key : FORBIDDEN_LABEL_KEYS) {
      Pattern labelKey = Pattern.compile("(^|[,{])" + Pattern.quote(key) + "=", Pattern.MULTILINE);
      assertThat(labelKey.matcher(body).find())
          .as("prometheus exposition must not include forbidden label key '%s'", key)
          .isFalse();
    }
  }
}

