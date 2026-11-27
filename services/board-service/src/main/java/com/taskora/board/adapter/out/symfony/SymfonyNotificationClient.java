package com.taskora.board.adapter.out.symfony;

import com.taskora.board.application.port.out.DeliveryOutcome;
import com.taskora.board.application.port.out.NotificationDeliveryPort;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client de {@code POST /internal/notifications} (Symfony, B-ADR-5), secret {@code
 * INTERNAL_SECRET_BOARD_TO_SYMFONY} (U12), connexion 1 s, lecture 3 s. 2xx → livré ; 400, 401, 409
 * et autres 4xx définitifs → échec définitif ; 404, 408, 429, 5xx, réseau, délai → rejouable.
 */
public class SymfonyNotificationClient implements NotificationDeliveryPort {
  static final String PATH = "/internal/notifications";
  static final String SECRET_HEADER = "X-Internal-Secret";

  private final RestClient http;
  private final String secret;

  public SymfonyNotificationClient(String baseUrl, String secret) {
    this(baseUrl, secret, Duration.ofSeconds(1), Duration.ofSeconds(3));
  }

  public SymfonyNotificationClient(
      String baseUrl, String secret, Duration connectTimeout, Duration readTimeout) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(readTimeout);
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.secret = secret;
  }

  @Override
  public DeliveryOutcome deliver(String payloadJson) {
    try {
      HttpStatusCode status =
          http.post()
              .uri(PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .header(SECRET_HEADER, secret)
              .body(payloadJson.getBytes(StandardCharsets.UTF_8))
              .exchange((request, response) -> response.getStatusCode(), true);
      return outcome(status);
    } catch (RestClientException e) {
      return DeliveryOutcome.RETRYABLE_FAILURE;
    }
  }

  static DeliveryOutcome outcome(HttpStatusCode status) {
    int code = status.value();
    if (status.is2xxSuccessful()) {
      return DeliveryOutcome.DELIVERED;
    }
    if (code == 404 || code == 408 || code == 429 || status.is5xxServerError()) {
      return DeliveryOutcome.RETRYABLE_FAILURE;
    }
    return status.is4xxClientError()
        ? DeliveryOutcome.PERMANENT_FAILURE
        : DeliveryOutcome.RETRYABLE_FAILURE;
  }
}
