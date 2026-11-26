package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.out.ClaimOutcome;
import com.taskora.identity.application.port.out.InvitationClaim;
import com.taskora.identity.application.port.out.InvitationClaimPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PendingClaim;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutboxDeliveryServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private OutboxPort outbox;
  private InvitationClaimPort client;
  private OutboxDeliveryService service;

  @BeforeEach
  void setUp() {
    outbox = mock(OutboxPort.class);
    client = mock(InvitationClaimPort.class);
    service =
        new OutboxDeliveryService(
            outbox, client, new FakeUnitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PendingClaim pending(int attempts) {
    return new PendingClaim(
        new InvitationClaim(UUID.randomUUID(), new UserId(4), new Email("camille@exemple.fr")),
        attempts);
  }

  @Test
  @DisplayName("SC-IDENT-005 — DELIVERED → markDelivered(eventId, maintenant)")
  void delivered() {
    PendingClaim p = pending(0);
    when(outbox.lockDueClaims(NOW, 20)).thenReturn(List.of(p));
    when(client.claim(p.claim())).thenReturn(ClaimOutcome.DELIVERED);
    service.deliverDue(20);
    verify(outbox).markDelivered(p.claim().eventId(), NOW);
    verify(outbox, never()).markRetry(any(), anyInt(), any(), any());
  }

  @ParameterizedTest(name = "tentatives {0} → nouvelle tentative dans {1} s")
  @CsvSource({"0,5", "1,10", "2,20", "3,40", "4,80", "5,160", "6,300", "7,300", "30,300"})
  @DisplayName(
      "SC-IDENT-005 — RETRYABLE_FAILURE → markRetry, backoff 5 s, 10 s, 20 s… plafonné à 5 min, sans limite")
  void retryBackoff(int previousAttempts, long delaySeconds) {
    PendingClaim p = pending(previousAttempts);
    when(outbox.lockDueClaims(NOW, 20)).thenReturn(List.of(p));
    when(client.claim(p.claim())).thenReturn(ClaimOutcome.RETRYABLE_FAILURE);
    service.deliverDue(20);
    verify(outbox)
        .markRetry(
            eq(p.claim().eventId()),
            eq(previousAttempts + 1),
            eq(NOW.plusSeconds(delaySeconds)),
            anyString());
    verify(outbox, never()).markFailed(any(), any());
    verify(outbox, never()).markDelivered(any(), any());
  }

  @Test
  @DisplayName("SC-IDENT-005 — PERMANENT_FAILURE (401/400/409) → markFailed")
  void permanent() {
    PendingClaim p = pending(2);
    when(outbox.lockDueClaims(NOW, 20)).thenReturn(List.of(p));
    when(client.claim(p.claim())).thenReturn(ClaimOutcome.PERMANENT_FAILURE);
    service.deliverDue(20);
    verify(outbox).markFailed(eq(p.claim().eventId()), anyString());
    verify(outbox, never()).markRetry(any(), anyInt(), any(), any());
  }

  @Test
  @DisplayName("SC-IDENT-005 — exception du client → traitée comme rejouable, le lot continue")
  void clientExceptionIsRetryable() {
    PendingClaim a = pending(0);
    PendingClaim b = pending(0);
    when(outbox.lockDueClaims(NOW, 20)).thenReturn(List.of(a, b));
    when(client.claim(a.claim())).thenThrow(new IllegalStateException("délai dépassé"));
    when(client.claim(b.claim())).thenReturn(ClaimOutcome.DELIVERED);
    service.deliverDue(20);
    verify(outbox).markRetry(eq(a.claim().eventId()), eq(1), eq(NOW.plusSeconds(5)), anyString());
    verify(outbox).markDelivered(b.claim().eventId(), NOW);
  }

  @Test
  @DisplayName("SC-IDENT-005 — taille de lot transmise ; lot vide → aucun appel")
  void emptyBatch() {
    when(outbox.lockDueClaims(NOW, 7)).thenReturn(List.of());
    service.deliverDue(7);
    verify(outbox).lockDueClaims(NOW, 7);
    verifyNoInteractions(client);
  }

  @Test
  @DisplayName("SC-IDENT-005 — deliverNow : message verrouillé et livré → true")
  void deliverNowDelivered() {
    PendingClaim p = pending(0);
    when(outbox.lockClaim(p.claim().eventId())).thenReturn(Optional.of(p));
    when(client.claim(p.claim())).thenReturn(ClaimOutcome.DELIVERED);
    assertThat(service.deliverNow(p.claim().eventId())).isTrue();
    verify(outbox).markDelivered(p.claim().eventId(), NOW);
  }

  @Test
  @DisplayName("SC-IDENT-005 — deliverNow : échec rejouable → false, message replanifié dans 5 s")
  void deliverNowRetry() {
    PendingClaim p = pending(0);
    when(outbox.lockClaim(p.claim().eventId())).thenReturn(Optional.of(p));
    when(client.claim(p.claim())).thenReturn(ClaimOutcome.RETRYABLE_FAILURE);
    assertThat(service.deliverNow(p.claim().eventId())).isFalse();
    verify(outbox).markRetry(eq(p.claim().eventId()), eq(1), eq(NOW.plusSeconds(5)), anyString());
  }

  @Test
  @DisplayName(
      "SC-IDENT-005 — deliverNow : message déjà pris par le relais (non verrouillable) → false, aucun appel")
  void deliverNowAlreadyTaken() {
    UUID id = UUID.randomUUID();
    when(outbox.lockClaim(id)).thenReturn(Optional.empty());
    assertThat(service.deliverNow(id)).isFalse();
    verifyNoInteractions(client);
  }
}
