<?php

namespace App\Storage;

use App\Service\Uploads;
use Aws\S3\Exception\S3Exception;
use Aws\S3\S3Client;
use GuzzleHttp\Psr7\StreamWrapper;
use Symfony\Component\HttpFoundation\HeaderUtils;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Response;

/**
 * Amazon S3 (or any S3-compatible service: MinIO, Scaleway, OVH, R2… via AWS_S3_ENDPOINT).
 * The bucket stays private: files are delivered through short-lived presigned URLs,
 * unless AWS_S3_PUBLIC_URL (e.g. a CloudFront domain) is configured.
 */
class S3FileStorage implements FileStorage
{
    private const URL_TTL = '+60 minutes';

    public function __construct(
        private S3Client $client,
        private string $bucket,
        private string $prefix = '',
        private string $publicUrl = '',
    ) {
        $this->prefix = $prefix ? trim($prefix, '/').'/' : '';
        $this->publicUrl = rtrim($publicUrl, '/');
    }

    public function put(string $key, string $localPath, string $mimeType): void
    {
        $this->client->putObject([
            'Bucket' => $this->bucket,
            'Key' => $this->prefix.$key,
            'SourceFile' => $localPath,
            'ContentType' => $mimeType,
            'CacheControl' => 'public, max-age=31536000, immutable', // keys are random and never rewritten
            'ServerSideEncryption' => 'AES256',
        ]);
    }

    public function exists(string $key): bool
    {
        return $this->client->doesObjectExistV2($this->bucket, $this->prefix.$key);
    }

    public function info(string $key): ?array
    {
        try {
            $head = $this->client->headObject(['Bucket' => $this->bucket, 'Key' => $this->prefix.$key]);
        } catch (S3Exception $e) {
            if (404 === $e->getStatusCode()) {
                return null;
            }
            throw $e;
        }
        return ['size' => (int) $head['ContentLength'], 'type' => (string) ($head['ContentType'] ?? 'application/octet-stream')];
    }

    public function download(string $key, string $localPath): void
    {
        $this->client->getObject(['Bucket' => $this->bucket, 'Key' => $this->prefix.$key, 'SaveAs' => $localPath]);
    }

    public function readStream(string $key)
    {
        $body = $this->client->getObject(['Bucket' => $this->bucket, 'Key' => $this->prefix.$key])['Body'];
        return StreamWrapper::getResource($body);
    }

    public function deleteMany(array $keys): void
    {
        foreach (array_chunk(array_values(array_unique($keys)), 1000) as $chunk) {
            $result = $this->client->deleteObjects([
                'Bucket' => $this->bucket,
                'Delete' => ['Objects' => array_map(fn ($k) => ['Key' => $this->prefix.$k], $chunk), 'Quiet' => true],
            ]);
            if (!empty($result['Errors'])) {
                throw new \RuntimeException(sprintf('%d objet(s) S3 non supprimé(s) : %s', count($result['Errors']), $result['Errors'][0]['Message'] ?? ''));
            }
        }
    }

    public function delete(string $key): void
    {
        $this->client->deleteObject(['Bucket' => $this->bucket, 'Key' => $this->prefix.$key]);
    }

    public function respond(string $key, string $filename, bool $inline, string $contentType): Response
    {
        if ($this->publicUrl && $inline) {
            $response = new RedirectResponse($this->publicUrl.'/'.$this->prefix.$key, 301);
            $response->setPublic()->setMaxAge(86400);
            return $response;
        }
        // S3 sends these headers back itself: file name, download vs display, and a safe content type
        $command = $this->client->getCommand('GetObject', [
            'Bucket' => $this->bucket,
            'Key' => $this->prefix.$key,
            'ResponseContentDisposition' => HeaderUtils::makeDisposition($inline ? 'inline' : 'attachment', $filename, Uploads::asciiFallback($filename)),
            'ResponseContentType' => $contentType,
        ]);
        $url = (string) $this->client->createPresignedRequest($command, self::URL_TTL)->getUri();
        $response = new RedirectResponse($url, 302);
        // The browser may reuse the redirect a little while, well before the signature expires
        $response->setPrivate()->setMaxAge(600);
        return $response;
    }

    public function describe(): string
    {
        return sprintf('s3://%s/%s', $this->bucket, $this->prefix);
    }
}
