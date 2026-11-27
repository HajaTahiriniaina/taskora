package com.taskora.board.adapter.in.web;

import com.taskora.board.application.port.in.ClaimInvitationsUseCase;
import com.taskora.board.application.port.in.ClaimResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/invitations/claim} (board-service.internal.yaml, même contrat
 * qu'identity-service.internal.yaml) ; le secret est contrôlé par {@code InternalSecretFilter}.
 */
@RestController
public class InternalInvitationController {
  private final ClaimInvitationsUseCase claim;

  public InternalInvitationController(ClaimInvitationsUseCase claim) {
    this.claim = claim;
  }

  @PostMapping("/internal/invitations/claim")
  public Map<String, Object> claim(@RequestBody(required = false) byte @Nullable [] body) {
    ClaimResult result = claim.claim(RawJsonBody.of(body).bytes());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("claimed", result.claimed());
    out.put("boardIds", result.boardIds());
    return out;
  }
}
