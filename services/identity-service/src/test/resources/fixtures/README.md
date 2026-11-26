# Fixtures dorées d'identity-service

Générées **une fois** par `generate.php` dans le conteneur `api` (PHP 8.4.28, Symfony 8.1, lecture seule,
aucun accès à la base). Toutes les valeurs sont inventées : mots de passe de test, e-mails `@exemple.test`.

| Fichier | Contenu | Utilisé par |
|---|---|---|
| `fixtures/php-hashes.json` | `{label, password, hash, expectedValid}` : `NativePasswordHasher` coûts 13 et 4, `$2a$`/`$2b$` (même sel, préfixe réécrit), `password_hash(ARGON2ID/ARGON2I)`, `sodium_crypto_pwhash_str`, mots de passe ASCII, accents, emoji, 72/73/100 octets, NUL, 4096/4097 octets, vide, hashes corrompus ou inconnus. `expectedValid` = résultat de `NativePasswordHasher::verify` | `SymfonyCompatiblePasswordHasherTest` |
| `compat/php-filter-validate-email.json` | `{input, valid}` = `filter_var($input, FILTER_VALIDATE_EMAIL) !== false` (154 cas) | `PhpEmailValidatorTest` |
| `compat/php-string-functions.json` | `trim`, `mb_strlen`, `mb_substr(…, 0, 120)`, `mb_strtolower` | `PhpStringTest` |
| `fixtures/legacy-users.sql` | 9 comptes `reprise-*@exemple.test` pour `taskora.user` (ids 1–5, 8–11 : trou volontaire) | `LegacyImportIT` |
| `fixtures/legacy-users.json` | mots de passe de test du jeu d'essai et résultat de connexion attendu | `LegacyImportIT` |

Écart volontaire : PHP `password_verify` accepte un hash crypt SHA-512 (`$6$`, entrée `unknown-sha512-crypt`,
`expectedValid=true`) ; l'ADR A2 impose `false` pour tout préfixe autre que `$2y$`/`$2a$`/`$2b$`/`$argon2i(d)$`.
Le test applique cette surcharge explicitement (`A2_OVERRIDES`).

`preg_match('/^#[0-9a-fA-F]{6}$/')` n'est plus généré : depuis E18 identity-service n'applique plus la règle PCRE
de Symfony (`\A#[0-9a-fA-F]{6}\z`, testé par `AvatarColorTest`).

## Régénérer (seulement si PHP ou Symfony change)
Depuis la racine du dépôt, stack démarrée :
```sh
docker compose exec -T api php -d memory_limit=512M < services/identity-service/src/test/resources/fixtures/generate.php > /tmp/identity-fixtures.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/identity-fixtures.json'))
base = 'services/identity-service/src/test/resources/'
for k, v in d.items():
    if k != 'meta':
        open(base + k, 'w').write(v if v.endswith('\n') else v + '\n')
print(d['meta'])
PY
```
Les hashes bcrypt et argon2 changent à chaque génération (sel aléatoire) ; les résultats attendus, non.
