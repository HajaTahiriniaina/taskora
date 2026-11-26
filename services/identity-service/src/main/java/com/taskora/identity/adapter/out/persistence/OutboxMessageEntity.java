package com.taskora.identity.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ligne de identity.outbox_message (V2__create_outbox_message.sql) ; dates en UTC. */
@Entity
@Table(name = "outbox_message")
public class OutboxMessageEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "event_id", nullable = false, length = 36)
  private String eventId;

  @Column(name = "type", nullable = false, length = 64)
  private String type;

  @Column(name = "destination", nullable = false, length = 32)
  private String destination;

  @Column(name = "aggregate_id", nullable = false)
  private int aggregateId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @Column(name = "available_at", nullable = false)
  private LocalDateTime availableAt;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "last_error", length = 255)
  private String lastError;

  @Column(name = "delivered_at")
  private LocalDateTime deliveredAt;

  protected OutboxMessageEntity() {}

  OutboxMessageEntity(
      String eventId,
      String type,
      String destination,
      int aggregateId,
      String payload,
      LocalDateTime occurredAt,
      LocalDateTime availableAt,
      String status) {
    this.eventId = eventId;
    this.type = type;
    this.destination = destination;
    this.aggregateId = aggregateId;
    this.payload = payload;
    this.occurredAt = occurredAt;
    this.availableAt = availableAt;
    this.status = status;
  }

  String getEventId() {
    return eventId;
  }

  int getAggregateId() {
    return aggregateId;
  }

  String getPayload() {
    return payload;
  }

  int getAttempts() {
    return attempts;
  }
}
