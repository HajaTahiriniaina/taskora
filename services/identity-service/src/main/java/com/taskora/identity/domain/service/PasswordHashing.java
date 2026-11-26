package com.taskora.identity.domain.service;

import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;

/** Hachage des mots de passe (interface de domaine). */
public interface PasswordHashing {
  HashedPassword hash(PlainPassword password);

  boolean matches(PlainPassword password, HashedPassword hash);
}
