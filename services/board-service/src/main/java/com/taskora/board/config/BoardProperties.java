package com.taskora.board.config;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriétés {@code board.*} (B-ADR-12), alimentées par les variables d'environnement.
 *
 * <p>Correspondances :
 *
 * <ul>
 *   <li>{@code board.jwt.public-key-path} ← {@code JWT_PUBLIC_KEY_PATH} (jamais de clé privée ni de
 *       passphrase) ;
 *   <li>{@code board.legacy.schema} ← {@code LEGACY_SCHEMA} ({@code [a-z_]+}), {@code
 *       board.legacy.zone} ← {@code LEGACY_ZONE} ;
 *   <li>{@code board.internal.identity-to-board-secret} ← {@code INTERNAL_SECRET_IDENTITY_TO_BOARD}
 *       et {@code board.internal.board-to-symfony-secret} ← {@code
 *       INTERNAL_SECRET_BOARD_TO_SYMFONY} (U12 : non vides et différents, sinon refus de démarrer)
 *       ;
 *   <li>{@code board.symfony.internal-url} ← {@code SYMFONY_INTERNAL_URL} ;
 *   <li>{@code board.outbox.relay.enabled}, {@code board.routing.marker} ← {@code
 *       BOARD_ROUTING_MARKER}, {@code board.purge.enabled} ← {@code BOARD_PURGE_ENABLED}.
 * </ul>
 *
 * <p>Validation au démarrage (A18, U12) : secrets internes non vides et différents ; schéma
 * historique {@code [a-z_]+}. Les messages nomment la variable, jamais sa valeur.
 *
 * @param jwt clé publique
 * @param legacy schéma et fuseau historiques
 * @param internal secrets internes par appelant
 * @param symfony URL interne de Symfony
 * @param outbox relais
 * @param routing marqueur de routage
 * @param purge purge planifiée (T5)
 */
@ConfigurationProperties("board")
public record BoardProperties(
    Jwt jwt,
    Legacy legacy,
    Internal internal,
    Symfony symfony,
    Outbox outbox,
    Routing routing,
    Purge purge) {

  static final String IDENTITY_TO_BOARD = "INTERNAL_SECRET_IDENTITY_TO_BOARD";
  static final String BOARD_TO_SYMFONY = "INTERNAL_SECRET_BOARD_TO_SYMFONY";

  /** Refuse de démarrer sans secrets internes ou avec un schéma historique non conforme. */
  public BoardProperties {
    if (internal == null) {
      throw new IllegalStateException(
          IDENTITY_TO_BOARD + " et " + BOARD_TO_SYMFONY + " doivent être définis et non vides.");
    }
    if (legacy != null && !legacy.schema().matches("[a-z_]+")) {
      throw new IllegalStateException("LEGACY_SCHEMA doit respecter [a-z_]+.");
    }
  }

  /**
   * Clé publique des JWT.
   *
   * @param publicKeyPath chemin de {@code public.pem}
   */
  public record Jwt(String publicKeyPath) {}

  /**
   * Schéma et fuseau historiques.
   *
   * @param schema schéma ({@code taskora})
   * @param zone fuseau ({@code Europe/Paris})
   */
  public record Legacy(String schema, String zone) {}

  /**
   * Secrets internes par appelant (U12) ; jamais dans {@link #toString()}.
   *
   * @param identityToBoardSecret secret accepté sur {@code /internal/**}
   * @param boardToSymfonySecret secret envoyé à Symfony
   */
  public record Internal(String identityToBoardSecret, String boardToSymfonySecret) {

    /** Secrets non vides et différents (U12), sinon refus de démarrer sans citer de valeur. */
    public Internal {
      if (blank(identityToBoardSecret)) {
        throw new IllegalStateException(IDENTITY_TO_BOARD + " doit être défini et non vide.");
      }
      if (blank(boardToSymfonySecret)) {
        throw new IllegalStateException(BOARD_TO_SYMFONY + " doit être défini et non vide.");
      }
      if (identityToBoardSecret.equals(boardToSymfonySecret)) {
        throw new IllegalStateException(
            IDENTITY_TO_BOARD + " et " + BOARD_TO_SYMFONY + " doivent être différents (U12).");
      }
    }

    private static boolean blank(@Nullable String secret) {
      return secret == null || secret.isBlank();
    }

    @Override
    public String toString() {
      return "Internal[secrets masqués]";
    }
  }

  /**
   * Symfony sur le réseau interne.
   *
   * @param internalUrl URL de base ({@code http://api:80})
   */
  public record Symfony(String internalUrl) {}

  /**
   * Relais de l'outbox.
   *
   * @param relay relais planifié
   */
  public record Outbox(Relay relay) {
    /**
     * Relais planifié.
     *
     * @param enabled actif
     */
    public record Relay(boolean enabled) {}
  }

  /**
   * Marqueur {@code X-Taskora-Backend: board} (SC-BOARD-022).
   *
   * @param marker actif
   */
  public record Routing(boolean marker) {}

  /**
   * Purge planifiée (T5).
   *
   * @param enabled active
   */
  public record Purge(boolean enabled) {}
}
