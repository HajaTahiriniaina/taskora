package com.taskora.board.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/** Symfony simulé (WireMock) pour {@code POST /internal/notifications}. */
public final class SymfonyStub {
  public static final String NOTIFICATIONS = "/internal/notifications";
  private static WireMockServer server;

  private SymfonyStub() {}

  public static synchronized WireMockServer server() {
    if (server == null) {
      server = new WireMockServer(wireMockConfig().dynamicPort());
      server.start();
    }
    return server;
  }

  public static String baseUrl() {
    return server().baseUrl();
  }

  /** Remet à zéro et répond 200 {"created":1} par défaut. */
  public static void reset() {
    server().resetAll();
    answer(200);
  }

  public static void answer(int status) {
    server()
        .stubFor(
            post(urlEqualTo(NOTIFICATIONS))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(status == 200 ? "{\"created\":1}" : "{\"error\":\"x\"}")));
  }

  public static void answerAfter(int status, int delayMillis) {
    server()
        .stubFor(
            post(urlEqualTo(NOTIFICATIONS))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"created\":1}")
                        .withFixedDelay(delayMillis)));
  }

  public static List<LoggedRequest> notifications() {
    return server().findAll(postRequestedFor(urlEqualTo(NOTIFICATIONS)));
  }
}
