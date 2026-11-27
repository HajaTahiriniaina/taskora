package com.taskora.board.support;

import java.time.Instant;

/**
 * Identifiants du jeu de référence {@code fixtures/board-reference.sql} (données synthétiques).
 *
 * <p>Tableau 101 « Lancement produit » (ocean) : Alice owner, Bruno admin, Carla member (favori),
 * Marc member ; listes ouvertes 201 (pos 0, 3 cartes), 202 (pos 1, 2 cartes), 205 (pos 1, 0 carte)
 * ; 203 archivée, 204 dans la corbeille ; invitations 301 et 302 ; 2 fichiers Trello non terminés.
 * Tableau 102 « Import Trello » : Alice owner (favori), Carla member. Tableaux 103 « Ancien projet
 * » et 105 « Brouillon » d'Alice dans la corbeille (même instant). Tableau 104 d'Ève.
 */
public final class Fixture {
  /** Instant courant des tests : 27/11/2026 11:00 à Paris (+01:00). */
  public static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

  public static final int ALICE = 1;
  public static final int BRUNO = 2;
  public static final int CARLA = 3;
  public static final int MARC = 4;
  public static final int NADIA = 5;
  public static final int DORA = 6;
  public static final int EVE = 7;
  public static final int GASTON = 8;
  public static final int INES = 9;

  public static final String ALICE_EMAIL = "alice@exemple.test";
  public static final String BRUNO_EMAIL = "bruno@exemple.test";
  public static final String CARLA_EMAIL = "carla@exemple.test";
  public static final String MARC_EMAIL = "marc@exemple.test";
  public static final String NADIA_EMAIL = "nadia@exemple.test";
  public static final String DORA_EMAIL = "dora@exemple.test";
  public static final String EVE_EMAIL = "eve@exemple.test";
  public static final String GASTON_STORED_EMAIL = "Gaston.Vieux@Exemple.TEST";
  public static final String INES_EMAIL = "invite@exemple.test";

  public static final int LAUNCH = 101;
  public static final String LAUNCH_TITLE = "Lancement produit";
  public static final int IMPORTED = 102;
  public static final int OLD_TRASHED = 103;
  public static final String OLD_TRASHED_TITLE = "Ancien projet";
  public static final int EVE_BOARD = 104;
  public static final int DRAFT_TRASHED = 105;
  public static final int MISSING_BOARD = 99_999;

  public static final int INVITATION_INES_LAUNCH = 301;
  public static final int INVITATION_OTHER_LAUNCH = 302;
  public static final int INVITATION_INES_OLD = 303;
  public static final int INVITATION_EVE_BOARD = 304;
  public static final int INVITATION_ORPHAN = 305;

  public static final int MISSING_USER = 9_999;

  private Fixture() {}
}
