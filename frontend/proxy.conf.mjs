// Proxy of `ng serve`: /api and /uploads go to the Symfony API published by docker compose.
// The target follows API_PORT (and API_BIND) from the environment or from the root .env file,
// so changing the published port in .env is enough.
// When IDENTITY_UPSTREAM designates identity-service (e.g. `identity:8080`), /api/auth/ (prefix)
// and /api/me (exact path, like `location = /api/me` in nginx) go to identity-service, published by
// docker-compose.identity-dev.yml on IDENTITY_BIND:IDENTITY_PORT (8082 by default).
// Only API_BIND, API_PORT, IDENTITY_UPSTREAM, IDENTITY_BIND and IDENTITY_PORT are read; no value is printed.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function fromDotEnv(key) {
  const file = resolve(dirname(fileURLToPath(import.meta.url)), '../.env');
  if (!existsSync(file)) return undefined;
  const line = readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .find((l) => l.startsWith(`${key}=`));
  return (
    line
      ?.slice(key.length + 1)
      .trim()
      .replace(/^(['"])(.*)\1$/, '$2') || undefined
  );
}

const setting = (key, fallback) => process.env[key] ?? fromDotEnv(key) ?? fallback;
const loopback = (bind) => (bind === '0.0.0.0' ? '127.0.0.1' : bind);
const target = `http://${loopback(setting('API_BIND', '127.0.0.1'))}:${setting('API_PORT', '8081')}`;

const route = { changeOrigin: true, secure: false, target };

// Same values as nginx: `api:80` (default, Symfony) or `identity:8080` (identity-service).
const upstreamHost = setting('IDENTITY_UPSTREAM', 'api:80').trim().split(':')[0];
const identity =
  upstreamHost === 'identity'
    ? {
        changeOrigin: true,
        secure: false,
        target: `http://${loopback(setting('IDENTITY_BIND', '127.0.0.1'))}:${setting('IDENTITY_PORT', '8082')}`,
      }
    : undefined;

// Vite proxies a request with the first matching entry: identity routes come before /api.
// A key starting with `^` is a regular expression tested against the URL (path and query).
export default identity
  ? { '/api/auth/': identity, '^/api/me(?:[?#]|$)': identity, '/api': route, '/uploads': route }
  : { '/api': route, '/uploads': route };
