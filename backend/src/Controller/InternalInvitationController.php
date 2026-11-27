<?php

namespace App\Controller;

use App\Entity\User;
use App\Service\InternalCaller;
use App\Service\InvitationClaimer;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\ConflictHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\HttpKernel\Attribute\AsController;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Endpoint transitoire appelé par identity-service après une inscription (U2-a, ADR A10),
 * contrat docs/contracts/openapi/identity-service.internal.yaml. Réseau interne seulement (nginx ne
 * route que /api/ et /uploads/), hors pare-feu /api : protégé par le secret de son appelant
 * INTERNAL_API_SECRET seul (U12, InternalCaller) ; secret vide ou absent, ou égal à
 * INTERNAL_SECRET_BOARD_TO_SYMFONY → toujours refusé.
 * Supprimé à la migration de board-service.
 */
#[AsController]
class InternalInvitationController
{
    public function __construct(
        private EntityManagerInterface $em,
        private InvitationClaimer $claimer,
        private InternalCaller $caller,
    ) {}

    #[Route('/internal/invitations/claim', methods: ['POST'])]
    public function claim(Request $request): JsonResponse
    {
        $this->caller->requireIdentity($request);

        $data = json_decode($request->getContent(), true);
        if (!is_array($data)) {
            throw new BadRequestHttpException('JSON invalide.');
        }
        foreach (['eventId', 'userId', 'email'] as $field) {
            if (!array_key_exists($field, $data) || null === $data[$field] || '' === $data[$field]) {
                throw new BadRequestHttpException(sprintf('Le champ "%s" est obligatoire.', $field));
            }
        }
        if (!is_string($data['eventId'])) {
            throw new BadRequestHttpException('Le champ "eventId" est invalide.');
        }
        if (!is_int($data['userId']) || $data['userId'] < 1) {
            throw new BadRequestHttpException('Le champ "userId" est invalide.');
        }
        if (!is_string($data['email'])) {
            throw new BadRequestHttpException('Le champ "email" est invalide.');
        }

        $user = $this->em->find(User::class, $data['userId']);
        if (!$user instanceof User) {
            // Projection pas encore visible : identity-service rejoue le message.
            throw new NotFoundHttpException('Utilisateur introuvable.');
        }
        $email = mb_strtolower(trim($data['email']));
        if ($email !== mb_strtolower($user->getEmail())) {
            throw new ConflictHttpException('L\'e-mail ne correspond pas à l\'utilisateur.');
        }

        $boardIds = $this->em->wrapInTransaction(function () use ($user, $email): array {
            $ids = $this->claimer->claim($user, $email);
            $this->em->flush();

            return $ids;
        });

        return new JsonResponse(['claimed' => count($boardIds), 'boardIds' => array_values($boardIds)]);
    }
}
