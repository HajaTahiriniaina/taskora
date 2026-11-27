<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\Card;
use App\Entity\InternalEventReceipt;
use App\Entity\User;
use App\Service\InternalCaller;
use App\Service\Notifier;
use App\Service\PushSender;
use Doctrine\DBAL\Exception as DbalException;
use Doctrine\DBAL\Exception\DeadlockException;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Endpoint transitoire appelé par l'outbox de board-service (U7-a, B-ADR-5), contrat
 * docs/contracts/openapi/symfony.internal.yaml (corps NotificationRequested). Réseau interne
 * seulement, hors pare-feu /api : secret INTERNAL_SECRET_BOARD_TO_SYMFONY seul (U12).
 * Reprend Notifier::notify (jamais l'acteur, sans doublon, destinataire inconnu ignoré, Web Push
 * après la réponse) ; tableau ou carte supprimé entre-temps → aucune notification ; idempotent par
 * eventId (internal_event_receipt, même transaction que les notifications).
 */
#[AsController]
class InternalNotificationController
{
    private const TYPES = ['board_invite', 'member_joined', 'member_removed', 'card_assigned', 'card_moved', 'card_due', 'card_comment'];
    private const UUID = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i';

    public function __construct(
        private EntityManagerInterface $em,
        private Notifier $notifier,
        private PushSender $push,
        private InternalCaller $caller,
        private LoggerInterface $logger,
    ) {}

    #[Route('/internal/notifications', methods: ['POST'])]
    public function create(Request $request): JsonResponse
    {
        $this->caller->requireBoard($request);
        $data = json_decode($request->getContent());
        if (!$data instanceof \stdClass) {
            throw new BadRequestHttpException('JSON invalide.');
        }
        $data = get_object_vars($data);
        foreach (['eventId', 'type', 'actorId', 'recipientIds', 'message'] as $field) {
            if (!array_key_exists($field, $data) || (null === $data[$field] && 'actorId' !== $field) || '' === $data[$field]) {
                throw new BadRequestHttpException(sprintf('Le champ "%s" est obligatoire.', $field));
            }
        }
        if (!is_string($data['eventId']) || !preg_match(self::UUID, $data['eventId'])) {
            throw self::invalid('eventId');
        }
        if (!in_array($data['type'], self::TYPES, true)) {
            throw self::invalid('type');
        }
        if (null !== $data['actorId'] && !self::isId($data['actorId'])) {
            throw self::invalid('actorId');
        }
        $recipientIds = $data['recipientIds'];
        if (!is_array($recipientIds) || [] === $recipientIds || count($recipientIds) > 500 || array_filter($recipientIds, fn ($id) => !self::isId($id))) {
            throw self::invalid('recipientIds');
        }
        if (!is_string($data['message']) || mb_strlen($data['message']) > 500) {
            throw self::invalid('message');
        }
        foreach (['boardId', 'cardId'] as $field) {
            if (null !== ($data[$field] ?? null) && !self::isId($data[$field])) {
                throw self::invalid($field);
            }
        }

        $mark = $this->push->mark();
        $eventId = strtolower($data['eventId']);
        try {
            $created = $this->em->wrapInTransaction(fn (): int => $this->notify(
                $eventId, $data['type'], $data['actorId'], $recipientIds,
                $data['message'], $data['boardId'] ?? null, $data['cardId'] ?? null,
            ));
        } catch (DbalException $e) {
            // Transaction annulée : aucun Web Push pour des notifications non enregistrées.
            $this->push->discardSince($mark);
            // Livraison concurrente du même eventId, validée pendant notre transaction (m1) : 1062
            // (clé dupliquée) ou, sous l'isolation par instantané de MariaDB 11.8, 1020 (« Record has
            // changed since last read », DeadlockException). Reçu relu par la connexion DBAL
            // (l'EntityManager est fermé après l'échec) : présent → déjà traité, sinon on relance.
            if (!self::isConcurrentReceipt($e) || false === $this->em->getConnection()->fetchOne('SELECT 1 FROM internal_event_receipt WHERE event_id = ?', [$eventId])) {
                throw $e;
            }
            $this->logger->info('Reçu interne déjà enregistré par une livraison concurrente.');

            return new JsonResponse(['created' => 0]);
        } catch (\Throwable $e) {
            // Transaction annulée : aucun Web Push pour des notifications non enregistrées.
            $this->push->discardSince($mark);
            throw $e;
        }

        return new JsonResponse(['created' => $created]);
    }

    /** @param list<int> $recipientIds */
    private function notify(string $eventId, string $type, ?int $actorId, array $recipientIds, string $message, ?int $boardId, ?int $cardId): int
    {
        if (null !== $this->em->find(InternalEventReceipt::class, $eventId)) {
            return 0; // rejeu : déjà traité, aucun effet
        }
        $this->em->persist(new InternalEventReceipt($eventId, 'NotificationRequested'));
        $board = null === $boardId ? null : $this->em->find(Board::class, $boardId);
        $card = null === $cardId ? null : $this->em->find(Card::class, $cardId);
        if ((null !== $boardId && null === $board) || (null !== $cardId && null === $card)) {
            // Tableau ou carte supprimé entre-temps : la cascade aurait supprimé la notification.
            $this->em->flush();

            return 0;
        }
        $actor = null === $actorId ? null : $this->em->find(User::class, $actorId);
        $recipients = [];
        foreach (array_unique($recipientIds) as $id) {
            $user = $this->em->find(User::class, $id);
            if (null !== $user && $id !== $actor?->getId()) {
                $recipients[] = $user;
            }
        }
        $this->notifier->notify($recipients, $actor, $type, $message, $board, $card);
        $this->em->flush();

        return count($recipients);
    }

    /** Violation de la clé du reçu ou conflit d'instantané, à n'importe quel niveau de la chaîne. */
    private static function isConcurrentReceipt(\Throwable $e): bool
    {
        for ($t = $e; null !== $t; $t = $t->getPrevious()) {
            if ($t instanceof UniqueConstraintViolationException || $t instanceof DeadlockException) {
                return true;
            }
        }

        return false;
    }

    private static function isId(mixed $value): bool
    {
        return is_int($value) && $value >= 1;
    }

    private static function invalid(string $field): BadRequestHttpException
    {
        return new BadRequestHttpException(sprintf('Le champ "%s" est invalide.', $field));
    }
}
