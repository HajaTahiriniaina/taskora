package com.taskora.board.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.SymfonyStub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mineur 3 (§12.3, B-ADR-5 point 2) — l'exécuteur de la livraison post-validation a une file bornée
 * : une rafale de transactions validées ne fait pas grossir la file ; aucune demande écartée ne
 * perd de message (la tâche en attente n'a pas encore réservé son lot).
 */
class OutboxDeliveryExecutorIT extends ApiTestBase {
  private static final int BURST = 30;
  private static final int FIRST_ID = 2_001;

  /** Comptes synthétiques {@code rafale-NN@exemple.test}, sans mot de passe réel. */
  private static void insertBurstUsers() {
    List<String> statements = new ArrayList<>();
    for (int i = 0; i < BURST; i++) {
      int id = FIRST_ID + i;
      statements.add(
          "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at)"
              + " VALUES ("
              + id
              + ", 'rafale-"
              + i
              + "@exemple.test', 'Rafale "
              + i
              + "', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel."
              + id
              + "', '#6366f1', '[]', '2026-01-10 09:00:00')");
    }
    BoardDatabase.rootExecute(statements);
  }

  @Test
  @DisplayName(
      "m3 — rafale de 30 ajouts validés : file de l'exécuteur ≤ 1 à chaque échantillon, puis toutes"
          + " les notifications livrées (aucun réveil perdu)")
  void afterCommitQueueBounded() {
    insertBurstUsers();
    Object bean = context.getBean("outboxDeliveryExecutor");
    assertThat(bean)
        .as("outboxDeliveryExecutor est un ThreadPoolExecutor")
        .isInstanceOf(ThreadPoolExecutor.class);
    ThreadPoolExecutor executor = (ThreadPoolExecutor) bean;
    SymfonyStub.server().resetMappings();
    SymfonyStub.answerAfter(200, 2_000);

    AtomicInteger maxQueue = new AtomicInteger();
    AtomicInteger samples = new AtomicInteger();
    ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    try {
      sampler.scheduleAtFixedRate(
          () -> {
            maxQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
            samples.incrementAndGet();
          },
          0,
          10,
          TimeUnit.MILLISECONDS);
      for (int i = 0; i < BURST; i++) {
        assertStatus(
            post(
                alice(),
                "/api/boards/101/members",
                "{\"email\":\"rafale-" + i + "@exemple.test\"}"),
            201);
      }
      // Dernier échantillon après la rafale, avant tout retour du stub.
      maxQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
    } finally {
      sampler.shutdownNow();
    }
    assertThat(samples.get()).as("échantillons pris pendant la rafale").isPositive();
    assertThat(maxQueue.get())
        .as("taille maximale de la file de outboxDeliveryExecutor pendant la rafale")
        .isLessThanOrEqualTo(1);

    // Symfony répond de nouveau sans délai : les messages restants partent vite, mais seulement
    // si une demande de livraison les a bien vus (le relais planifié est désactivé).
    SymfonyStub.reset();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThat(outbox("symfony-notifications"))
                .hasSize(2 * BURST)
                .allSatisfy(m -> assertThat(m.get("status")).isEqualTo("DELIVERED")));
  }
}
