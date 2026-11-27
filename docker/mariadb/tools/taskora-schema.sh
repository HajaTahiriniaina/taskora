#!/usr/bin/env bash
# Taskora — schéma `taskora` des tests d'intégration de board-service, généré depuis les
# migrations Doctrine RÉELLES de backend/migrations (spec phase 3 §9.1, demande CI §B4/§B6).
#
#   docker/mariadb/tools/taskora-schema.sh            régénère le fichier (défaut ci-dessous)
#   docker/mariadb/tools/taskora-schema.sh --check    échoue (code 1, diff affiché) si le fichier
#                                                     versionné diffère des migrations
#   docker/mariadb/tools/taskora-schema.sh --output F écrit ailleurs (F absolu ou relatif au dépôt)
#
# Mécanique (aucune stack compose, aucune base existante touchée) :
#   1. image Symfony construite depuis backend/ (cache Docker), ou TASKORA_SCHEMA_API_IMAGE si fourni
#      (backend/migrations est alors monté en lecture seule : ce sont toujours celles de l'arbre) ;
#   2. réseau Docker --internal et conteneur MariaDB jetables (même image et même digest que
#      docker-compose.yml), mots de passe éphémères générés ici, jamais affichés ;
#   3. php bin/console doctrine:migrations:migrate -n (APP_ENV=prod, sans entrypoint : ni clés JWT,
#      ni purge) ;
#   4. mariadb-dump --no-data de `taskora`, normalisé pour être déterministe et chargeable par le
#      client mariadb, par /docker-entrypoint-initdb.d et par ScriptUtils de Testcontainers :
#      commentaires conditionnels /*!…*/ retirés, AUTO_INCREMENT=N retiré, en-tête et pied explicites ;
#   5. nettoyage systématique (conteneurs, réseau), y compris en cas d'échec.
# Prérequis : docker, openssl, git. Durée : ~1 min avec le cache de l'image Symfony.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
DEFAULT_OUTPUT="services/board-service/src/test/resources/schema/taskora-schema.sql"
OUTPUT="$DEFAULT_OUTPUT"
CHECK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK=1 ;;
    --output) shift; OUTPUT="${1:?--output exige un chemin}" ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "taskora-schema: option inconnue : $1" >&2; exit 2 ;;
  esac
  shift
done
case "$OUTPUT" in /*) ;; *) OUTPUT="$REPO_ROOT/$OUTPUT" ;; esac

log() { echo "taskora-schema: $*" >&2; }

# Image MariaDB : une seule source de vérité, le service `database` de docker-compose.yml.
MARIADB_IMAGE=$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(mariadb:[^[:space:]]*@sha256:[0-9a-f]\{64\}\)[[:space:]]*$/\1/p' \
  "$REPO_ROOT/docker-compose.yml" | head -n 1)
[ -n "$MARIADB_IMAGE" ] || { log "image mariadb épinglée introuvable dans docker-compose.yml"; exit 1; }

suffix="$$-$(openssl rand -hex 4)"
NET="taskora-schema-net-$suffix"
DB="taskora-schema-db-$suffix"
TMP_DIR=$(mktemp -d)

cleanup() {
  docker rm -f -v "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

# Secrets éphémères : transmis par l'environnement (docker run -e NOM), jamais dans argv ni la sortie.
MARIADB_ROOT_PASSWORD=$(openssl rand -hex 24)
MARIADB_PASSWORD=$(openssl rand -hex 24)
APP_SECRET=$(openssl rand -hex 16)
DATABASE_URL="mysql://taskora:${MARIADB_PASSWORD}@${DB}:3306/taskora?serverVersion=11.8.2-MariaDB&charset=utf8mb4"
export MARIADB_ROOT_PASSWORD MARIADB_PASSWORD APP_SECRET DATABASE_URL

API_IMAGE="${TASKORA_SCHEMA_API_IMAGE:-}"
if [ -z "$API_IMAGE" ]; then
  API_IMAGE="taskora-schema-api:local"
  log "construction de l'image Symfony (backend/, cache Docker)…"
  docker build -q -t "$API_IMAGE" "$REPO_ROOT/backend" >/dev/null
fi

docker network create --internal "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" \
  -e MARIADB_ROOT_PASSWORD -e MARIADB_PASSWORD \
  -e MARIADB_DATABASE=taskora -e MARIADB_USER=taskora \
  "$MARIADB_IMAGE" --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null

log "attente de MariaDB…"
for i in $(seq 1 90); do
  docker exec "$DB" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1 && break
  [ "$i" -eq 90 ] && { log "MariaDB injoignable"; exit 1; }
  sleep 1
done

log "migrations Doctrine (backend/migrations)…"
docker run --rm --network "$NET" --entrypoint php \
  -e APP_ENV=prod -e APP_DEBUG=0 -e APP_SECRET -e DATABASE_URL \
  -v "$REPO_ROOT/backend/migrations:/app/migrations:ro" \
  "$API_IMAGE" bin/console doctrine:migrations:migrate --no-interaction --allow-no-migration -q \
  >"$TMP_DIR/migrate.log" 2>&1 || { cat "$TMP_DIR/migrate.log" >&2; log "échec des migrations Doctrine"; exit 1; }

versions=$(docker exec "$DB" sh -c 'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb -uroot --batch --skip-column-names taskora -e "SELECT version FROM doctrine_migration_versions ORDER BY version"')
count=$(printf '%s\n' "$versions" | grep -c . || true)
last=$(printf '%s\n' "$versions" | tail -n 1 | sed 's/.*\\//')
expected=$(find "$REPO_ROOT/backend/migrations" -maxdepth 1 -name 'Version*.php' | wc -l | tr -d ' ')
[ "$count" = "$expected" ] || { log "migrations appliquées : $count, fichiers : $expected"; exit 1; }

log "export du schéma ($count migrations)…"
docker exec "$DB" sh -c 'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb-dump -uroot --no-data --skip-comments --skip-add-locks --skip-set-charset --skip-disable-keys --single-transaction taskora' \
  > "$TMP_DIR/raw.sql"

{
  echo "-- Schéma \`taskora\` généré depuis backend/migrations ($count migrations Doctrine, dernière : $last)."
  echo "-- NE PAS MODIFIER À LA MAIN : docker/mariadb/tools/taskora-schema.sh (contrôle CI : --check)."
  echo "-- Structure seulement (aucune donnée). Chargement : client mariadb, /docker-entrypoint-initdb.d ou ScriptUtils."
  echo "SET NAMES utf8mb4;"
  echo "SET FOREIGN_KEY_CHECKS = 0;"
  echo "CREATE DATABASE IF NOT EXISTS \`taskora\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  echo "USE \`taskora\`;"
  # Retire les lignes /*!NNNNN …*/; et /*M!…*/ (spécifiques au client) et les compteurs AUTO_INCREMENT.
  grep -v -E '^/\*M?!.*\*/;?[[:space:]]*$' "$TMP_DIR/raw.sql" \
    | sed -E 's/ AUTO_INCREMENT=[0-9]+//' \
    | cat -s
  echo "SET FOREIGN_KEY_CHECKS = 1;"
} > "$TMP_DIR/taskora-schema.sql"

# Garde-fou : plus aucun commentaire conditionnel (ScriptUtils les retirerait sans les exécuter).
if grep -q -E '/\*M?!' "$TMP_DIR/taskora-schema.sql"; then
  log "commentaire conditionnel /*! restant dans le schéma : normalisation à revoir"; exit 1
fi

if [ "$CHECK" = 1 ]; then
  if [ ! -f "$OUTPUT" ]; then
    log "fichier absent : ${OUTPUT#"$REPO_ROOT"/} (le générer sans --check puis le commiter)"; exit 1
  fi
  if ! diff -u "$OUTPUT" "$TMP_DIR/taskora-schema.sql" >&2; then
    log "DÉRIVE : ${OUTPUT#"$REPO_ROOT"/} ne correspond plus à backend/migrations."
    log "Régénérer : docker/mariadb/tools/taskora-schema.sh, puis commiter le fichier (BackTesteur)."
    exit 1
  fi
  log "à jour (${OUTPUT#"$REPO_ROOT"/}, $count migrations)."
else
  mkdir -p "$(dirname "$OUTPUT")"
  cp "$TMP_DIR/taskora-schema.sql" "$OUTPUT"
  log "écrit : ${OUTPUT#"$REPO_ROOT"/} ($count migrations, dernière : $last)."
fi
