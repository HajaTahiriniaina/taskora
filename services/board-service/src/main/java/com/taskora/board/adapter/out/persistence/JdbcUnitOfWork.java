package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.UnitOfWorkPort;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction JDBC (B-ADR-1 point 2, isolation du serveur). Un interblocage InnoDB (1213), un délai
 * d'attente de verrou (1205) ou une lecture verrouillante d'une ligne modifiée depuis l'instantané
 * (1020, {@code innodb_snapshot_isolation} actif par défaut depuis MariaDB 11.6) annule la
 * transaction ; le cas d'utilisation entier est rejoué dans une nouvelle transaction, au plus trois
 * fois, après 10 à 50 ms (B-ADR-4 point 4). Les effets externes ne partent qu'après validation
 * (outbox) : un rejeu n'en duplique aucun.
 */
public class JdbcUnitOfWork implements UnitOfWorkPort {
  static final int MAX_ATTEMPTS = 3;
  static final int MARIADB_DEADLOCK = 1213;
  static final int MARIADB_LOCK_WAIT_TIMEOUT = 1205;
  static final int MARIADB_RECORD_CHANGED = 1020;
  private static final Logger LOG = LoggerFactory.getLogger(JdbcUnitOfWork.class);

  private final TransactionTemplate template;
  private final Runnable onRetriesExhausted;

  /**
   * Crée l'unité de travail.
   *
   * @param transactionManager gestionnaire de la {@code DataSource} unique
   * @param onRetriesExhausted compteur {@code board_tx_retry_exhausted_total}
   */
  public JdbcUnitOfWork(
      PlatformTransactionManager transactionManager, Runnable onRetriesExhausted) {
    this.template = new TransactionTemplate(transactionManager);
    this.onRetriesExhausted = onRetriesExhausted;
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return template.execute(status -> work.get());
      } catch (DataAccessException e) {
        if (!isLockConflict(e)) {
          throw e;
        }
        if (attempt >= MAX_ATTEMPTS) {
          onRetriesExhausted.run();
          throw e;
        }
        LOG.debug("Transaction rejouée après un conflit de verrou (essai {})", attempt);
        pause();
      }
    }
  }

  private static void pause() {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(10, 51));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Interblocage (1213, SQLSTATE 40001), délai d'attente de verrou (1205) ou ligne modifiée depuis
   * l'instantané (1020).
   *
   * @param failure exception
   * @return vrai si la transaction peut être rejouée
   */
  static boolean isLockConflict(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && (sql.getErrorCode() == MARIADB_DEADLOCK
              || sql.getErrorCode() == MARIADB_LOCK_WAIT_TIMEOUT
              || sql.getErrorCode() == MARIADB_RECORD_CHANGED
              || "40001".equals(sql.getSQLState()))) {
        return true;
      }
    }
    return false;
  }
}
