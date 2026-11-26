package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import com.taskora.identity.application.port.out.LegacyUserSourcePort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;

/** Reprise idempotente (A14, identity-migration.md §3-4). */
public class LegacyImportService implements ImportLegacyUsersUseCase {

  public LegacyImportService(LegacyUserSourcePort source, UnitOfWorkPort unitOfWork) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public ImportReport importAll() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public VerificationReport verify() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
