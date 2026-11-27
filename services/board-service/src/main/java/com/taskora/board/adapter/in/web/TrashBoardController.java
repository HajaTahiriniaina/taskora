package com.taskora.board.adapter.in.web;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.ListTrashedBoardsQuery;
import com.taskora.board.application.port.in.RestoreBoardUseCase;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Corbeille des tableaux : lecture et restauration (T1 ; purge servie par Symfony jusqu'à T5). */
@RestController
@RequestMapping("/api/trash/boards")
public class TrashBoardController {
  private final ListTrashedBoardsQuery listTrashed;
  private final RestoreBoardUseCase restore;

  public TrashBoardController(ListTrashedBoardsQuery listTrashed, RestoreBoardUseCase restore) {
    this.listTrashed = listTrashed;
    this.restore = restore;
  }

  @GetMapping
  public List<Map<String, @Nullable Object>> list(@AuthenticationPrincipal AuthenticatedUser me) {
    return listTrashed.listTrashedBoards(me.id()).stream().map(JsonViews::trashed).toList();
  }

  @PostMapping("/{id}/restore")
  public Map<String, Object> restore(
      @AuthenticationPrincipal AuthenticatedUser me, @PathVariable("id") String id) {
    return Map.of("id", restore.restore(me.id(), BoardController.board(id)).value());
  }
}
