package com.taskora.identity.adapter.out.persistence;

import com.taskora.identity.application.port.out.UnitOfWorkPort;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction JPA partagée par JDBC (même DataSource) : écriture identity, projection taskora.user
 * et outbox dans la même transaction (A9, A11).
 *
 * <p>Un interblocage InnoDB (inscriptions simultanées sur le même e-mail : verrous de l'index
 * unique) annule la transaction ; elle est rejouée en entier, au plus trois fois. Le rejeu tombe
 * alors sur la violation d'unicité → 409, jamais 500 (SC-IDENT-002).
 */
public class JpaUnitOfWork implements UnitOfWorkPort {
  static final int MAX_ATTEMPTS = 3;
  static final int MARIADB_DEADLOCK = 1213;
  private static final Logger LOG = LoggerFactory.getLogger(JpaUnitOfWork.class);

  private final TransactionTemplate template;

  public JpaUnitOfWork(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return template.execute(status -> work.get());
      } catch (PessimisticLockingFailureException e) {
        if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) {
          throw e;
        }
        LOG.debug("Transaction rejouée après un interblocage (essai {})", attempt);
      }
    }
  }

  /**
   * Interblocage InnoDB seulement (code 1213, SQLSTATE 40001), pas un simple délai d'attente de
   * verrou : Hibernate le traduit en CannotAcquireLockException et non en
   * DeadlockLoserDataAccessException (dépréciée), d'où l'examen de la cause SQL (m5).
   */
  static boolean isDeadlock(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && (sql.getErrorCode() == MARIADB_DEADLOCK || "40001".equals(sql.getSQLState()))) {
        return true;
      }
    }
    return false;
  }
}
