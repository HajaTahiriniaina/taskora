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
 *
 * <p>B1 : le calcul bcrypt (hachage, vérification et hash factice) passe par un {@link
 * BcryptEngine} : natif libxcrypt par défaut, Java pur sur configuration explicite. Le sel vient de
 * {@link BCrypt#gensalt(String, int, java.security.SecureRandom)} ; la vérification recalcule le
 * hash et le compare au hash stocké à temps constant ({@link MessageDigest#isEqual}).
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

  /** Moteur bcrypt (B1) : natif par défaut, Java sur configuration explicite. */
  private final BcryptEngine engine;

  /**
   * Hacheur avec le moteur bcrypt par défaut : natif libxcrypt (B1).
   *
   * @throws IllegalStateException si libcrypt.so.1 est introuvable
   */
  public SymfonyCompatiblePasswordHasher(int bcryptCost) {
    this(bcryptCost, new LibxcryptBcryptEngine());
  }

  /**
   * Hacheur avec un moteur bcrypt explicite (B1).
   *
   * @param bcryptCost coût bcrypt des nouveaux hashes, dans [4, 31]
   * @param engine moteur bcrypt utilisé pour le hachage, la vérification et le hash factice
   */
  public SymfonyCompatiblePasswordHasher(int bcryptCost, BcryptEngine engine) {
    if (bcryptCost < 4 || bcryptCost > 31) {
      throw new IllegalArgumentException("Coût bcrypt hors de [4, 31] : " + bcryptCost);
    }
    this.bcryptCost = bcryptCost;
    this.engine = engine;
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    this.dummyHash = bcrypt(engine, HexFormat.of().formatHex(secret), bcryptCost, random);
    Arrays.fill(secret, (byte) 0);
  }

  /**
   * Hacheur sur le moteur natif libxcrypt (identity.password.bcrypt-engine=native, défaut).
   *
   * @throws IllegalStateException si libcrypt.so.1 est introuvable (refus de démarrer)
   */
  public static SymfonyCompatiblePasswordHasher withNativeEngine(int bcryptCost) {
    return new SymfonyCompatiblePasswordHasher(bcryptCost, new LibxcryptBcryptEngine());
  }

  /** Hacheur sur le moteur Java pur (identity.password.bcrypt-engine=java, développement). */
  public static SymfonyCompatiblePasswordHasher withJavaEngine(int bcryptCost) {
    return new SymfonyCompatiblePasswordHasher(bcryptCost, new JavaBcryptEngine());
  }

  /** Moteur bcrypt effectif (contrôle de configuration, B1). */
  BcryptEngine engine() {
    return engine;
  }

  @Override
  public HashedPassword hash(PlainPassword password) {
    if (password.utf8Length() > PlainPassword.MAX_BYTES) {
      // Garde défensive : le domaine refuse avant (E15).
      throw new PasswordTooLongException();
    }
    return new HashedPassword(bcrypt(engine, password.value(), bcryptCost, random));
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
        return bcryptMatches(password.value(), encoded);
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
      bcryptMatches(password.value(), dummyHash);
    } catch (RuntimeException e) {
      // Résultat ignoré : seul le temps de calcul compte.
    }
  }

  /**
   * Nouveau hash $2y$ au coût donné, calculé par le moteur (statique : appelé depuis le
   * constructeur pour le hash factice).
   */
  private static String bcrypt(BcryptEngine engine, String plain, int cost, SecureRandom random) {
    byte[] input = bcryptInput(plain);
    try {
      String hash = engine.crypt(input, BCrypt.gensalt(BCRYPT_PREFIX, cost, random));
      if (hash == null || hash.length() != BCRYPT_HASH_LENGTH) {
        throw new IllegalStateException("Échec du hachage bcrypt");
      }
      return hash;
    } finally {
      Arrays.fill(input, (byte) 0);
    }
  }

  /** Recalcul par le moteur avec le hash stocké comme réglage, comparaison à temps constant. */
  private boolean bcryptMatches(String plain, String encoded) {
    byte[] input = bcryptInput(plain);
    try {
      String computed = engine.crypt(input, encoded);
      return computed != null
          && MessageDigest.isEqual(
              computed.getBytes(StandardCharsets.UTF_8), encoded.getBytes(StandardCharsets.UTF_8));
    } finally {
      Arrays.fill(input, (byte) 0);
    }
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
