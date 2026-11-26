package com.taskora.identity.adapter.out.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès à l'outbox ; verrouillage SELECT … FOR UPDATE SKIP LOCKED (A10, A11). */
public interface OutboxJpaRepository extends JpaRepository<OutboxMessageEntity, Long> {

  @NativeQuery(
      "SELECT * FROM outbox_message WHERE destination = :destination AND status = 'PENDING'"
          + " AND available_at <= :now ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
  List<OutboxMessageEntity> lockDue(
      @Param("destination") String destination,
      @Param("now") LocalDateTime now,
      @Param("limit") int limit);

  @NativeQuery(
      "SELECT * FROM outbox_message WHERE event_id = :eventId AND status = 'PENDING'"
          + " FOR UPDATE SKIP LOCKED")
  Optional<OutboxMessageEntity> lockPending(@Param("eventId") String eventId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE OutboxMessageEntity m SET m.status = 'DELIVERED', m.deliveredAt = :at,"
          + " m.lastError = null WHERE m.eventId = :eventId")
  int markDelivered(@Param("eventId") String eventId, @Param("at") LocalDateTime at);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE OutboxMessageEntity m SET m.attempts = :attempts, m.availableAt = :next,"
          + " m.lastError = :error WHERE m.eventId = :eventId")
  int markRetry(
      @Param("eventId") String eventId,
      @Param("attempts") int attempts,
      @Param("next") LocalDateTime next,
      @Param("error") String error);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE OutboxMessageEntity m SET m.status = 'FAILED', m.attempts = m.attempts + 1,"
          + " m.lastError = :error WHERE m.eventId = :eventId")
  int markFailed(@Param("eventId") String eventId, @Param("error") String error);

  @Modifying
  @Query(
      "DELETE FROM OutboxMessageEntity m WHERE m.status = 'DELIVERED' AND m.deliveredAt < :before")
  int purgeDelivered(@Param("before") LocalDateTime before);
}
