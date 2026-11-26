package com.taskora.identity.adapter.out.legacy;

import com.taskora.identity.application.port.out.ClaimOutcome;
import com.taskora.identity.application.port.out.InvitationClaim;
import com.taskora.identity.application.port.out.InvitationClaimPort;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client de POST /internal/invitations/claim (U2-a, A10), connexion 1 s, lecture 3 s. 2xx → livré ;
 * 400, 401, 409 (et autres 4xx définitifs) → échec définitif ; 404, 408, 429, 5xx, réseau →
 * rejouable.
 */
public class SymfonyInvitationClaimClient implements InvitationClaimPort {
  static final String PATH = "/internal/invitations/claim";
  static final String SECRET_HEADER = "X-Internal-Secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RestClient http;
  private final String secret;

  public SymfonyInvitationClaimClient(String baseUrl, String secret) {
    this(baseUrl, secret, Duration.ofSeconds(1), Duration.ofSeconds(3));
  }

  public SymfonyInvitationClaimClient(
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
  public ClaimOutcome claim(InvitationClaim claim) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("eventId", claim.eventId().toString());
    body.put("userId", claim.userId().value());
    body.put("email", claim.email().value());
    try {
      HttpStatusCode status =
          http.post()
              .uri(PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .header(SECRET_HEADER, secret)
              .body(JSON.writeValueAsBytes(body))
              .exchange((request, response) -> response.getStatusCode(), true);
      return outcome(status);
    } catch (RestClientException e) {
      return ClaimOutcome.RETRYABLE_FAILURE;
    }
  }

  static ClaimOutcome outcome(HttpStatusCode status) {
    int code = status.value();
    if (status.is2xxSuccessful()) {
      return ClaimOutcome.DELIVERED;
    }
    if (code == 404 || code == 408 || code == 429 || status.is5xxServerError()) {
      return ClaimOutcome.RETRYABLE_FAILURE;
    }
    return status.is4xxClientError()
        ? ClaimOutcome.PERMANENT_FAILURE
        : ClaimOutcome.RETRYABLE_FAILURE;
  }
}
