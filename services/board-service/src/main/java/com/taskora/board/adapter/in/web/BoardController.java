package com.taskora.board.adapter.in.web;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.CreateBoardUseCase;
import com.taskora.board.application.port.in.GetBoardQuery;
import com.taskora.board.application.port.in.ListMyBoardsQuery;
import com.taskora.board.application.port.in.ToggleFavoriteUseCase;
import com.taskora.board.application.port.in.TrashBoardUseCase;
import com.taskora.board.application.port.in.UpdateBoardUseCase;
import com.taskora.board.domain.model.BoardId;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tableaux et favori (board-service.yaml, T1). L'adapter ne lit que les octets du corps ({@link
 * RawJsonBody}) : accès puis analyse sont faits par le cas d'utilisation (B-ADR-3 point 3).
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {
  private final ListMyBoardsQuery listMyBoards;
  private final GetBoardQuery getBoard;
  private final CreateBoardUseCase createBoard;
  private final UpdateBoardUseCase updateBoard;
  private final TrashBoardUseCase trashBoard;
  private final ToggleFavoriteUseCase toggleFavorite;

  public BoardController(
      ListMyBoardsQuery listMyBoards,
      GetBoardQuery getBoard,
      CreateBoardUseCase createBoard,
      UpdateBoardUseCase updateBoard,
      TrashBoardUseCase trashBoard,
      ToggleFavoriteUseCase toggleFavorite) {
    this.listMyBoards = listMyBoards;
    this.getBoard = getBoard;
    this.createBoard = createBoard;
    this.updateBoard = updateBoard;
    this.trashBoard = trashBoard;
    this.toggleFavorite = toggleFavorite;
  }

  @GetMapping
  public List<Map<String, @Nullable Object>> list(@AuthenticationPrincipal AuthenticatedUser me) {
    return JsonViews.summaries(listMyBoards.listMyBoards(me.id()));
  }

  @PostMapping
  public ResponseEntity<Map<String, @Nullable Object>> create(
      @AuthenticationPrincipal AuthenticatedUser me,
      @RequestBody(required = false) byte @Nullable [] body) {
    RawJsonBody raw = RawJsonBody.of(body);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(JsonViews.board(createBoard.create(me.id(), raw.bytes())));
  }

  @GetMapping("/{id}")
  public Map<String, @Nullable Object> get(
      @AuthenticationPrincipal AuthenticatedUser me, @PathVariable("id") String id) {
    return JsonViews.board(getBoard.getBoard(me.id(), board(id)));
  }

  @PatchMapping("/{id}")
  public Map<String, @Nullable Object> update(
      @AuthenticationPrincipal AuthenticatedUser me,
      @PathVariable("id") String id,
      @RequestBody(required = false) byte @Nullable [] body) {
    BoardId board = board(id);
    return JsonViews.summary(updateBoard.update(me.id(), board, RawJsonBody.of(body).bytes()));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> trash(
      @AuthenticationPrincipal AuthenticatedUser me, @PathVariable("id") String id) {
    trashBoard.trash(me.id(), board(id));
    return ResponseEntity.noContent().build();
  }

  /** Corps ignoré (le front n'en envoie pas ; ni analysé ni contrôlé, comme aujourd'hui). */
  @PostMapping("/{id}/favorite")
  public Map<String, Object> favorite(
      @AuthenticationPrincipal AuthenticatedUser me, @PathVariable("id") String id) {
    return Map.of("favorite", toggleFavorite.toggle(me.id(), board(id)));
  }

  static BoardId board(String id) {
    return new BoardId(PathIdConverter.parse(id));
  }
}
