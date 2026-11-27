<?php

namespace App\Controller;

use App\Entity\Attachment;
use App\Entity\Card;
use App\Repository\AttachmentRepository;
use App\Service\ActivityLogger;
use App\Service\BoardAccess;
use App\Service\DocumentPreview;
use App\Service\Uploads;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\StreamedResponse;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Routing\Attribute\Route;

/** Card attachments: any file, up to Uploads::MAX, stored in FileStorage (Amazon S3). */
#[Route('/api')]
class AttachmentController extends ApiController
{
    #[Route('/cards/{id}/attachments', methods: ['GET'])]
    public function index(int $id, BoardAccess $access, AttachmentRepository $repo): JsonResponse
    {
        $card = $this->findForMember(Card::class, $id, $access);
        return $this->json(array_map(fn (Attachment $a) => $this->presenter->attachment($a), $repo->forCard($card)));
    }

    #[Route('/cards/{id}/attachments', methods: ['POST'])]
    public function upload(int $id, Request $request, BoardAccess $access, Uploads $uploads, ActivityLogger $activity): JsonResponse
    {
        $card = $this->findForMember(Card::class, $id, $access);
        $me = $this->me();
        $f = $uploads->store($request->files->get('file'));
        $attachment = new Attachment($card, $me, $f['name'], $f['key'], $f['mime'], $f['size']);
        if ($f['image']) {
            $attachment->setThumbKey($uploads->thumbnail($f['path'], $f['mime']));
        }
        $this->em->persist($attachment);
        $activity->log($card, $me, 'attachment', ['name' => $attachment->getName()]);
        $this->em->flush();
        return $this->json($this->presenter->attachment($attachment), 201);
    }

    /** Renames an attachment (display and download name). */
    #[Route('/attachments/{id}', methods: ['PATCH'])]
    public function rename(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $attachment = $this->findForMember(Attachment::class, $id, $access);
        $this->guard($attachment, $access);
        $name = $this->requireString($this->body($request), 'name');
        // "Rapport" for "rapport.pdf" keeps the extension, so the download still opens with the right app
        $ext = pathinfo($attachment->getName(), PATHINFO_EXTENSION);
        if ($ext && !pathinfo($name, PATHINFO_EXTENSION)) {
            $name .= '.'.$ext;
        }
        $attachment->setName($name);
        $this->em->flush();
        return $this->json($this->presenter->attachment($attachment));
    }

    /**
     * Removes an attachment. The file is deleted from the storage, except when it was posted in
     * a comment: the comment still shows it (delete the comment to remove it everywhere).
     */
    #[Route('/attachments/{id}', methods: ['DELETE'])]
    public function delete(int $id, BoardAccess $access, Uploads $uploads, ActivityLogger $activity): JsonResponse
    {
        $attachment = $this->findForMember(Attachment::class, $id, $access);
        $this->guard($attachment, $access);
        $activity->log($attachment->getCard(), $this->me(), 'attachment_removed', ['name' => $attachment->getName()]);
        $card = $attachment->getCard();
        if ($card->getCoverAttachment() === $attachment) {
            $card->setCoverAttachment(null);
        }
        // Thumbnail and PDF preview always go; the original stays when a comment still shows it
        $keys = array_filter([$attachment->getThumbKey(), $attachment->getPreviewKey(), $attachment->getComment() ? null : $attachment->getStorageKey()]);
        $this->em->remove($attachment);
        $this->em->flush();
        $uploads->deleteKeys($keys);
        return $this->json(null, 204);
    }

    /**
     * Raw bytes for the in-page viewers (text, JSON, spreadsheets, e-mails), through the API:
     * same origin and authenticated, so the S3 bucket needs no CORS rule.
     */
    #[Route('/attachments/{id}/content', methods: ['GET'])]
    public function content(int $id, BoardAccess $access, Uploads $uploads): StreamedResponse
    {
        $attachment = $this->findForMember(Attachment::class, $id, $access);
        $stream = $uploads->storage()->readStream($attachment->getStorageKey());
        $response = new StreamedResponse(function () use ($stream) {
            while (!feof($stream)) {
                echo fread($stream, 1 << 16);
                flush();
            }
            fclose($stream);
        });
        $response->headers->set('Content-Type', 'application/octet-stream');
        $response->headers->set('Content-Length', (string) $attachment->getSize());
        $response->headers->set('X-Content-Type-Options', 'nosniff');
        $response->setPrivate()->setMaxAge(3600);
        return $response;
    }

    /** PDF rendering of an Office / LibreOffice document (converted on first call, then cached). */
    #[Route('/attachments/{id}/preview', methods: ['GET'])]
    public function preview(int $id, BoardAccess $access, DocumentPreview $previews): JsonResponse
    {
        $attachment = $this->findForMember(Attachment::class, $id, $access);
        $key = $previews->pdfKey($attachment);
        $this->em->flush();
        return $this->json(['url' => '/uploads/'.$key]);
    }

    /** Any board member may add; only the uploader or a board admin may rename / delete. */
    private function guard(Attachment $attachment, BoardAccess $access): void
    {
        $m = $access->memberOfChild($attachment->getCard()->getBoard(), $this->me());
        if ($attachment->getUploader() !== $this->me() && !$m->canManage()) {
            throw new BadRequestHttpException('Seul l’auteur de la pièce jointe ou un administrateur du tableau peut la modifier.');
        }
    }
}
