#!/bin/sh
# Taskora — initialisation idempotente de MariaDB, exécutée en root.
#
#   * schéma `identity` + compte `identity` avec EXACTEMENT les droits de
#     docs/architecture/identity-migration.md §2 (jamais DELETE sur taskora.user) ;
#   * base `<DB_NAME>_test` pour PHPUnit (doctrine.yaml when@test ajoute « _test »)
#     et droits du compte applicatif dessus.
#
# Trois façons de l'exécuter (même script, ré-exécutable sans effet de bord) :
#   1. volume vide : automatiquement par /docker-entrypoint-initdb.d (socket local) ;
#   2. volume existant : docker compose exec database /docker-entrypoint-initdb.d/10-taskora-init.sh
#   3. service `db-init` (profil identity) : client MariaDB en TCP (DB_HOST=database),
#      après les migrations Symfony, pour accorder les droits sur la table taskora.user.
#
# Entrées (variables d'environnement, jamais affichées) :
#   MARIADB_ROOT_PASSWORD, MARIADB_DATABASE (défaut taskora), MARIADB_USER (défaut taskora),
#   IDENTITY_DB_PASSWORD (obligatoire), DB_HOST (facultatif : TCP au lieu du socket).
set -eu

: "${MARIADB_ROOT_PASSWORD:?MARIADB_ROOT_PASSWORD manquant}"
: "${IDENTITY_DB_PASSWORD:?IDENTITY_DB_PASSWORD manquant}"
APP_DB="${MARIADB_DATABASE:-taskora}"
APP_USER="${MARIADB_USER:-taskora}"
TEST_DB="${APP_DB}_test"

case "$APP_DB$APP_USER" in
  *[!a-zA-Z0-9_]*) echo "taskora-init: nom de base ou d'utilisateur invalide" >&2; exit 1 ;;
esac

# Mot de passe via MYSQL_PWD : jamais sur la ligne de commande (ps) ni dans la sortie.
export MYSQL_PWD="$MARIADB_ROOT_PASSWORD"
if [ -n "${DB_HOST:-}" ]; then
  set -- --host="$DB_HOST" --port="${DB_PORT:-3306}" --protocol=tcp
else
  set -- --protocol=socket
fi
CLIENT=mariadb
command -v "$CLIENT" >/dev/null 2>&1 || CLIENT=mysql

sql() { "$CLIENT" --no-defaults --skip-ssl -uroot "$@" ; }

# Attente du serveur (cas TCP / exec ; immédiat en initdb).
i=0
until echo 'SELECT 1' | sql "$@" >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && { echo "taskora-init: MariaDB injoignable" >&2; exit 1; }
  sleep 1
done

# Échappement SQL du mot de passe (\ et ').
pw=$(printf '%s' "$IDENTITY_DB_PASSWORD" | sed -e 's/\\/\\\\/g' -e "s/'/''/g")
# Dans GRANT, « _ » est un joker : on l'échappe pour viser exactement la base de test.
test_db_grant=$(printf '%s' "$TEST_DB" | sed 's/_/\\_/g')

has_user_table=$(echo "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${APP_DB}' AND TABLE_NAME='user';" \
  | sql "$@" --batch --skip-column-names)

{
  cat <<SQL
CREATE DATABASE IF NOT EXISTS identity CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'identity'@'%' IDENTIFIED BY '${pw}';
ALTER USER 'identity'@'%' IDENTIFIED BY '${pw}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'identity'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON identity.* TO 'identity'@'%';
CREATE DATABASE IF NOT EXISTS \`${TEST_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON \`${test_db_grant}\`.* TO '${APP_USER}'@'%';
SQL
  if [ "$has_user_table" = "1" ]; then
    # Projection U1-B + lecture pour la reprise. Jamais DELETE (cascades).
    echo "GRANT SELECT, INSERT, UPDATE ON \`${APP_DB}\`.\`user\` TO 'identity'@'%';"
  fi
  echo "FLUSH PRIVILEGES;"
} | sql "$@"

if [ "$has_user_table" = "1" ]; then
  echo "taskora-init: OK (schéma identity, compte identity, droits sur ${APP_DB}.user, base ${TEST_DB})"
else
  echo "taskora-init: OK partiel — ${APP_DB}.user n'existe pas encore (migrations Symfony) :"
  echo "taskora-init: droits sur ${APP_DB}.user à accorder en relançant ce script (service db-init)."
fi
