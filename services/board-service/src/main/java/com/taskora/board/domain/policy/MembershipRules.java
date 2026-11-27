package com.taskora.board.domain.policy;

import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;

/** Règles d'adhésion : propriétaire unique et non transférable, rôles (SC-BOARD-014, 032). */
public final class MembershipRules {
  private MembershipRules() {}

  /**
   * Rôle d'un ajout direct : {@code admin} ou {@code member} ; toute autre valeur (dont {@code
   * owner}, non chaîne : {@code null}) → {@code member}.
   *
   * @param requested valeur reçue, ou {@code null}
   * @return rôle attribué
   */
  public static Role roleForAddition(String requested) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Rôle d'un changement : {@code admin} ou {@code member} seulement.
   *
   * @param requested valeur reçue, ou {@code null} (absente ou non chaîne)
   * @return rôle demandé
   * @throws com.taskora.board.domain.exception.ValidationException {@code Rôle invalide.}
   */
  public static Role roleForChange(String requested) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Le rôle du propriétaire n'est jamais modifié.
   *
   * @param target adhésion visée
   * @throws com.taskora.board.domain.exception.ForbiddenException {@code Le rôle du propriétaire ne
   *     peut pas être modifié.}
   */
  public static void ensureRoleChangeable(Membership target) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Retrait ou départ : soi-même pour tout membre, autrui pour un gestionnaire ; jamais le
   * propriétaire.
   *
   * @param actor appelant
   * @param actorRole rôle de l'appelant
   * @param target adhésion visée
   * @throws com.taskora.board.domain.exception.ForbiddenException gestionnaire requis, ou {@code Le
   *     propriétaire ne peut pas quitter son tableau.}
   */
  public static void ensureRemovable(UserId actor, Role actorRole, Membership target) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
