package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.UserView;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.event.DomainEvent;
import com.taskora.identity.domain.event.UserProfileUpdated;
import com.taskora.identity.domain.exception.InvalidCurrentPasswordException;
import com.taskora.identity.domain.exception.ValidationException;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.ProfileUpdate;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProfileServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-26T11:30:00Z");
  private UserRepositoryPort users;
  private PasswordHasherPort hasher;
  private LegacyUserProjectionPort projection;
  private OutboxPort outbox;
  private FakeUnitOfWork uow;
  private ProfileService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepositoryPort.class);
    hasher = mock(PasswordHasherPort.class);
    projection = mock(LegacyUserProjectionPort.class);
    outbox = mock(OutboxPort.class);
    uow = new FakeUnitOfWork();
    service = new ProfileService(users, hasher, projection, outbox, uow, Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.findById(new UserId(4))).thenAnswer(inv -> Optional.of(Users.camille()));
    when(hasher.hash(any())).thenReturn(new HashedPassword("$2y$04$nouveauhash"));
    doAnswer(inv -> { uow.assertActive("users.update"); return null; }).when(users).update(any());
    doAnswer(inv -> { uow.assertActive("projection.upsert"); return null; }).when(projection).upsert(any());
    doAnswer(inv -> { uow.assertActive("outbox.appendEvent"); return null; }).when(outbox).appendEvent(any());
  }

  private static ProfileUpdate update(String name, String color, String current, String newPw) {
    return new ProfileUpdate(
        Optional.ofNullable(name), Optional.ofNullable(color), Optional.ofNullable(current), Optional.ofNullable(newPw));
  }

  @Test
  @DisplayName("SC-IDENT-014 — lecture du profil de SON compte")
  void get() {
    UserView v = service.get(new UserId(4));
    assertThat(v).isEqualTo(new UserView(4, "Camille", "camille@exemple.fr", "#14b8a6"));
  }

  @Test
  @DisplayName("SC-IDENT-015 — renommage : une unité de travail (update + projection + UserProfileUpdated)")
  void rename() {
    UserView v = service.update(new UserId(4), update("  Camille M. ", null, null, null));
    assertThat(v.name()).isEqualTo("Camille M.");
    assertThat(uow.transactions).isEqualTo(1);
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).update(saved.capture());
    assertThat(saved.getValue().name().value()).isEqualTo("Camille M.");
    verify(projection).upsert(saved.getValue());
    ArgumentCaptor<DomainEvent> e = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox).appendEvent(e.capture());
    UserProfileUpdated ev = (UserProfileUpdated) e.getValue();
    assertThat(ev.userId()).isEqualTo(new UserId(4));
    assertThat(ev.name()).isEqualTo("Camille M.");
    assertThat(ev.avatarColor()).isEqualTo("#14b8a6");
    assertThat(ev.changed()).containsExactly("name");
    assertThat(ev.occurredAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("SC-IDENT-016 — couleur : UserProfileUpdated avec changed = [avatarColor]")
  void color() {
    service.update(new UserId(4), update(null, "#0EA5E9", null, null));
    ArgumentCaptor<DomainEvent> e = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox).appendEvent(e.capture());
    assertThat(((UserProfileUpdated) e.getValue()).changed()).containsExactly("avatarColor");
    assertThat(((UserProfileUpdated) e.getValue()).avatarColor()).isEqualTo("#0EA5E9");
  }

  @Test
  @DisplayName("SC-IDENT-017 — mot de passe seul : persisté et projeté, AUCUN événement (pas de donnée sensible)")
  void passwordOnlyNoEvent() {
    when(hasher.matches(any(), any())).thenReturn(true);
    service.update(new UserId(4), update(null, null, "ancienmdp1", "nouveaumdp1"));
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).update(saved.capture());
    assertThat(saved.getValue().password().value()).isEqualTo("$2y$04$nouveauhash");
    verify(projection).upsert(any());
    verify(outbox, never()).appendEvent(any());
  }

  @Test
  @DisplayName("SC-IDENT-015 — valeurs identiques : aucun événement")
  void noRealChangeNoEvent() {
    service.update(new UserId(4), update("Camille", "#14b8a6", null, null));
    verify(outbox, never()).appendEvent(any());
  }

  @Test
  @DisplayName("SC-IDENT-018/019 — mot de passe actuel faux : aucune écriture")
  void errorWritesNothing() {
    when(hasher.matches(any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.update(new UserId(4), update("Camille M.", null, "faux", "nouveaumdp1")))
        .isInstanceOf(InvalidCurrentPasswordException.class);
    verify(users, never()).update(any());
    verifyNoInteractions(projection, outbox);
  }

  @Test
  @DisplayName("SC-IDENT-031 E18 — couleur invalide : 400, aucune écriture")
  void invalidColorWritesNothing() {
    assertThatThrownBy(() -> service.update(new UserId(4), update("Camille M.", "red", null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Couleur d'avatar invalide.");
    verify(users, never()).update(any());
    verifyNoInteractions(projection, outbox);
  }

  @Test
  @DisplayName("SC-IDENT-020 — l'e-mail persisté reste celui du compte")
  void emailUnchanged() {
    service.update(new UserId(4), update("Camille M.", null, null, null));
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).update(saved.capture());
    assertThat(saved.getValue().email().value()).isEqualTo("camille@exemple.fr");
  }
}
