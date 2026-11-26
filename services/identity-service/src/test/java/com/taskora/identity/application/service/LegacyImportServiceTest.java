package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import com.taskora.identity.application.port.out.LegacyUserSourcePort;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegacyImportServiceTest {
  private LegacyUserSourcePort source;
  private FakeUnitOfWork uow;
  private LegacyImportService service;

  @BeforeEach
  void setUp() {
    source = mock(LegacyUserSourcePort.class);
    uow = new FakeUnitOfWork();
    service = new LegacyImportService(source, uow);
  }

  @Test
  @DisplayName("SC-IDENT-023 — doublons d'e-mail normalisé > 0 → échec AVANT toute copie")
  void duplicatesAbort() {
    when(source.countNormalizedEmailDuplicates()).thenReturn(1);
    assertThatThrownBy(service::importAll)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(UnsupportedOperationException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("@"));
    verify(source, never()).copyAllIntoIdentity();
  }

  @Test
  @DisplayName("SC-IDENT-023 — aucun doublon → copie dans une transaction, rapport renvoyé")
  void copies() {
    ImportReport report = new ImportReport(9, 9, 0, 0, 0);
    when(source.countNormalizedEmailDuplicates()).thenReturn(0);
    when(source.copyAllIntoIdentity()).thenReturn(report);
    assertThat(service.importAll()).isEqualTo(report);
    assertThat(uow.transactions).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-023 — verify : métriques identiques → consistent=true")
  void verifyConsistent() {
    Map<String, String> m = Map.of("count", "9", "maxId", "11", "crc", "123");
    when(source.sourceMetrics()).thenReturn(m);
    when(source.targetMetrics()).thenReturn(m);
    when(source.countNormalizedEmailDuplicates()).thenReturn(0);
    VerificationReport r = service.verify();
    assertThat(r.consistent()).isTrue();
    assertThat(r.failedChecks()).isEmpty();
  }

  @Test
  @DisplayName("SC-IDENT-023 — verify : une métrique diffère → consistent=false, contrôle nommé, sans donnée personnelle")
  void verifyInconsistent() {
    when(source.sourceMetrics()).thenReturn(Map.of("count", "9", "crc", "123"));
    when(source.targetMetrics()).thenReturn(Map.of("count", "8", "crc", "123"));
    when(source.countNormalizedEmailDuplicates()).thenReturn(0);
    VerificationReport r = service.verify();
    assertThat(r.consistent()).isFalse();
    assertThat(r.failedChecks()).contains("count");
    assertThat(r.metrics().values()).noneMatch(v -> v.contains("@"));
  }
}
