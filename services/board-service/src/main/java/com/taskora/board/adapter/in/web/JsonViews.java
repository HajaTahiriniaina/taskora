package com.taskora.board.adapter.in.web;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.CustomFieldView;
import com.taskora.board.application.port.in.InvitationView;
import com.taskora.board.application.port.in.LabelView;
import com.taskora.board.application.port.in.ListView;
import com.taskora.board.application.port.in.MemberView;
import com.taskora.board.application.port.in.TrashedBoardView;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Corps JSON des réponses, champ pour champ comme {@code Presenter} de Symfony (board-service.yaml)
 * ; dates ATOM avec le décalage, secondes entières (B-ADR-11).
 */
final class JsonViews {
  private static final DateTimeFormatter ATOM =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private JsonViews() {}

  static @Nullable String date(@Nullable OffsetDateTime date) {
    return date == null ? null : ATOM.format(date);
  }

  static Map<String, @Nullable Object> member(MemberView m) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", m.id());
    out.put("name", m.name());
    out.put("email", m.email());
    out.put("avatarColor", m.avatarColor());
    out.put("role", m.role().value());
    out.put("joinedAt", date(m.joinedAt()));
    return out;
  }

  static Map<String, @Nullable Object> summary(BoardSummaryView b) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", b.id());
    out.put("title", b.title());
    out.put("description", b.description());
    out.put("background", b.background());
    out.put("showCardNumbers", b.showCardNumbers());
    out.put("favorite", b.favorite());
    out.put("role", b.role() == null ? null : b.role().value());
    out.put("members", b.members().stream().map(JsonViews::member).toList());
    out.put("listCount", b.listCount());
    out.put("cardCount", b.cardCount());
    out.put("createdAt", date(b.createdAt()));
    return out;
  }

  static Map<String, @Nullable Object> board(BoardView b) {
    Map<String, @Nullable Object> out = summary(b.summary());
    out.put("trelloFiles", b.trelloFiles());
    out.put("lists", b.lists().stream().map(JsonViews::list).toList());
    out.put("labels", b.labels().stream().map(JsonViews::label).toList());
    out.put("customFields", b.customFields().stream().map(JsonViews::field).toList());
    out.put("invitations", b.invitations().stream().map(JsonViews::invitation).toList());
    return out;
  }

  static Map<String, @Nullable Object> trashed(TrashedBoardView t) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", t.id());
    out.put("title", t.title());
    out.put("background", t.background());
    out.put("deletedAt", date(t.deletedAt()));
    out.put("deletedBy", t.deletedBy());
    out.put("purgeAt", date(t.purgeAt()));
    return out;
  }

  static List<Map<String, @Nullable Object>> summaries(List<BoardSummaryView> boards) {
    return boards.stream().map(JsonViews::summary).toList();
  }

  private static Map<String, @Nullable Object> list(ListView l) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", l.id());
    out.put("title", l.title());
    out.put("position", l.position());
    out.put("color", l.color());
    out.put("archivedAt", date(l.archivedAt()));
    out.put("cardCount", l.cardCount());
    return out;
  }

  private static Map<String, @Nullable Object> label(LabelView l) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", l.id());
    out.put("name", l.name());
    out.put("color", l.color());
    return out;
  }

  /** Options relues telles qu'écrites (encodage PHP, R5) et renvoyées comme JSON. */
  private static Map<String, @Nullable Object> field(CustomFieldView f) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", f.id());
    out.put("name", f.name());
    out.put("type", f.type());
    out.put("options", JSON.readTree(f.optionsJson()));
    out.put("showOnCard", f.showOnCard());
    out.put("position", f.position());
    return out;
  }

  private static Map<String, @Nullable Object> invitation(InvitationView i) {
    Map<String, @Nullable Object> out = new LinkedHashMap<>();
    out.put("id", i.id());
    out.put("email", i.email());
    out.put("createdAt", date(i.createdAt()));
    return out;
  }
}
