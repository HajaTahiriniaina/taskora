<?php

namespace App\Controller;

use App\Repository\AttachmentRepository;
use App\Service\Uploads;
use App\Storage\FileStorage;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Files inserted in rich text (images, and any file in comments). Stored through FileStorage
 * (Amazon S3 or local volume) under an unguessable random key.
 * The URL saved in descriptions/comments is always "/uploads/<key>", independent of the storage.
 */
class UploadController extends ApiController
{
    #[Route('/api/uploads', methods: ['POST'])]
    public function upload(Request $request, Uploads $uploads): JsonResponse
    {
        $f = $uploads->store($request->files->get('file'));
        return $this->json(['url' => $f['url'], 'name' => $f['name'], 'mime' => $f['mime'], 'size' => $f['size'], 'image' => $f['image']], 201);
    }

    /**
     * Delivers a file: redirect to a presigned S3 URL (private bucket) or the local file.
     * Images and PDFs are displayed (unless ?download=1), everything else is downloaded under its original name.
     */
    #[Route('/uploads/{key}', methods: ['GET', 'HEAD'], requirements: ['key' => Uploads::KEY_PATTERN])]
    public function show(string $key, Request $request, FileStorage $storage, AttachmentRepository $attachments): Response
    {
        $name = $attachments->downloadName($key) ?? basename($key);
        ['inline' => $inline, 'type' => $type] = Uploads::delivery($key);
        $response = $storage->respond($key, $name, $inline && !$request->query->getBoolean('download'), $type);
        $response->headers->set('X-Content-Type-Options', 'nosniff');
        return $response;
    }

    #[Route('/uploads/{any}', requirements: ['any' => '.*'], priority: -10)]
    public function notFound(): never
    {
        throw new NotFoundHttpException();
    }
}
