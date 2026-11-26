<?php

namespace App\Storage;

use App\Service\Uploads;
use Symfony\Component\HttpFoundation\BinaryFileResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/** Fallback when S3 is not configured: files in public/uploads (Docker volume "uploads"). */
class LocalFileStorage implements FileStorage
{
    public function __construct(private string $dir) {}

    public function put(string $key, string $localPath, string $mimeType): void
    {
        $target = $this->path($key);
        if (!is_dir(dirname($target)) && !mkdir(dirname($target), 0775, true) && !is_dir(dirname($target))) {
            throw new \RuntimeException('Dossier d’upload inaccessible.');
        }
        if (!copy($localPath, $target)) {
            throw new \RuntimeException('Écriture du fichier impossible.');
        }
    }

    public function exists(string $key): bool
    {
        return is_file($this->path($key));
    }

    public function info(string $key): ?array
    {
        $path = $this->path($key);
        return is_file($path) ? ['size' => (int) filesize($path), 'type' => mime_content_type($path) ?: 'application/octet-stream'] : null;
    }

    public function download(string $key, string $localPath): void
    {
        if (!$this->exists($key) || !copy($this->path($key), $localPath)) {
            throw new NotFoundHttpException();
        }
    }

    public function readStream(string $key)
    {
        if (!$this->exists($key)) {
            throw new NotFoundHttpException();
        }
        return fopen($this->path($key), 'rb');
    }

    public function deleteMany(array $keys): void
    {
        foreach ($keys as $key) {
            $this->delete($key);
        }
    }

    public function delete(string $key): void
    {
        @unlink($this->path($key));
    }

    public function respond(string $key, string $filename, bool $inline, string $contentType): Response
    {
        if (!$this->exists($key)) {
            throw new NotFoundHttpException();
        }
        $response = new BinaryFileResponse($this->path($key));
        $response->headers->set('Content-Type', $contentType);
        $response->setContentDisposition($inline ? 'inline' : 'attachment', $filename, Uploads::asciiFallback($filename));
        $response->setPrivate()->setMaxAge(2592000);
        return $response;
    }

    public function describe(): string
    {
        return 'local:'.$this->dir;
    }

    public function path(string $key): string
    {
        return $this->dir.'/'.$key;
    }
}
