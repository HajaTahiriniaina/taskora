package com.taskora.board.config;

import com.taskora.board.adapter.out.json.JacksonRequestBodyParser;
import com.taskora.board.adapter.out.persistence.DatabaseStartupCheck;
import com.taskora.board.adapter.out.persistence.JdbcBoardAccessQuery;
import com.taskora.board.adapter.out.persistence.JdbcBoardRepository;
import com.taskora.board.adapter.out.persistence.JdbcInvariantsQuery;
import com.taskora.board.adapter.out.persistence.JdbcInvitationRepository;
import com.taskora.board.adapter.out.persistence.JdbcMembershipRepository;
import com.taskora.board.adapter.out.persistence.JdbcOutbox;
import com.taskora.board.adapter.out.persistence.JdbcUnitOfWork;
import com.taskora.board.adapter.out.persistence.JdbcUserDirectory;
import com.taskora.board.adapter.out.persistence.LegacyClock;
import com.taskora.board.adapter.out.persistence.read.JdbcBoardReadModel;
import com.taskora.board.adapter.out.symfony.SymfonyNotificationClient;
import com.taskora.board.application.port.in.DeliverPendingMessagesUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Assemblage des adapters sortants (JDBC, Symfony, analyse JSON). */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {
  /** Lot de la livraison asynchrone lancée après validation (B-ADR-5 point 2). */
  static final int AFTER_COMMIT_BATCH = 50;

  private static final Logger LOG = LoggerFactory.getLogger(PersistenceConfig.class);

  @Bean
  public DatabaseStartupCheck databaseStartupCheck(JdbcClient jdbc, BoardProperties properties) {
    DatabaseStartupCheck check = new DatabaseStartupCheck(jdbc, properties.legacy().schema());
    check.verify();
    return check;
  }

  @Bean
  public JdbcUnitOfWork unitOfWork(
      PlatformTransactionManager transactionManager, ObjectProvider<MeterRegistry> meters) {
    return new JdbcUnitOfWork(
        transactionManager,
        () -> meters.ifAvailable(m -> m.counter("board.tx.retry.exhausted").increment()));
  }

  @Bean
  public JdbcUserDirectory userDirectory(JdbcClient jdbc) {
    return new JdbcUserDirectory(jdbc);
  }

  @Bean
  public JdbcBoardAccessQuery boardAccessQuery(JdbcClient jdbc, LegacyClock clock) {
    return new JdbcBoardAccessQuery(jdbc, clock);
  }

  @Bean
  public JdbcBoardRepository boardRepository(JdbcClient jdbc, LegacyClock clock) {
    return new JdbcBoardRepository(jdbc, clock);
  }

  @Bean
  public JdbcMembershipRepository membershipRepository(JdbcClient jdbc, LegacyClock clock) {
    return new JdbcMembershipRepository(jdbc, clock);
  }

  @Bean
  public JdbcInvitationRepository invitationRepository(JdbcClient jdbc, LegacyClock clock) {
    return new JdbcInvitationRepository(jdbc, clock);
  }

  @Bean
  public JdbcBoardReadModel boardReadModel(JdbcClient jdbc, LegacyClock clock) {
    return new JdbcBoardReadModel(jdbc, clock);
  }

  @Bean
  public JdbcInvariantsQuery invariantsQuery(JdbcClient jdbc, Clock clock) {
    return new JdbcInvariantsQuery(jdbc, clock);
  }

  /**
   * Exécuteur de la livraison asynchrone (la réponse HTTP n'attend pas Symfony). File bornée à une
   * demande, les suivantes sont écartées sans journal (§12.4 m3) : une demande en file n'a pas
   * encore réservé son lot et verra tout message déjà validé ; le relais planifié reste le filet.
   */
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService outboxDeliveryExecutor() {
    return new ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        Thread.ofPlatform().name("board-outbox-", 0).daemon(true).factory(),
        new ThreadPoolExecutor.DiscardPolicy());
  }

  @Bean
  public JdbcOutbox outbox(
      JdbcClient jdbc,
      Clock clock,
      ExecutorService outboxDeliveryExecutor,
      ObjectProvider<DeliverPendingMessagesUseCase> relay) {
    Runnable deliver =
        () -> {
          try {
            // Lot plein : d'autres messages peuvent être dus, dont ceux des demandes écartées par
            // la file bornée (m3) ; on continue jusqu'à un lot incomplet.
            int processed;
            do {
              processed = relay.getObject().deliverDue(AFTER_COMMIT_BATCH);
            } while (processed >= AFTER_COMMIT_BATCH);
          } catch (RuntimeException e) {
            // Le relais planifié reprendra le message ; aucune donnée dans le journal.
            LOG.warn("Livraison immédiate de l'outbox en échec : {}", e.getClass().getSimpleName());
          }
        };
    return new JdbcOutbox(jdbc, clock, () -> outboxDeliveryExecutor.execute(deliver));
  }

  @Bean
  public SymfonyNotificationClient symfonyNotificationClient(BoardProperties properties) {
    return new SymfonyNotificationClient(
        properties.symfony().internalUrl(), properties.internal().boardToSymfonySecret());
  }

  @Bean
  public JacksonRequestBodyParser requestBodyParser() {
    return new JacksonRequestBodyParser();
  }
}
