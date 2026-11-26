<?php
/*
 * Générateur des fixtures dorées d'identity-service (BackTesteur).
 * À exécuter UNE fois dans le conteneur `api` (aucun accès base, aucune donnée réelle) :
 *   docker compose exec -T api php -d memory_limit=512M -- < services/identity-service/src/test/resources/fixtures/generate.php \
 *     > /tmp/out.json
 * Le script écrit un seul objet JSON {fileName: contenu} ; voir fixtures/README.md pour le découpage.
 * Mots de passe et e-mails : valeurs de test inventées (domaine @exemple.test).
 */
require '/app/vendor/autoload.php';

use Symfony\Component\PasswordHasher\Hasher\NativePasswordHasher;

mt_srand(20261126);
$symfony13 = new NativePasswordHasher(null, null, 13); // configuration prod : auto → bcrypt 2y coût 13
$symfony4  = new NativePasswordHasher(null, null, 4);  // configuration test : coût 4
$verify = fn(string $pw, string $hash): bool => $symfony13->verify($hash, $pw);

$passwords = [
    'ascii8'      => 'testpass',
    'accents'     => 'ééééééé8',
    'emoji'       => 'mot🔑passe',
    'bytes72'     => str_repeat('a', 72),
    'bytes73'     => str_repeat('b', 73),
    'bytes100'    => str_repeat('c', 50) . str_repeat('é', 25),
    'nul'         => "avant\0apres",
    'bytes4096'   => str_repeat('d', 4096),
];

$hashes = [];
$add = function (string $label, string $pw, string $hash) use (&$hashes, $verify) {
    $hashes[] = ['label' => $label, 'password' => $pw, 'hash' => $hash, 'expectedValid' => $verify($pw, $hash)];
};
foreach ($passwords as $k => $pw) {
    $add("symfony-2y-13-$k", $pw, $symfony13->hash($pw));
    $add("symfony-2y-4-$k", $pw, $symfony4->hash($pw));
}
// Mauvais mot de passe contre un hash valide
$add('wrong-password-2y-13', 'mauvaismdp', $symfony13->hash('testpass'));
$add('wrong-password-72-vs-73', str_repeat('a', 72) . 'X', $symfony4->hash(str_repeat('a', 72)));
// Variantes de préfixe : même sel et même empreinte, préfixe réécrit
$base = $symfony4->hash('testpass');
$add('prefix-2a', 'testpass', '$2a$' . substr($base, 4));
$add('prefix-2b', 'testpass', '$2b$' . substr($base, 4));
$base100 = $symfony4->hash($passwords['bytes100']);
$add('prefix-2b-bytes100', $passwords['bytes100'], '$2b$' . substr($base100, 4));
// Argon2 (password_hash et sodium), sans pré-hachage
foreach (['ascii8', 'accents', 'bytes100'] as $k) {
    $add("argon2id-$k", $passwords[$k], password_hash($passwords[$k], PASSWORD_ARGON2ID));
    $add("argon2i-$k", $passwords[$k], password_hash($passwords[$k], PASSWORD_ARGON2I));
}
$add('sodium-argon2id-ascii8', 'testpass', sodium_crypto_pwhash_str('testpass', SODIUM_CRYPTO_PWHASH_OPSLIMIT_INTERACTIVE, SODIUM_CRYPTO_PWHASH_MEMLIMIT_INTERACTIVE));
// Limites : vide et > 4096 octets → false en vérification
$add('empty-password', '', $symfony4->hash('testpass'));
$add('bytes4097', str_repeat('d', 4097), $symfony4->hash(str_repeat('d', 4096)));
// Hashes corrompus ou inconnus
$good = $symfony4->hash('testpass');
$add('corrupt-truncated', 'testpass', substr($good, 0, 40));
$add('corrupt-bad-cost', 'testpass', '$2y$99$' . substr($good, 7));
$add('corrupt-empty', 'testpass', '');
$add('unknown-md5', 'testpass', md5('testpass'));
$add('unknown-sha512-crypt', 'testpass', crypt('testpass', '$6$saltsalt$'));
$add('unknown-plain', 'testpass', 'testpass');
$add('corrupt-argon-truncated', 'testpass', substr(password_hash('testpass', PASSWORD_ARGON2ID), 0, 50));

// ---------------------------------------------------------------- filter_var(FILTER_VALIDATE_EMAIL)
$l64 = str_repeat('a', 64); $l65 = str_repeat('a', 65);
$emails = [
    'camille@exemple.fr', 'camille.martin@exemple.fr', 'c@e.fr', 'a@b.c', 'a@b', 'user+tag@exemple.test',
    'user-name@exemple.test', 'user_name@exemple.test', "o'brien@exemple.test", 'x@exemple-test.fr',
    'x@-exemple.fr', 'x@exemple-.fr', 'x@exemple..fr', 'x..y@exemple.fr', '.x@exemple.fr', 'x.@exemple.fr',
    'x@.exemple.fr', 'x@exemple.fr.', 'x@exemple', 'x@localhost', 'x@123', 'x@exemple.123', 'x@1.2.3.4',
    'x@[1.2.3.4]', 'x@[127.0.0.1]', 'x@[256.1.1.1]', 'x@[IPv6:::1]', 'x@[IPv6:2001:db8::1]', 'x@[IPv6:zz]',
    '"x"@exemple.fr', '"x y"@exemple.fr', '"x@y"@exemple.fr', '"x\\"y"@exemple.fr', '"x\\\\y"@exemple.fr', '""@exemple.fr',
    'x y@exemple.fr', ' x@exemple.fr', 'x@exemple.fr ', "x@exemple.fr\n", "x\n@exemple.fr", "x@exemple.fr\r\n", "\tx@exemple.fr",
    'x@exemple fr', '@exemple.fr', 'x@', 'x', '', '@', 'x@@exemple.fr', 'x@y@exemple.fr', 'pas-un-email',
    'é@exemple.fr', 'x@exémple.fr', 'x@xn--exmple-cva.fr', 'ñ@exemple.fr', '用户@例子.广告', 'x@exemple.fr‎',
    "$l64@exemple.fr", "$l65@exemple.fr", 'x@' . str_repeat('a', 63) . '.fr', 'x@' . str_repeat('a', 64) . '.fr',
    'x!#$%&*+/=?^`{|}~@exemple.fr', 'x(comment)@exemple.fr', 'x@exemple.fr(comment)', 'x<y>@exemple.fr',
    'x[y]@exemple.fr', 'x;y@exemple.fr', 'x,y@exemple.fr', 'x:y@exemple.fr', 'x\\y@exemple.fr', 'x"y@exemple.fr',
    'X@EXEMPLE.FR', 'Camille@Exemple.FR', '1@exemple.fr', '123@456.789', 'x@exemple.f', 'x@exemple.fr1',
    'x@exemple.co.uk', 'x@sub.sub.sub.exemple.fr', 'x@e_x.fr', 'x@e--x.fr', 'x@exemple.-fr', 'x@exemple.fr-',
    'x@0.fr', 'x@a-b-c.fr', 'x.y.z@exemple.fr', 'x--y@exemple.fr', '-x@exemple.fr', 'x-@exemple.fr', '_x@exemple.fr',
    'x@exemple.FR', 'x@[exemple.fr]', 'x@[1.2.3]', 'x@[1.2.3.4.5]', 'x@[IPv6:1:2:3:4:5:6:7:8]', 'x@[IPv6:1:2:3:4:5:6:7:8:9]',
    'x@[IPv6:::ffff:1.2.3.4]', 'x@[01.2.3.4]', 'x@[1.2.3.04]', 'x@01.2.3.4', 'x@1.2.3.256', "x\0@exemple.fr",
    'x@exemple.fr' . "\0", 'x@-', 'x@a', 'x@a.b', 'x@a.b-', 'x@a.-b', 'x@a.1b', 'x@a.b1', 'x@1a.b', 'x@a..b',
    '"x".y@exemple.fr', 'x."y"@exemple.fr', '"x"."y"@exemple.fr', '"x"y@exemple.fr', '"x\ty"@exemple.fr', "\"x\ny\"@exemple.fr",
    '"' . str_repeat('a', 62) . '"@exemple.fr', '"' . str_repeat('a', 63) . '"@exemple.fr', 'x@exemple.fr#', 'x@exemple.fr/',
    'mailto:x@exemple.fr', 'x@exemple.fr?', 'x%y@exemple.fr', 'x@exemple%2Efr', 'x@exemple.fr.fr.fr.fr.fr.fr', 'x@-a.b',
    'x@a.b.c.d.e.f.g.h', 'x+@exemple.fr', '+@exemple.fr', 'x@e.x.e.m.p.l.e', 'test@exemple.test', 'compat-1@exemple.test',
];
// Longueurs totales 254 / 255 / 320 et proches
$dom = fn(int $len) => implode('.', str_split(str_repeat('d', $len - 3), 60)) . '.fr';
foreach ([180, 181, 253, 254, 255, 256, 320, 321] as $total) {
    $local = 'x';
    $domainLen = $total - strlen($local) - 1;
    $emails[] = $local . '@' . $dom($domainLen);
    $emails[] = str_repeat('l', 64) . '@' . $dom($total - 65);
}
// Troncature à 180 de l'inscription : adresses de 179 à 182 caractères
foreach ([179, 180, 181, 182] as $total) {
    $emails[] = str_repeat('a', 40) . '@' . $dom($total - 41);
}
$emails = array_values(array_unique($emails));
$emailCases = array_map(fn($e) => ['input' => $e, 'valid' => false !== filter_var($e, FILTER_VALIDATE_EMAIL)], $emails);

// ---------------------------------------------------------------- fonctions de chaîne
$strings = [
    '', ' ', "  Camille Martin ", "\t\n\r\0\x0Bx\x0B\0\r\n\t", "\u{00A0}x\u{00A0}", "\u{2003}x\u{3000}", "\fx\f", 'x',
    'ÉCOLE', 'Ünïcödé ÀÉÎÕÛ', 'İstanbul', 'ΣΟΦΙΑ', 'STRASSE', 'Straße', 'ǅ', 'Ⅻ', 'Ａ', '🔑🔑', '😀' . str_repeat('a', 130),
    str_repeat('é', 150), str_repeat('a', 119) . '😀b', str_repeat('a', 121), "a\u{0301}" . str_repeat('b', 125),
    'Camille@Exemple.FR', ' Camille@Exemple.FR ', "x\n", "\nx", str_repeat(' ', 5) . 'y' . str_repeat("\t", 3),
    "ǈǋ", 'ẞ', 'ﬀ', 'K', 'Ω',
];
$stringCases = array_map(fn($s) => [
    'input' => $s,
    'trim' => trim($s),
    'mb_strlen' => mb_strlen($s),
    'mb_substr_120' => mb_substr($s, 0, 120),
    'mb_strtolower' => mb_strtolower($s),
], $strings);

// ---------------------------------------------------------------- jeu d'essai de reprise
$legacy = [
    ['label' => '2y-13',      'password' => 'motdepasse13',   'hash' => fn($p) => $symfony13->hash($p)],
    ['label' => '2y-4',       'password' => 'motdepasse04',   'hash' => fn($p) => $symfony4->hash($p)],
    ['label' => '2a',         'password' => 'motdepasse2a',   'hash' => fn($p) => '$2a$' . substr($symfony4->hash($p), 4)],
    ['label' => '2b',         'password' => 'motdepasse2b',   'hash' => fn($p) => '$2b$' . substr($symfony4->hash($p), 4)],
    ['label' => 'argon2id',   'password' => 'motdepasseA2id', 'hash' => fn($p) => password_hash($p, PASSWORD_ARGON2ID)],
    ['label' => 'argon2i',    'password' => 'motdepasseA2i',  'hash' => fn($p) => password_hash($p, PASSWORD_ARGON2I)],
    ['label' => 'bytes100',   'password' => str_repeat('p', 100), 'hash' => fn($p) => $symfony13->hash($p)],
    ['label' => 'accent',     'password' => 'mötdepässe',     'hash' => fn($p) => $symfony4->hash($p)],
    ['label' => 'corrupt',    'password' => 'motdepasseKO',   'hash' => fn($p) => substr($symfony4->hash($p), 0, 30)],
];
$colors = ['#6366f1', '#ec4899', '#14b8a6', '#f59e0b', '#8b5cf6', '#ef4444', '#0ea5e9', '#22c55e'];
$sql = "-- Jeu d'essai de reprise (identity-migration.md §7). Comptes FICTIFS @exemple.test, mots de passe de test.\n"
     . "-- Généré par fixtures/generate.php dans le conteneur api. Mots de passe : voir legacy-users.json.\n";
$legacyJson = [];
$id = 1;
foreach ($legacy as $row) {
    $hash = ($row['hash'])($row['password']);
    $email = 'reprise-' . $row['label'] . '@exemple.test';
    $name = 'Compte ' . $row['label'];
    $color = $colors[$id % count($colors)];
    $created = sprintf('2026-0%d-1%d 1%d:00:00', 1 + $id % 9, $id % 10, $id % 10);
    $sql .= sprintf("INSERT INTO `user` (id, email, name, password, avatar_color, roles, created_at) VALUES (%d, '%s', '%s', '%s', '%s', '[]', '%s');\n",
        $id, $email, $name, addslashes($hash), $color, $created);
    $legacyJson[] = ['id' => $id, 'email' => $email, 'password' => $row['password'], 'label' => $row['label'],
        'expectLogin' => $verify($row['password'], $hash)];
    $id += ($id === 5) ? 3 : 1; // trou dans les ids (8 après 5) : AUTO_INCREMENT = MAX(id)+1
}

$flags = JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE;
echo json_encode([
    'fixtures/php-hashes.json' => json_encode($hashes, $flags),
    'compat/php-filter-validate-email.json' => json_encode($emailCases, $flags),
    'compat/php-string-functions.json' => json_encode($stringCases, $flags),
    'fixtures/legacy-users.sql' => $sql,
    'fixtures/legacy-users.json' => json_encode($legacyJson, $flags),
    'meta' => ['php' => PHP_VERSION, 'emailCases' => count($emailCases), 'hashCases' => count($hashes)],
], JSON_UNESCAPED_SLASHES);
