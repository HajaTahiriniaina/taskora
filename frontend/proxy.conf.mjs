// Proxy of `ng serve`: /api and /uploads go to the Symfony API published by docker compose.
// The target follows API_PORT (and API_BIND) from the environment or from the root .env file,
// so changing the published port in .env is enough. Only these two keys are read.
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
const bind = setting('API_BIND', '127.0.0.1');
const host = bind === '0.0.0.0' ? '127.0.0.1' : bind;
const target = `http://${host}:${setting('API_PORT', '8081')}`;

const route = { changeOrigin: true, secure: false, target };

export default { '/api': route, '/uploads': route };
