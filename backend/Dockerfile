# ---- Taskora API (Symfony 8.1 + FrankenPHP) ----
# Images épinglées par digest d'index multi-architecture (résolu le 26/11/2026 ;
# mise à jour : docker buildx imagetools inspect <image:tag>).
FROM dunglas/frankenphp:1-php8.4-alpine@sha256:f81693fa548684eb12964eeab78d86325b75c5cd91289c09ffc4dd4404a641e5 AS base

RUN apk add --no-cache bash mariadb-client \
 && install-php-extensions pdo_mysql intl opcache zip apcu gd exif gmp

COPY --from=composer:2@sha256:0049ce90b432795bf413a738b073963ada1a689e1a8708104684baafe94586ec /usr/bin/composer /usr/bin/composer
ENV COMPOSER_ALLOW_SUPERUSER=1 \
    SERVER_NAME=":80" \
    APP_ENV=prod \
    APP_DEBUG=0

WORKDIR /app

# Dependencies first (better layer caching)
COPY composer.json composer.lock symfony.lock ./
RUN composer install --no-dev --no-scripts --no-autoloader --prefer-dist --no-progress --no-interaction

COPY . .
RUN composer dump-autoload --classmap-authoritative --no-dev \
 && composer run-script --no-dev post-install-cmd \
 && mkdir -p var config/jwt public/uploads && chmod -R 777 var public/uploads

COPY docker/php.ini $PHP_INI_DIR/conf.d/zz-taskora.ini
COPY docker/entrypoint.sh /usr/local/bin/taskora-entrypoint
RUN chmod +x /usr/local/bin/taskora-entrypoint

EXPOSE 80
ENTRYPOINT ["taskora-entrypoint"]
CMD ["--config", "/etc/frankenphp/Caddyfile", "--adapter", "caddyfile"]
