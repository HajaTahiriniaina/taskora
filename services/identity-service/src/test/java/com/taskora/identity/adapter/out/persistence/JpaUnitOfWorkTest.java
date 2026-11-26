package com.taskora.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * SC-IDENT-002 (A7, m5) : une transaction annulée par un interblocage InnoDB (code 1213 ou SQLSTATE
 * 40001) est rejouée en entier, au plus 3 essais ; un simple délai d'attente de verrou ou toute
 * autre erreur n'est pas rejoué.
 */
class JpaUnitOfWorkTest {

  /**
   * Gestionnaire de transactions factice : compte les transactions ouvertes, validées, annulées.
   */
  static final class FakeTransactionManager implements PlatformTransactionManager {
    int begun;
    int commits;
    int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      begun++;
      return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
    }
  }

  private final FakeTransactionManager tx = new FakeTransactionManager();
  private final JpaUnitOfWork uow = new JpaUnitOfWork(tx);

  private static SQLException mariaDbDeadlock() {
    return new SQLException("Deadlock found when trying to get lock", "HY000", 1213);
  }

  private static SQLException sqlState40001() {
    return new SQLException("serialization failure", "40001", 0);
  }

  private static SQLException lockWaitTimeout() {
    return new SQLException("Lock wait timeout exceeded", "HY000", 1205);
  }

  /** Travail qui lève les exceptions données dans l'ordre, puis renvoie « ok ». */
  private static Supplier<String> failing(AtomicInteger calls, RuntimeException... failures) {
    Deque<RuntimeException> queue = new ArrayDeque<>(java.util.List.of(failures));
    return () -> {
      calls.incrementAndGet();
      RuntimeException next = queue.poll();
      if (next != null) {
        throw next;
      }
      return "ok";
    };
  }

  @Test
  @DisplayName(
      "SC-IDENT-002 m5 — interblocage MariaDB (1213) : rejoué, succès au 2e essai, résultat renvoyé")
  void mariaDbDeadlockRetried() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        uow.inTransaction(
            failing(calls, new CannotAcquireLockException("interblocage", mariaDbDeadlock())));
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
    assertThat(tx.begun).isEqualTo(2);
    assertThat(tx.rollbacks).isEqualTo(1);
    assertThat(tx.commits).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — SQLSTATE 40001 : rejoué")
  void sqlState40001Retried() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        uow.inTransaction(
            failing(calls, new PessimisticLockingFailureException("40001", sqlState40001())));
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — code 1213 enfoui dans la chaîne des causes : rejoué")
  void nestedDeadlockRetried() {
    AtomicInteger calls = new AtomicInteger();
    RuntimeException wrapped =
        new CannotAcquireLockException(
            "interblocage", new IllegalStateException("couche JPA", mariaDbDeadlock()));
    assertThat(uow.inTransaction(failing(calls, wrapped))).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — deux interblocages puis succès : 3 essais, résultat renvoyé")
  void twoDeadlocksThenSuccess() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        uow.inTransaction(
            failing(
                calls,
                new CannotAcquireLockException("1", mariaDbDeadlock()),
                new CannotAcquireLockException("2", sqlState40001())));
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
    assertThat(tx.commits).isEqualTo(1);
    assertThat(tx.rollbacks).isEqualTo(2);
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — 3e interblocage consécutif : propagé, exactement 3 essais")
  void thirdDeadlockPropagates() {
    AtomicInteger calls = new AtomicInteger();
    CannotAcquireLockException third = new CannotAcquireLockException("3", mariaDbDeadlock());
    Supplier<String> work =
        failing(
            calls,
            new CannotAcquireLockException("1", mariaDbDeadlock()),
            new CannotAcquireLockException("2", mariaDbDeadlock()),
            third,
            new CannotAcquireLockException("4", mariaDbDeadlock()));
    assertThatThrownBy(() -> uow.inTransaction(work)).isSameAs(third);
    assertThat(calls).hasValue(JpaUnitOfWork.MAX_ATTEMPTS).hasValue(3);
    assertThat(tx.rollbacks).isEqualTo(3);
    assertThat(tx.commits).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — simple délai d'attente de verrou (1205) : NON rejoué")
  void lockWaitTimeoutNotRetried() {
    AtomicInteger calls = new AtomicInteger();
    CannotAcquireLockException timeout =
        new CannotAcquireLockException("attente", lockWaitTimeout());
    assertThatThrownBy(() -> uow.inTransaction(failing(calls, timeout))).isSameAs(timeout);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("SC-IDENT-002 m5 — PessimisticLockingFailureException sans cause SQL : NON rejouée")
  void lockingFailureWithoutSqlCauseNotRetried() {
    AtomicInteger calls = new AtomicInteger();
    PessimisticLockingFailureException plain = new PessimisticLockingFailureException("verrou");
    assertThatThrownBy(() -> uow.inTransaction(failing(calls, plain))).isSameAs(plain);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("SC-IDENT-002 — violation d'unicité (1062) : NON rejouée")
  void integrityViolationNotRetried() {
    AtomicInteger calls = new AtomicInteger();
    DataIntegrityViolationException duplicate =
        new DataIntegrityViolationException(
            "doublon", new SQLException("Duplicate entry", "23000", 1062));
    assertThatThrownBy(() -> uow.inTransaction(failing(calls, duplicate))).isSameAs(duplicate);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName(
      "SC-IDENT-002 — autre exception (même avec une cause 1213 hors verrouillage) : NON rejouée")
  void otherExceptionsNotRetried() {
    AtomicInteger calls = new AtomicInteger();
    IllegalStateException other = new IllegalStateException("autre", mariaDbDeadlock());
    assertThatThrownBy(() -> uow.inTransaction(failing(calls, other))).isSameAs(other);
    assertThat(calls).hasValue(1);
    assertThat(tx.rollbacks).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-002 — sans erreur : un seul essai, transaction validée")
  void success() {
    AtomicInteger calls = new AtomicInteger();
    assertThat(uow.inTransaction(failing(calls))).isEqualTo("ok");
    assertThat(calls).hasValue(1);
    assertThat(tx.commits).isEqualTo(1);
    assertThat(tx.rollbacks).isZero();
  }
}
