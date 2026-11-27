package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.Background;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.BoardTitle;
import com.taskora.board.domain.model.DefaultBoardContent.DefaultLabel;
import com.taskora.board.domain.model.DefaultBoardContent.DefaultList;
import com.taskora.board.domain.model.NewBoard;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Écritures sur {@code taskora.board}, {@code board_list} et {@code label} (DML seulement). */
public interface BoardRepositoryPort {

  /**
   * Insère un tableau.
   *
   * @param board tableau à créer
   * @return identifiant attribué
   */
  BoardId insert(NewBoard board);

  /**
   * Insère le contenu par défaut.
   *
   * @param board tableau
   * @param lists listes
   * @param labels étiquettes
   */
  void insertDefaultContent(BoardId board, List<DefaultList> lists, List<DefaultLabel> labels);

  /**
   * Met à jour les seules colonnes présentes (B-ADR-1 point 1).
   *
   * @param board tableau
   * @param changes modifications
   */
  void update(BoardId board, BoardChanges changes);

  /**
   * Met le tableau dans la corbeille.
   *
   * @param board tableau
   * @param by auteur
   * @param at instant
   */
  void markTrashed(BoardId board, UserId by, Instant at);

  /**
   * Restaure le tableau.
   *
   * @param board tableau
   */
  void restore(BoardId board);

  /**
   * Titre actuel d'un tableau, pour composer les notifications (relu à chaque action, R9).
   *
   * @param board tableau existant
   * @return titre
   */
  String titleOf(BoardId board);

  /**
   * Modifications d'un tableau ; un champ {@code null} est inchangé.
   *
   * @param title nouveau titre, ou {@code null}
   * @param descriptionChanged la description est présente dans le corps
   * @param description nouvelle description (peut être {@code null})
   * @param background nouveau fond, ou {@code null}
   * @param showCardNumbers nouvel affichage des numéros, ou {@code null}
   */
  record BoardChanges(
      @Nullable BoardTitle title,
      boolean descriptionChanged,
      @Nullable String description,
      @Nullable Background background,
      @Nullable Boolean showCardNumbers) {}
}
