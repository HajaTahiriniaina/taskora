#!/bin/sh
# Taskora — initialisation idempotente de MariaDB, exécutée en root.
#
#   * schéma `identity` + compte `identity` avec EXACTEMENT les droits de
#     docs/architecture/identity-migration.md §2 (jamais DELETE sur taskora.user) ;
#   * schéma `board` + compte `board` avec EXACTEMENT les droits de
#     docs/architecture/board-migration.md §3 (DML sur les tables du tableau, jamais de DDL
#     sur taskora, SELECT par colonne sur taskora.user sans password) — seulement si
#     BOARD_DB_PASSWORD est défini (profil board) ;
#   * base `<DB_NAME>_test` pour PHPUnit (doctrine.yaml when@test ajoute « _test »)
#     et droits du compte applicatif dessus.
#
# Trois façons de l'exécuter (même script, ré-exécutable sans effet de bord) :
#   1. volume vide : automatiquement par /docker-entrypoint-initdb.d (socket local) ;
#   2. volume existant : docker compose exec database /docker-entrypoint-initdb.d/10-taskora-init.sh
#   3. service `db-init` (profils identity et board) : client MariaDB en TCP (DB_HOST=database),
#      après les migrations Symfony, pour accorder les droits sur les tables de taskora.
#   (4. tests d'intégration de board-service : copié dans /docker-entrypoint-initdb.d/ d'un
#      conteneur Testcontainers APRÈS le schéma taskora généré, voir docs/ci/requests/backend.md §B6.)
#
# Entrées (variables d'environnement, jamais affichées) :
#   MARIADB_ROOT_PASSWORD, MARIADB_DATABASE (défaut taskora), MARIADB_USER (défaut taskora),
#   IDENTITY_DB_PASSWORD (obligatoire), DB_HOST (facultatif : TCP au lieu du socket),
#   BOARD_DB_PASSWORD (facultatif : vide = compte board non créé ni modifié),
#   BOARD_GRANT_COMMENT_FILES (on | off, défaut off : INSERT sur taskora.attachment, T4 seulement).
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

# Échappement SQL d'un mot de passe (\ et ').
sql_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"; }
pw=$(sql_escape "$IDENTITY_DB_PASSWORD")
# Dans GRANT, « _ » est un joker : on l'échappe pour viser exactement la base de test.
test_db_grant=$(printf '%s' "$TEST_DB" | sed 's/_/\\_/g')

has_user_table=$(echo "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${APP_DB}' AND TABLE_NAME='user';" \
  | sql "$@" --batch --skip-column-names)

# board-migration.md §3 : DML sur les tables du tableau, lecture seule sur les autres.
BOARD_DML_TABLES="board board_list board_member invitation card card_assignee card_label label custom_field custom_field_value checklist_item comment activity"
BOARD_READ_TABLES="attachment trello_import_file"
BOARD_GRANT_COMMENT_FILES="${BOARD_GRANT_COMMENT_FILES:-off}"
case "$BOARD_GRANT_COMMENT_FILES" in
  on|off) ;;
  *) echo "taskora-init: BOARD_GRANT_COMMENT_FILES doit valoir on ou off" >&2; exit 1 ;;
esac
board_tables_expected=0
board_tables_in=""
for t in user $BOARD_DML_TABLES $BOARD_READ_TABLES; do
  board_tables_expected=$((board_tables_expected + 1))
  board_tables_in="${board_tables_in:+$board_tables_in,}'$t'"
done
has_board_tables=$(echo "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${APP_DB}' AND TABLE_NAME IN (${board_tables_in});" \
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
  if [ -n "${BOARD_DB_PASSWORD:-}" ]; then
    bpw=$(sql_escape "$BOARD_DB_PASSWORD")
    cat <<SQL
CREATE DATABASE IF NOT EXISTS board CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'board'@'%' IDENTIFIED BY '${bpw}';
ALTER USER 'board'@'%' IDENTIFIED BY '${bpw}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON board.* TO 'board'@'%';
SQL
    # Droits sur taskora seulement quand toutes les tables existent (migrations Symfony passées).
    if [ "$has_board_tables" = "$board_tables_expected" ]; then
      for t in $BOARD_DML_TABLES; do
        echo "GRANT SELECT, INSERT, UPDATE, DELETE ON \`${APP_DB}\`.\`${t}\` TO 'board'@'%';"
      done
      # Jamais la colonne password (FA10).
      echo "GRANT SELECT (id, email, name, avatar_color, created_at) ON \`${APP_DB}\`.\`user\` TO 'board'@'%';"
      for t in $BOARD_READ_TABLES; do
        echo "GRANT SELECT ON \`${APP_DB}\`.\`${t}\` TO 'board'@'%';"
      done
      if [ "$BOARD_GRANT_COMMENT_FILES" = "on" ]; then
        # T4 seulement (B-ADR-6) : fichiers de commentaire.
        echo "GRANT INSERT ON \`${APP_DB}\`.\`attachment\` TO 'board'@'%';"
      fi
    fi
  fi
  echo "FLUSH PRIVILEGES;"
} | sql "$@"

if [ "$has_user_table" = "1" ]; then
  echo "taskora-init: OK (schéma identity, compte identity, droits sur ${APP_DB}.user, base ${TEST_DB})"
else
  echo "taskora-init: OK partiel — ${APP_DB}.user n'existe pas encore (migrations Symfony) :"
  echo "taskora-init: droits sur ${APP_DB}.user à accorder en relançant ce script (service db-init)."
fi
if [ -z "${BOARD_DB_PASSWORD:-}" ]; then
  echo "taskora-init: BOARD_DB_PASSWORD absent : schéma et compte board ni créés ni modifiés."
elif [ "$has_board_tables" = "$board_tables_expected" ]; then
  echo "taskora-init: OK (schéma board, compte board, droits sur ${APP_DB} selon board-migration.md §3, fichiers de commentaire : ${BOARD_GRANT_COMMENT_FILES})"
else
  echo "taskora-init: OK partiel — tables de ${APP_DB} absentes (migrations Symfony) :"
  echo "taskora-init: droits du compte board sur ${APP_DB} à accorder en relançant ce script (service db-init)."
fi
