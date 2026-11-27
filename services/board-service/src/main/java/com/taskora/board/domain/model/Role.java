package com.taskora.board.domain.model;

import java.util.Locale;

/** Rôle d'un membre ({@code board_member.role}). */
public enum Role {
  /** Propriétaire unique, non transférable. */
  OWNER,
  /** Gestionnaire. */
  ADMIN,
  /** Membre simple. */
  MEMBER;

  /**
   * Valeur stockée et exposée ({@code owner}, {@code admin}, {@code member}).
   *
   * @return valeur en minuscules
   */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Droit de gestion ({@code canManage} de Symfony) : propriétaire ou admin.
   *
   * @return vrai pour {@link #OWNER} et {@link #ADMIN}
   */
  public boolean canManage() {
    return this != MEMBER;
  }

  /**
   * Rôle lu en base.
   *
   * @param stored colonne {@code board_member.role}
   * @return le rôle correspondant
   */
  public static Role fromStored(String stored) {
    return valueOf(stored.toUpperCase(Locale.ROOT));
  }
}
