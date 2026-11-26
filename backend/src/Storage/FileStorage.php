<?php

namespace App\Storage;

use Symfony\Component\HttpFoundation\Response;

/**
 * Where uploaded files live. Keys are relative paths like "2026/11/<hex>/report.pdf";
 * the public URL stored in rich text and attachments is always "/uploads/<key>" whatever the driver.
 */
interface FileStorage
{
    /** Stores a local file under $key. */
    public function put(string $key, string $localPath, string $mimeType): void;

    public function exists(string $key): bool;

    /** @return array{size:int, type:string}|null null when the object does not exist */
    public function info(string $key): ?array;

    public function delete(string $key): void;

    /** Deletes many objects (S3: 1000 per request). Missing objects are ignored. */
    public function deleteMany(array $keys): void;

    /** Copies the object to a local file (thumbnails, document conversion). */
    public function download(string $key, string $localPath): void;

    /** @return resource readable stream of the object (proxied to the browser for in-page viewers) */
    public function readStream(string $key);

    /**
     * HTTP response delivering the file (redirect to S3, or the bytes themselves).
     * $inline false forces a download (Content-Disposition: attachment) under $filename.
     */
    public function respond(string $key, string $filename, bool $inline, string $contentType): Response;

    /** Human readable target, for logs and the check command. */
    public function describe(): string;
}
