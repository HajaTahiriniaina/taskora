package com.taskora.board.adapter.in.web;

import com.taskora.board.application.port.in.AddMemberResult;
import com.taskora.board.application.port.in.AddMemberUseCase;
import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.CancelInvitationUseCase;
import com.taskora.board.application.port.in.ChangeMemberRoleUseCase;
import com.taskora.board.application.port.in.RemoveMemberUseCase;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Membres et invitations d'un tableau (board-service.yaml, T1). */
@RestController
@RequestMapping("/api/boards/{id}")
public class MemberController {
  private final AddMemberUseCase addMember;
  private final ChangeMemberRoleUseCase changeRole;
  private final RemoveMemberUseCase removeMember;
  private final CancelInvitationUseCase cancelInvitation;

  public MemberController(
      AddMemberUseCase addMember,
      ChangeMemberRoleUseCase changeRole,
      RemoveMemberUseCase removeMember,
      CancelInvitationUseCase cancelInvitation) {
    this.addMember = addMember;
    this.changeRole = changeRole;
    this.removeMember = removeMember;
    this.cancelInvitation = cancelInvitation;
  }

  @PostMapping("/members")
  public ResponseEntity<Map<String, @Nullable Object>> add(
      @AuthenticationPrincipal AuthenticatedUser me,
      @PathVariable("id") String id,
      @RequestBody(required = false) byte @Nullable [] body) {
    BoardId board = BoardController.board(id);
    AddMemberResult result = addMember.add(me.id(), board, RawJsonBody.of(body).bytes());
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    if (result instanceof AddMemberResult.Added added) {
      out.put("status", "added");
      out.put("member", JsonViews.member(added.member()));
      return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }
    AddMemberResult.Invited invited = (AddMemberResult.Invited) result;
    out.put("status", "invited");
    out.put("message", ErrorMessages.invited(invited.email()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  @PatchMapping("/members/{userId}")
  public Map<String, @Nullable Object> changeRole(
      @AuthenticationPrincipal AuthenticatedUser me,
      @PathVariable("id") String id,
      @PathVariable("userId") String userId,
      @RequestBody(required = false) byte @Nullable [] body) {
    BoardId board = BoardController.board(id);
    UserId target = new UserId(PathIdConverter.parse(userId));
    return JsonViews.member(
        changeRole.changeRole(me.id(), board, target, RawJsonBody.of(body).bytes()));
  }

  @DeleteMapping("/members/{userId}")
  public ResponseEntity<Void> remove(
      @AuthenticationPrincipal AuthenticatedUser me,
      @PathVariable("id") String id,
      @PathVariable("userId") String userId) {
    BoardId board = BoardController.board(id);
    removeMember.remove(me.id(), board, new UserId(PathIdConverter.parse(userId)));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/invitations/{invId}")
  public ResponseEntity<Void> cancelInvitation(
      @AuthenticationPrincipal AuthenticatedUser me,
      @PathVariable("id") String id,
      @PathVariable("invId") String invId) {
    BoardId board = BoardController.board(id);
    cancelInvitation.cancel(me.id(), board, PathIdConverter.parse(invId));
    return ResponseEntity.noContent().build();
  }
}
