<?php

namespace App\Storage;

use Aws\S3\S3Client;

/** Builds the storage from environment variables (see .env.example). */
class StorageFactory
{
    public static function create(
        string $driver, string $projectDir,
        string $bucket, string $region, string $key, string $secret,
        string $endpoint, string $prefix, string $publicUrl,
    ): FileStorage {
        if ('s3' !== strtolower(trim($driver))) {
            return new LocalFileStorage($projectDir.'/public/uploads');
        }
        if ('' === $bucket || '' === $region) {
            throw new \InvalidArgumentException('STORAGE_DRIVER=s3 requiert AWS_S3_BUCKET et AWS_S3_REGION.');
        }
        $config = ['version' => 'latest', 'region' => $region];
        // Explicit keys, or the default AWS credential chain (IAM role, ~/.aws, ECS/EC2 metadata) when empty
        if ('' !== $key && '' !== $secret) {
            $config['credentials'] = ['key' => $key, 'secret' => $secret];
        }
        if ('' !== $endpoint) {
            $config['endpoint'] = $endpoint;
            $config['use_path_style_endpoint'] = true;
        }
        return new S3FileStorage(new S3Client($config), $bucket, $prefix, $publicUrl);
    }
}
