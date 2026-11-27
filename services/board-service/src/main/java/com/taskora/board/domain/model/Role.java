package com.taskora.board.domain.model;

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
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Droit de gestion ({@code canManage} de Symfony) : propriétaire ou admin.
   *
   * @return vrai pour {@link #OWNER} et {@link #ADMIN}
   */
  public boolean canManage() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Rôle lu en base.
   *
   * @param stored colonne {@code board_member.role}
   * @return le rôle correspondant
   */
  public static Role fromStored(String stored) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
