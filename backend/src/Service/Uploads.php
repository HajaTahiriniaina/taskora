<?php

namespace App\Service;

use App\Entity\Attachment;
use App\Storage\FileStorage;
use Symfony\Component\HttpFoundation\File\UploadedFile;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\String\Slugger\AsciiSlugger;

/**
 * Stores uploaded files (images for rich text, card attachments) and decides how they are delivered.
 *
 * Keys: "YYYY/MM/<32 random hex>/<safe-name.ext>" (the random part makes the URL unguessable,
 * the name gives a readable download). Older images use "YYYY/MM/<hex>.<ext>".
 * The type is sniffed from the bytes, never trusted from the client: an extension that the
 * browser would render (images, PDF) is kept only when the content really is of that type.
 * Everything else is served as a download with a neutral content type (no HTML/SVG/JS execution).
 */
class Uploads
{
    public const MAX = 100 * 1024 * 1024;
    /** Thumbnails: longest side in pixels; images above this many pixels are not decoded (memory). */
    private const THUMB_SIZE = 640;
    private const THUMB_MAX_PIXELS = 60_000_000;
    public const KEY_PATTERN = '\d{4}/\d{2}/[a-f0-9]{32}(?:\.(?:png|jpg|gif|webp)|/[A-Za-z0-9][A-Za-z0-9._-]{0,119})';

    /** Types displayed in the browser (everything else is downloaded). */
    private const INLINE = [
        'png' => 'image/png', 'jpg' => 'image/jpeg', 'gif' => 'image/gif', 'webp' => 'image/webp', 'pdf' => 'application/pdf',
    ];
    /**
     * Audio / video playable by HTML5 players, streamed inline (S3 and local storage answer Range
     * requests, so seeking works). The type is forced from the extension and sent with nosniff:
     * a non-media file renamed ".mp4" can only fail to play, never be interpreted as a page.
     */
    private const MEDIA = [
        'mp4' => 'video/mp4', 'm4v' => 'video/mp4', 'mov' => 'video/mp4', 'webm' => 'video/webm', 'ogv' => 'video/ogg',
        'mp3' => 'audio/mpeg', 'm4a' => 'audio/mp4', 'aac' => 'audio/aac', 'wav' => 'audio/wav', 'oga' => 'audio/ogg',
        'ogg' => 'audio/ogg', 'opus' => 'audio/ogg', 'flac' => 'audio/flac', 'weba' => 'audio/webm',
    ];
    private const IMAGES = ['image/png' => 'png', 'image/jpeg' => 'jpg', 'image/gif' => 'gif', 'image/webp' => 'webp'];

    public function __construct(private FileStorage $storage) {}

    /**
     * @return array{key:string, url:string, name:string, mime:string, size:int, image:bool, path:string}
     */
    public function store(mixed $file, bool $imagesOnly = false): array
    {
        if (!$file instanceof UploadedFile || !$file->isValid()) {
            throw new BadRequestHttpException(sprintf('Fichier manquant ou trop volumineux (%d Mo max).', self::MAX >> 20));
        }
        if ($file->getSize() > self::MAX) {
            throw new BadRequestHttpException(sprintf('« %s » dépasse %d Mo.', $file->getClientOriginalName(), self::MAX >> 20));
        }
        return $this->storeLocal($file->getPathname(), $file->getClientOriginalName(), $imagesOnly);
    }

    /**
     * Stores a file already on this server (upload, or a download from Trello) under a new random key.
     * @return array{key:string, url:string, name:string, mime:string, size:int, image:bool, path:string}
     */
    public function storeLocal(string $path, string $originalName, bool $imagesOnly = false): array
    {
        $size = (int) filesize($path);
        if ($size > self::MAX) {
            throw new BadRequestHttpException(sprintf('« %s » dépasse %d Mo.', $originalName, self::MAX >> 20));
        }
        $mime = (new \finfo(FILEINFO_MIME_TYPE))->file($path) ?: 'application/octet-stream'; // sniffed from the bytes
        $imageExt = self::IMAGES[$mime] ?? null;
        $image = $imageExt && false !== @getimagesize($path);
        if ($imagesOnly && !$image) {
            throw new BadRequestHttpException('Format non supporté : PNG, JPEG, GIF ou WebP uniquement.');
        }

        $original = trim(str_replace(["\0", '/', '\\'], '', $originalName)) ?: 'fichier';
        $ext = strtolower(preg_replace('/[^A-Za-z0-9]/', '', pathinfo($original, PATHINFO_EXTENSION)));
        $ext = substr($ext, 0, 10);
        if ($image) {
            $ext = $imageExt;
        } elseif (isset(self::INLINE[$ext]) && self::INLINE[$ext] !== $mime) {
            $ext = 'bin'; // e.g. "photo.png" that is not a PNG, "doc.pdf" that is not a PDF
        }
        $base = (string) (new AsciiSlugger())->slug(pathinfo($original, PATHINFO_FILENAME))->slice(0, 100);
        $safe = ltrim($base, '.-_') ?: 'fichier';
        $key = sprintf('%s/%s/%s%s', date('Y/m'), bin2hex(random_bytes(16)), $safe, $ext ? '.'.$ext : '');

        try {
            $this->storage->put($key, $path, $mime);
        } catch (\Throwable $e) {
            throw new \RuntimeException('Enregistrement du fichier impossible ('.$this->storage->describe().') : '.$e->getMessage(), 0, $e);
        }

        return ['key' => $key, 'url' => '/uploads/'.$key, 'name' => mb_substr($original, 0, 255), 'mime' => $mime, 'size' => $size, 'image' => $image, 'path' => $path];
    }

    /**
     * Stores a small WebP copy of an image (≤ 640 px) and returns its key, or null when the
     * original is already small enough or cannot be decoded (it is then used as is).
     */
    public function thumbnail(string $localPath, string $mime): ?string
    {
        $info = @getimagesize($localPath);
        if (!$info || !function_exists('imagewebp')) {
            return null;
        }
        [$w, $h] = $info;
        if ($w * $h > self::THUMB_MAX_PIXELS || (max($w, $h) <= self::THUMB_SIZE && filesize($localPath) < 200_000)) {
            return null;
        }
        $src = match ($mime) {
            'image/png' => @imagecreatefrompng($localPath),
            'image/jpeg' => @imagecreatefromjpeg($localPath),
            'image/gif' => @imagecreatefromgif($localPath),
            'image/webp' => @imagecreatefromwebp($localPath),
            default => false,
        };
        if (!$src) {
            return null;
        }
        if ('image/jpeg' === $mime && function_exists('exif_read_data')) {
            $src = $this->orient($src, $localPath); // photos taken in portrait
            [$w, $h] = [imagesx($src), imagesy($src)];
        }
        $ratio = min(1, self::THUMB_SIZE / max($w, $h));
        $tw = max(1, (int) round($w * $ratio));
        $th = max(1, (int) round($h * $ratio));
        $dst = imagecreatetruecolor($tw, $th);
        imagealphablending($dst, false);
        imagesavealpha($dst, true);
        imagefill($dst, 0, 0, imagecolorallocatealpha($dst, 0, 0, 0, 127));
        imagecopyresampled($dst, $src, 0, 0, 0, 0, $tw, $th, $w, $h);
        $tmp = tempnam(sys_get_temp_dir(), 'th');
        try {
            imagewebp($dst, $tmp, 80);
            $key = sprintf('%s/%s/thumb.webp', date('Y/m'), bin2hex(random_bytes(16)));
            $this->storage->put($key, $tmp, 'image/webp');
            return $key;
        } catch (\Throwable) {
            return null; // a thumbnail is a nice-to-have
        } finally {
            @unlink($tmp);
        }
    }

    /** Thumbnail of an image already in the storage (files posted in comments). */
    public function thumbnailFor(Attachment $attachment): ?string
    {
        if (!$attachment->isImage() || $attachment->getThumbKey()) {
            return $attachment->getThumbKey();
        }
        $tmp = tempnam(sys_get_temp_dir(), 'src');
        try {
            $this->storage->download($attachment->getStorageKey(), $tmp);
            $key = $this->thumbnail($tmp, $attachment->getMimeType());
            $attachment->setThumbKey($key);
            return $key;
        } catch (\Throwable) {
            return null;
        } finally {
            @unlink($tmp);
        }
    }

    /** Deletes storage objects, ignoring errors (an orphan object is harmless). */
    public function deleteKeys(array $keys): void
    {
        $keys = array_values(array_filter($keys));
        if (!$keys) {
            return;
        }
        try {
            $this->storage->deleteMany($keys);
        } catch (\Throwable) {
        }
    }

    private function orient(\GdImage $img, string $path): \GdImage
    {
        $o = (int) (@exif_read_data($path)['Orientation'] ?? 1);
        return match ($o) {
            3 => imagerotate($img, 180, 0),
            6 => imagerotate($img, -90, 0),
            8 => imagerotate($img, 90, 0),
            default => $img,
        };
    }

    /** Storage key from a "/uploads/<key>" URL (relative or absolute on this host), or null. */
    public static function keyFromUrl(string $url): ?string
    {
        $path = parse_url($url, PHP_URL_PATH) ?: '';
        return preg_match('#^/uploads/('.self::KEY_PATTERN.')$#', $path, $m) ? $m[1] : null;
    }

    /** @return array{inline:bool, type:string} how a key must be delivered (displayed / played, or downloaded) */
    public static function delivery(string $key): array
    {
        $ext = strtolower(pathinfo($key, PATHINFO_EXTENSION));
        $type = self::INLINE[$ext] ?? self::MEDIA[$ext] ?? null;
        return $type ? ['inline' => true, 'type' => $type] : ['inline' => false, 'type' => 'application/octet-stream'];
    }

    public static function asciiFallback(string $name): string
    {
        $ascii = (string) (new AsciiSlugger())->slug(pathinfo($name, PATHINFO_FILENAME));
        $ext = preg_replace('/[^A-Za-z0-9]/', '', pathinfo($name, PATHINFO_EXTENSION));
        return ($ascii ?: 'fichier').($ext ? '.'.$ext : '');
    }

    public function storage(): FileStorage
    {
        return $this->storage;
    }
}
