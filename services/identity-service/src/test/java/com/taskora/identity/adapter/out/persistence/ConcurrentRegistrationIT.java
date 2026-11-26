package com.taskora.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.support.ApiTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** SC-IDENT-002 : inscriptions simultanées avec le même e-mail (A7). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentRegistrationIT extends ApiTestBase {
  @LocalServerPort int port;

  @Test
  @DisplayName(
      "SC-IDENT-002 — 8 inscriptions parallèles (casses différentes) → 1 × 201, 7 × 409, 0 × 500, 1 ligne par table")
  void parallelRegistrations() throws Exception {
    String[] emails = {
      "camille@exemple.fr", "CAMILLE@exemple.fr", "Camille@Exemple.fr", "camille@EXEMPLE.FR",
      "cAmille@exemple.fr", "caMille@exemple.fr", "camILLE@exemple.fr", "CAMILLE@EXEMPLE.FR"
    };
    HttpClient http = HttpClient.newHttpClient();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(emails.length);
    List<Future<Integer>> results = new ArrayList<>();
    for (String e : emails) {
      results.add(
          pool.submit(() -> {
            HttpRequest req =
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/auth/register"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"name\":\"Camille\",\"email\":\""
                                + e
                                + "\",\"password\":\"motdepasse\"}"))
                    .build();
            start.await();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
          }));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get());
    }
    pool.shutdown();
    Map<Integer, Long> byStatus =
        statuses.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
    assertThat(byStatus).containsEntry(201, 1L).containsEntry(409, 7L).doesNotContainKey(500);
    assertThat(count("SELECT COUNT(*) FROM identity.user_account")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM taskora.`user`")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM identity.outbox_message WHERE type = 'UserRegistered'"))
        .isEqualTo(1);
  }
}
