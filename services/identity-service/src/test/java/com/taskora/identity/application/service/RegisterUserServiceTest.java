package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.RegisterUserCommand;
import com.taskora.identity.application.port.out.InvitationClaim;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.event.DomainEvent;
import com.taskora.identity.domain.event.UserRegistered;
import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.exception.ValidationException;
import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.NewUser;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RegisterUserServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");
  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

  private UserRepositoryPort users;
  private PasswordHasherPort hasher;
  private TokenIssuerPort tokens;
  private LegacyUserProjectionPort projection;
  private OutboxPort outbox;
  private DeliverPendingMessagesUseCase delivery;
  private FakeUnitOfWork uow;
  private RegisterUserService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepositoryPort.class);
    hasher = mock(PasswordHasherPort.class);
    tokens = mock(TokenIssuerPort.class);
    projection = mock(LegacyUserProjectionPort.class);
    outbox = mock(OutboxPort.class);
    delivery = mock(DeliverPendingMessagesUseCase.class);
    uow = new FakeUnitOfWork();
    service =
        new RegisterUserService(
            users, hasher, tokens, projection, outbox, uow, delivery,
            Clock.fixed(NOW, ZoneOffset.UTC), PARIS, new SplittableRandom(7));

    when(hasher.hash(any())).thenReturn(new HashedPassword(Users.HASH));
    when(tokens.issue(any())).thenReturn("jeton.de.test");
    when(users.insert(any()))
        .thenAnswer(
            inv -> {
              uow.assertActive("insert");
              NewUser n = inv.getArgument(0);
              return User.rehydrate(
                  new UserId(4), n.email(), n.name(), n.password(), n.avatarColor(), n.roles(), n.createdAt());
            });
    doAnswer(inv -> { uow.assertActive("projection.upsert"); return null; }).when(projection).upsert(any());
    doAnswer(inv -> { uow.assertActive("outbox.appendInvitationClaim"); return null; })
        .when(outbox).appendInvitationClaim(any(), any());
    doAnswer(inv -> { uow.assertActive("outbox.appendEvent"); return null; }).when(outbox).appendEvent(any());
    when(delivery.deliverNow(any()))
        .thenAnswer(inv -> { uow.assertInactive("deliverNow"); return true; });
  }

  private AuthResult registerCamille() {
    return service.register(new RegisterUserCommand("  Camille Martin ", " Camille@Exemple.FR ", "motdepasse"));
  }

  @Test
  @DisplayName("SC-IDENT-001 — inscription nominale : compte normalisé, haché, palette, created_at en heure de Paris")
  void nominal() {
    AuthResult r = registerCamille();

    ArgumentCaptor<NewUser> captor = ArgumentCaptor.forClass(NewUser.class);
    verify(users).insert(captor.capture());
    NewUser n = captor.getValue();
    assertThat(n.email().value()).isEqualTo("camille@exemple.fr");
    assertThat(n.name().value()).isEqualTo("Camille Martin");
    assertThat(n.password().value()).isEqualTo(Users.HASH);
    assertThat(AvatarColor.PALETTE).contains(n.avatarColor().value());
    assertThat(n.roles().stored()).isEmpty();
    assertThat(n.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));

    assertThat(r.token()).isEqualTo("jeton.de.test");
    assertThat(r.user().id()).isEqualTo(4);
    assertThat(r.user().name()).isEqualTo("Camille Martin");
    assertThat(r.user().email()).isEqualTo("camille@exemple.fr");
    assertThat(r.user().avatarColor()).isEqualTo(n.avatarColor().value());
  }

  @Test
  @DisplayName("SC-IDENT-002 — e-mail déjà utilisé → EmailAlreadyUsedException, aucune écriture ni hachage")
  void duplicate() {
    when(users.existsByEmail(new Email("camille@exemple.fr"))).thenReturn(true);
    assertThatThrownBy(this::registerCamille).isInstanceOf(EmailAlreadyUsedException.class);
    verify(users, never()).insert(any());
    verifyNoInteractions(projection, outbox, delivery, tokens);
    verify(hasher, never()).hash(any());
  }

  @Test
  @DisplayName("SC-IDENT-002 — course perdue (violation d'unicité à l'insertion) → EmailAlreadyUsedException, ni projection ni outbox")
  void duplicateRace() {
    doThrow(new EmailAlreadyUsedException()).when(users).insert(any());
    assertThatThrownBy(this::registerCamille).isInstanceOf(EmailAlreadyUsedException.class);
    verifyNoInteractions(projection, outbox, delivery, tokens);
  }

  @Test
  @DisplayName("SC-IDENT-003 — ordre : name avant email")
  void nameFirst() {
    assertThatThrownBy(() -> service.register(new RegisterUserCommand(" ", "pas-un-email", "1")))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"name\" est obligatoire.");
  }

  @Test
  @DisplayName("SC-IDENT-003 — ordre : email (obligatoire) avant password")
  void emailRequiredBeforePassword() {
    assertThatThrownBy(() -> service.register(new RegisterUserCommand("Camille", null, "1")))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"email\" est obligatoire.");
  }

  @Test
  @DisplayName("SC-IDENT-003 — ordre : format de l'e-mail avant password")
  void emailFormatBeforePassword() {
    assertThatThrownBy(() -> service.register(new RegisterUserCommand("Camille", "pas-un-email", "1")))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-003 — ordre : longueur du mot de passe avant l'unicité (dépôt non consulté)")
  void passwordBeforeUniqueness() {
    when(users.existsByEmail(any())).thenReturn(true);
    assertThatThrownBy(() -> service.register(new RegisterUserCommand("Camille", "camille@exemple.fr", "1234567")))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le mot de passe doit contenir au moins 8 caractères.");
    verify(users, never()).existsByEmail(any());
  }

  @Test
  @DisplayName("SC-IDENT-003 — mot de passe absent (null) → message de longueur")
  void passwordAbsent() {
    assertThatThrownBy(() -> service.register(new RegisterUserCommand("Camille", "camille@exemple.fr", null)))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le mot de passe doit contenir au moins 8 caractères.");
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — > 4096 octets → PasswordTooLongException avant l'unicité, sans écriture ni hachage")
  void passwordTooLong() {
    when(users.existsByEmail(any())).thenReturn(true);
    assertThatThrownBy(
            () -> service.register(new RegisterUserCommand("Camille", "camille@exemple.fr", "a".repeat(4097))))
        .isInstanceOf(PasswordTooLongException.class);
    verify(users, never()).existsByEmail(any());
    verify(users, never()).insert(any());
    verify(hasher, never()).hash(any());
    verifyNoInteractions(projection, outbox, delivery);
  }

  @Test
  @DisplayName("SC-IDENT-005 — une seule unité de travail : insert + projection + claim immédiat + claim à +60 s + UserRegistered")
  void oneUnitOfWorkWithThreeOutboxMessages() {
    registerCamille();
    assertThat(uow.transactions).isEqualTo(1);
    verify(projection).upsert(any());

    ArgumentCaptor<InvitationClaim> claims = ArgumentCaptor.forClass(InvitationClaim.class);
    ArgumentCaptor<Instant> due = ArgumentCaptor.forClass(Instant.class);
    verify(outbox, times(2)).appendInvitationClaim(claims.capture(), due.capture());
    assertThat(due.getAllValues()).containsExactly(NOW, NOW.plusSeconds(60));
    InvitationClaim first = claims.getAllValues().get(0);
    InvitationClaim catchUp = claims.getAllValues().get(1);
    assertThat(first.userId()).isEqualTo(new UserId(4));
    assertThat(first.email().value()).isEqualTo("camille@exemple.fr");
    assertThat(catchUp.userId()).isEqualTo(first.userId());
    assertThat(catchUp.email()).isEqualTo(first.email());
    assertThat(catchUp.eventId()).isNotEqualTo(first.eventId());

    ArgumentCaptor<DomainEvent> events = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox).appendEvent(events.capture());
    assertThat(events.getValue()).isInstanceOf(UserRegistered.class);
    UserRegistered e = (UserRegistered) events.getValue();
    assertThat(e.userId()).isEqualTo(new UserId(4));
    assertThat(e.email()).isEqualTo("camille@exemple.fr");
    assertThat(e.name()).isEqualTo("Camille Martin");
    assertThat(AvatarColor.PALETTE).contains(e.avatarColor());
    assertThat(e.occurredAt()).isEqualTo(NOW);
    assertThat(e.eventId()).isNotIn(first.eventId(), catchUp.eventId());
  }

  @Test
  @DisplayName("SC-IDENT-005 — livraison synchrone du PREMIER claim, APRÈS la validation de la transaction")
  void deliverNowAfterCommit() {
    registerCamille();
    ArgumentCaptor<InvitationClaim> claims = ArgumentCaptor.forClass(InvitationClaim.class);
    verify(outbox, times(2)).appendInvitationClaim(claims.capture(), any());
    verify(delivery).deliverNow(claims.getAllValues().get(0).eventId());
  }

  @Test
  @DisplayName("SC-IDENT-005 — échec de la livraison synchrone (exception) → l'inscription réussit quand même")
  void deliveryFailureDoesNotFailRegistration() {
    when(delivery.deliverNow(any())).thenThrow(new IllegalStateException("Symfony injoignable"));
    AuthResult r = registerCamille();
    assertThat(r.token()).isEqualTo("jeton.de.test");
  }

  @Test
  @DisplayName("SC-IDENT-005 — livraison synchrone non aboutie (false) → l'inscription réussit")
  void deliveryNotDelivered() {
    when(delivery.deliverNow(any())).thenReturn(false);
    assertThat(registerCamille().user().id()).isEqualTo(4);
  }

  @Test
  @DisplayName("SC-IDENT-005 — échec de la projection → exception propagée, aucune livraison tentée")
  void projectionFailure() {
    doAnswer(inv -> { throw new IllegalStateException("projection"); }).when(projection).upsert(any());
    assertThatThrownBy(this::registerCamille)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("projection");
    verifyNoInteractions(delivery);
    verify(tokens, never()).issue(any());
  }

  @Test
  @DisplayName("SC-IDENT-001 — les identifiants d'événement sont des UUID v4")
  void uuidV4() {
    registerCamille();
    ArgumentCaptor<InvitationClaim> claims = ArgumentCaptor.forClass(InvitationClaim.class);
    verify(outbox, times(2)).appendInvitationClaim(claims.capture(), any());
    for (InvitationClaim c : claims.getAllValues()) {
      UUID id = c.eventId();
      assertThat(id.version()).isEqualTo(4);
    }
  }
}
