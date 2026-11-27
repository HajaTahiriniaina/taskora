package com.taskora.board.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Heure murale des colonnes {@code DATETIME} (B-ADR-11) : écrites et lues en {@code Europe/Paris}
 * (sauf {@code card.due_date}, UTC, T2), secondes entières, heure ambiguë d'octobre → décalage le
 * plus ancien, heure inexistante de mars → décalée vers l'avant (comme PHP).
 */
public class LegacyClock {
  /** Rétention de la corbeille ({@code Trash::RETENTION_DAYS}). */
  static final int PURGE_DAYS = 30;

  private final Clock clock;
  private final ZoneId zone;

  /**
   * Crée l'horloge.
   *
   * @param clock horloge injectée
   * @param zone fuseau historique ({@code LEGACY_ZONE}, {@code Europe/Paris})
   */
  public LegacyClock(Clock clock, ZoneId zone) {
    this.clock = clock;
    this.zone = zone;
  }

  /**
   * Heure murale courante, tronquée à la seconde.
   *
   * @return valeur à écrire
   */
  public LocalDateTime nowWall() {
    return toWall(clock.instant());
  }

  /**
   * Instant → heure murale du fuseau historique, tronquée à la seconde.
   *
   * @param instant instant
   * @return valeur à écrire
   */
  public LocalDateTime toWall(Instant instant) {
    return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), zone);
  }

  /**
   * Heure murale lue → date exposée avec son décalage (ATOM).
   *
   * @param wall valeur lue
   * @return date avec décalage
   */
  public OffsetDateTime fromWall(LocalDateTime wall) {
    // Sans décalage préféré : heure ambiguë → décalage le plus ancien ; heure inexistante → vers
    // l'avant (comportement de PHP).
    return ZonedDateTime.of(wall.truncatedTo(ChronoUnit.SECONDS), zone).toOffsetDateTime();
  }

  /**
   * Heure murale lue → instant.
   *
   * @param wall valeur lue
   * @return instant
   */
  public Instant instantOf(LocalDateTime wall) {
    return fromWall(wall).toInstant();
  }

  /**
   * {@code purgeAt} = {@code deletedAt} + 30 jours calendaires dans le fuseau historique ({@code
   * modify('+30 days')}).
   *
   * @param deletedAtWall {@code deleted_at} lu
   * @return date de purge avec décalage
   */
  public OffsetDateTime purgeAt(LocalDateTime deletedAtWall) {
    return fromWall(deletedAtWall.plusDays(PURGE_DAYS));
  }
}
