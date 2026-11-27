package com.taskora.board.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SC-BOARD-028 (B-ADR-11) — heure murale de Paris comme PHP, horloge injectée, indépendante du
 * fuseau de la JVM ; heure ambiguë d'octobre et heure inexistante de mars ; purgeAt = +30 jours
 * calendaires.
 */
class LegacyClockTest {
  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
  private final TimeZone jvmZone = TimeZone.getDefault();

  @AfterEach
  void restoreJvmZone() {
    TimeZone.setDefault(jvmZone);
  }

  private static LegacyClock at(String instant) {
    return new LegacyClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), PARIS);
  }

  @ParameterizedTest(name = "JVM en {0}")
  @ValueSource(strings = {"UTC", "Pacific/Auckland", "America/Los_Angeles", "Europe/Paris"})
  @DisplayName(
      "SC-BOARD-028 — maintenant en heure murale de Paris, secondes entières, quel que soit le fuseau JVM")
  void nowWall(String jvm) {
    TimeZone.setDefault(TimeZone.getTimeZone(jvm));
    assertThat(at("2026-11-27T10:00:00.789Z").nowWall())
        .isEqualTo(LocalDateTime.parse("2026-11-27T11:00:00"));
    assertThat(at("2026-07-01T10:00:59.999Z").nowWall())
        .isEqualTo(LocalDateTime.parse("2026-07-01T12:00:59"));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — écriture puis relecture : même instant, décalage d'hiver +01:00 et d'été +02:00")
  void roundTrip() {
    LegacyClock clock = at("2026-11-27T10:00:00Z");
    Instant winter = Instant.parse("2026-12-01T16:00:00Z");
    assertThat(clock.toWall(winter)).isEqualTo(LocalDateTime.parse("2026-12-01T17:00:00"));
    assertThat(clock.fromWall(clock.toWall(winter)))
        .isEqualTo(OffsetDateTime.parse("2026-12-01T17:00:00+01:00"));
    Instant summer = Instant.parse("2026-07-01T15:00:00Z");
    assertThat(clock.fromWall(clock.toWall(summer)))
        .isEqualTo(OffsetDateTime.parse("2026-07-01T17:00:00+02:00"));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — heure ambiguë d'octobre : décalage le plus ancien (+02:00), comme PHP")
  void ambiguousOctoberHour() {
    LegacyClock clock = at("2026-11-27T10:00:00Z");
    OffsetDateTime read = clock.fromWall(LocalDateTime.parse("2026-10-25T02:30:00"));
    assertThat(read).isEqualTo(OffsetDateTime.parse("2026-10-25T02:30:00+02:00"));
    assertThat(read.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — l'ordre de deux écritures de part et d'autre du changement d'heure est conservé")
  void orderAcrossOctoberChange() {
    LegacyClock clock = at("2026-11-27T10:00:00Z");
    LocalDateTime before = clock.toWall(Instant.parse("2026-10-25T00:59:00Z"));
    LocalDateTime after = clock.toWall(Instant.parse("2026-10-25T01:01:00Z"));
    assertThat(before).isEqualTo(LocalDateTime.parse("2026-10-25T02:59:00"));
    assertThat(after).isEqualTo(LocalDateTime.parse("2026-10-25T02:01:00"));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — heure inexistante de mars : décalée vers l'avant (03:30+02:00), comme PHP")
  void missingMarchHour() {
    LegacyClock clock = at("2026-11-27T10:00:00Z");
    assertThat(clock.fromWall(LocalDateTime.parse("2026-03-29T02:30:00")))
        .isEqualTo(OffsetDateTime.parse("2026-03-29T03:30:00+02:00"));
  }

  @Test
  @DisplayName(
      "SC-BOARD-028 — purgeAt = deletedAt + 30 jours calendaires à Paris (modify('+30 days'))")
  void purgeAt() {
    LegacyClock clock = at("2026-11-27T10:00:00Z");
    assertThat(clock.purgeAt(LocalDateTime.parse("2026-10-10T12:00:00")))
        .as("à cheval sur le passage à l'heure d'hiver")
        .isEqualTo(OffsetDateTime.parse("2026-11-09T12:00:00+01:00"));
    assertThat(clock.purgeAt(LocalDateTime.parse("2026-03-10T12:00:00")))
        .as("à cheval sur le passage à l'heure d'été")
        .isEqualTo(OffsetDateTime.parse("2026-04-09T12:00:00+02:00"));
    assertThat(clock.purgeAt(LocalDateTime.parse("2026-11-25T09:30:00")))
        .isEqualTo(OffsetDateTime.parse("2026-12-25T09:30:00+01:00"));
  }
}
