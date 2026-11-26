package com.taskora.identity.adapter.out.security;

import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Hachage compatible Symfony NativePasswordHasher (A2) : bcrypt $2y$, pré-hachage base64(sha512)
 * au-delà de 72 octets ou avec NUL, vérification bcrypt ($2y$/$2a$/$2b$) et argon2 (i, id), toute
 * autre forme → false sans exception.
 */
public class SymfonyCompatiblePasswordHasher implements PasswordHasherPort {
  private static final int BCRYPT_MAX_BYTES = 72;
  private static final int BCRYPT_HASH_LENGTH = 60;
  private static final String BCRYPT_PREFIX = "$2y";

  private final int bcryptCost;
  private final SecureRandom random = new SecureRandom();
  private final Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 65536, 4);

  /** Hash factice $2y$ au coût configuré, prêt dès le démarrage (A2, SC-IDENT-008). */
  private final String dummyHash;

  public SymfonyCompatiblePasswordHasher(int bcryptCost) {
    if (bcryptCost < 4 || bcryptCost > 31) {
      throw new IllegalArgumentException("Coût bcrypt hors de [4, 31] : " + bcryptCost);
    }
    this.bcryptCost = bcryptCost;
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    this.dummyHash = bcrypt(HexFormat.of().formatHex(secret), bcryptCost, random);
  }

  @Override
  public HashedPassword hash(PlainPassword password) {
    if (password.utf8Length() > PlainPassword.MAX_BYTES) {
      // Garde défensive : le domaine refuse avant (E15).
      throw new PasswordTooLongException();
    }
    return new HashedPassword(bcrypt(password.value(), bcryptCost, random));
  }

  @Override
  public boolean matches(PlainPassword password, HashedPassword hash) {
    if (password.isEmpty() || password.utf8Length() > PlainPassword.MAX_BYTES) {
      return false;
    }
    String encoded = hash.value();
    try {
      if (encoded.startsWith("$argon2id$") || encoded.startsWith("$argon2i$")) {
        return argon2.matches(password.value(), encoded);
      }
      if (isBcrypt(encoded)) {
        return BCrypt.checkpw(bcryptInput(password.value()), encoded);
      }
      return false;
    } catch (RuntimeException e) {
      // Hash corrompu : jamais d'exception (jamais de 500), A2.
      return false;
    }
  }

  @Override
  public void verifyAgainstDummy(PlainPassword password) {
    try {
      BCrypt.checkpw(bcryptInput(password.value()), dummyHash);
    } catch (RuntimeException e) {
      // Résultat ignoré : seul le temps de calcul compte.
    }
  }

  private static String bcrypt(String plain, int cost, SecureRandom random) {
    return BCrypt.hashpw(bcryptInput(plain), BCrypt.gensalt(BCRYPT_PREFIX, cost, random));
  }

  private static boolean isBcrypt(String encoded) {
    return encoded.length() == BCRYPT_HASH_LENGTH
        && (encoded.startsWith("$2y$") || encoded.startsWith("$2a$") || encoded.startsWith("$2b$"));
  }

  /**
   * Entrée effective de bcrypt : au-delà de 72 octets ou avec NUL, base64(sha512) comme Symfony ;
   * puis les 72 premiers octets, que PHP lit seuls (troncature sûre : ASCII après pré-hachage).
   */
  static byte[] bcryptInput(String plain) {
    byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > BCRYPT_MAX_BYTES || plain.indexOf('\0') >= 0) {
      bytes = Base64.getEncoder().encodeToString(sha512(bytes)).getBytes(StandardCharsets.US_ASCII);
    }
    return bytes.length > BCRYPT_MAX_BYTES ? Arrays.copyOf(bytes, BCRYPT_MAX_BYTES) : bytes;
  }

  private static byte[] sha512(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-512").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-512 indisponible", e);
    }
  }
}
