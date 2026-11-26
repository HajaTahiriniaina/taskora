#!/bin/bash
set -e

cd /app

echo "⏳ Attente de la base de données..."
until php bin/console dbal:run-sql "SELECT 1" >/dev/null 2>&1; do
  sleep 1
done
echo "✅ Base de données disponible"

# JWT keys are persisted in a volume so tokens survive restarts
php bin/console lexik:jwt:generate-keypair --skip-if-exists
chmod 644 config/jwt/*.pem || true

php bin/console doctrine:migrations:migrate --no-interaction --allow-no-migration
php bin/console cache:warmup

# Trash: boards / lists / cards deleted more than 30 days ago are purged for good, every 6 hours
(
  while true; do
    sleep 300
    php bin/console app:trash:purge --no-interaction >/dev/null 2>&1 || echo "⚠️  Purge de la corbeille impossible"
    sleep 21300
  done
) &

exec docker-php-entrypoint "$@"
