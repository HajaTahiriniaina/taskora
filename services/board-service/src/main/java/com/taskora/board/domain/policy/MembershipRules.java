package com.taskora.board.domain.policy;

import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.ValidationException;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import org.checkerframework.checker.nullness.qual.Nullable;

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
  public static Role roleForAddition(@Nullable String requested) {
    return Role.ADMIN.value().equals(requested) ? Role.ADMIN : Role.MEMBER;
  }

  /**
   * Rôle d'un changement : {@code admin} ou {@code member} seulement.
   *
   * @param requested valeur reçue, ou {@code null} (absente ou non chaîne)
   * @return rôle demandé
   * @throws com.taskora.board.domain.exception.ValidationException {@code Rôle invalide.}
   */
  public static Role roleForChange(@Nullable String requested) {
    if (Role.ADMIN.value().equals(requested)) {
      return Role.ADMIN;
    }
    if (Role.MEMBER.value().equals(requested)) {
      return Role.MEMBER;
    }
    throw new ValidationException(DomainMessages.INVALID_ROLE);
  }

  /**
   * Le rôle du propriétaire n'est jamais modifié.
   *
   * @param target adhésion visée
   * @throws com.taskora.board.domain.exception.ForbiddenException {@code Le rôle du propriétaire ne
   *     peut pas être modifié.}
   */
  public static void ensureRoleChangeable(Membership target) {
    if (target.role() == Role.OWNER) {
      throw new ForbiddenException(DomainMessages.OWNER_ROLE_LOCKED);
    }
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
    if (!actor.equals(target.userId()) && !actorRole.canManage()) {
      throw new ForbiddenException(DomainMessages.MANAGER_ONLY);
    }
    if (target.role() == Role.OWNER) {
      throw new ForbiddenException(DomainMessages.OWNER_CANNOT_LEAVE);
    }
  }
}
