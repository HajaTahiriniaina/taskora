<?php

namespace App\Controller;

use App\Entity\PushSubscription;
use App\Repository\PushSubscriptionRepository;
use App\Service\PushSender;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

/** Browser notifications: devices of the current user (Web Push subscriptions). */
#[Route('/api/push')]
class PushController extends ApiController
{
    #[Route('', methods: ['GET'])]
    public function index(PushSender $push, PushSubscriptionRepository $repo): JsonResponse
    {
        return $this->json([
            'configured' => $push->isConfigured(),
            'publicKey' => $push->publicKey(),
            'devices' => array_map(fn (PushSubscription $s) => [
                'id' => $s->getId(), 'kind' => $s->getKind(), 'device' => $s->getDevice(), 'endpointHash' => PushSubscription::hash($s->getEndpoint()),
                'createdAt' => $s->getCreatedAt()->format(\DateTimeInterface::ATOM), 'lastSuccessAt' => $s->getLastSuccessAt()?->format(\DateTimeInterface::ATOM),
            ], $repo->findBy(['user' => $this->me()], ['createdAt' => 'DESC'])),
        ]);
    }

    /** Registers this browser (PushSubscription.toJSON() + a device label). Same browser, other account: moved to this account. */
    #[Route('/subscriptions', methods: ['POST'])]
    public function subscribe(Request $request, PushSubscriptionRepository $repo): JsonResponse
    {
        $data = $this->body($request);
        $endpoint = (string) ($data['endpoint'] ?? '');
        $p256dh = (string) ($data['keys']['p256dh'] ?? '');
        $auth = (string) ($data['keys']['auth'] ?? '');
        if (strlen($endpoint) > 2000 || !PushSender::isAllowedEndpoint($endpoint)) {
            throw new BadRequestHttpException('Service de notification du navigateur non reconnu.');
        }
        if (!preg_match('/^[A-Za-z0-9_\-=]{20,200}$/', $p256dh) || !preg_match('/^[A-Za-z0-9_\-=]{8,100}$/', $auth)) {
            throw new BadRequestHttpException('Clés de notification invalides.');
        }
        $device = mb_substr(trim((string) ($data['device'] ?? '')), 0, 120) ?: null;
        $s = $repo->findByEndpoint($endpoint);
        if ($s) {
            $s->setUser($this->me());
            $s->update($endpoint, $p256dh, $auth, $device);
        } else {
            $this->em->persist($s = new PushSubscription($this->me(), $endpoint, $p256dh, $auth, $device));
        }
        $this->em->flush();
        return $this->json(['id' => $s->getId()], 201);
    }

    /** Unregisters this browser (notifications turned off, or logout). */
    #[Route('/subscriptions', methods: ['DELETE'])]
    public function unsubscribe(Request $request, PushSubscriptionRepository $repo): JsonResponse
    {
        $s = $repo->findByEndpoint((string) ($this->body($request)['endpoint'] ?? ''));
        if ($s && $s->getUser() === $this->me()) {
            $this->em->remove($s);
            $this->em->flush();
        }
        return $this->json(null, 204);
    }

    /** Removes one of my devices (profile). */
    #[Route('/devices/{id}', methods: ['DELETE'], requirements: ['id' => '\d+'])]
    public function removeDevice(int $id, PushSubscriptionRepository $repo): JsonResponse
    {
        $s = $repo->find($id);
        if (!$s || $s->getUser() !== $this->me()) {
            throw new NotFoundHttpException('Appareil introuvable.');
        }
        $this->em->remove($s);
        $this->em->flush();
        return $this->json(null, 204);
    }

    /** Test banner on this browser. */
    #[Route('/test', methods: ['POST'])]
    public function test(Request $request, PushSender $push, PushSubscriptionRepository $repo): JsonResponse
    {
        $s = $repo->findByEndpoint((string) ($this->body($request)['endpoint'] ?? ''));
        if (!$s || $s->getUser() !== $this->me()) {
            throw new NotFoundHttpException('Ce navigateur n’est pas abonné aux notifications.');
        }
        $ok = $push->sendNow($s, ['title' => 'Taskora', 'body' => 'Les notifications sont activées sur cet appareil 🎉', 'url' => '/profile', 'tag' => 'test']);
        if (!$ok) {
            throw new BadRequestHttpException('Le service de notification du navigateur a refusé le message.');
        }
        return $this->json(['sent' => true]);
    }
}
