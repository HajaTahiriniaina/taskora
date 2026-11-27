package com.taskora.board.domain.event;

import org.checkerframework.checker.nullness.qual.Nullable;

/** Textes exacts des notifications de T1 (boards-members.md, {@code BoardController} Symfony). */
public final class NotificationTexts {
  private NotificationTexts() {}

  /**
   * {@code <acteur> vous a ajouté au tableau « <titre> »} ; acteur absent → {@code Quelqu'un}.
   *
   * @param actorName nom de l'acteur, ou {@code null}
   * @param boardTitle titre du tableau
   * @return message
   */
  public static String boardInvite(@Nullable String actorName, String boardTitle) {
    return (actorName == null ? "Quelqu'un" : actorName)
        + " vous a ajouté au tableau « "
        + boardTitle
        + " »";
  }

  /**
   * {@code <nouveau membre> a rejoint le tableau « <titre> »}.
   *
   * @param memberName nom du nouveau membre
   * @param boardTitle titre du tableau
   * @return message
   */
  public static String memberJoined(String memberName, String boardTitle) {
    return memberName + " a rejoint le tableau « " + boardTitle + " »";
  }

  /**
   * {@code <acteur> vous a retiré du tableau « <titre> »}.
   *
   * @param actorName nom de l'acteur
   * @param boardTitle titre du tableau
   * @return message
   */
  public static String memberRemoved(String actorName, String boardTitle) {
    return actorName + " vous a retiré du tableau « " + boardTitle + " »";
  }
}
