<?php

namespace App\Controller;

use App\Entity\Notification;
use App\Repository\NotificationRepository;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api/notifications')]
class NotificationController extends ApiController
{
    #[Route('', methods: ['GET'])]
    public function index(NotificationRepository $repo): JsonResponse
    {
        $me = $this->me();
        return $this->json([
            'unread' => $repo->countUnread($me),
            'items' => array_map(fn ($n) => $this->presenter->notification($n), $repo->latestFor($me)),
        ]);
    }

    #[Route('/unread-count', methods: ['GET'])]
    public function unread(NotificationRepository $repo): JsonResponse
    {
        return $this->json(['unread' => $repo->countUnread($this->me())]);
    }

    #[Route('/{id}/read', methods: ['POST'])]
    public function read(int $id): JsonResponse
    {
        $n = $this->find(Notification::class, $id);
        if ($n->getRecipient() !== $this->me()) {
            throw new NotFoundHttpException();
        }
        $n->setRead(true);
        $this->em->flush();
        return $this->json($this->presenter->notification($n));
    }

    #[Route('/read-all', methods: ['POST'])]
    public function readAll(NotificationRepository $repo): JsonResponse
    {
        $repo->markAllRead($this->me());
        return $this->json(['unread' => 0]);
    }

    #[Route('/{id}', methods: ['DELETE'])]
    public function delete(int $id): JsonResponse
    {
        $n = $this->find(Notification::class, $id);
        if ($n->getRecipient() !== $this->me()) {
            throw new NotFoundHttpException();
        }
        $this->em->remove($n);
        $this->em->flush();
        return $this->json(null, 204);
    }
}
