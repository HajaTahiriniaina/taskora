<?php

namespace App\Service;

use App\Entity\BoardMember;
use App\Entity\Invitation;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;

/**
 * Transforme les invitations en attente d'un utilisateur en adhésions (rôle member), avec une
 * notification board_invite par tableau rejoint, puis supprime les invitations.
 *
 * Utilisé par l'inscription Symfony (AuthController::register, comportement inchangé) et par
 * l'endpoint transitoire POST /internal/invitations/claim appelé par identity-service (U2-a, ADR A10).
 * Idempotent : une adhésion déjà présente ne crée ni doublon ni notification, l'invitation est
 * supprimée quand même. Ne fait pas de flush : l'appelant décide de la transaction.
 */
class InvitationClaimer
{
    public function __construct(private EntityManagerInterface $em, private Notifier $notifier) {}

    /**
     * @return list<int|null> identifiants des tableaux rejoints par cet appel
     */
    public function claim(User $user, string $email): array
    {
        $boardIds = [];
        foreach ($this->em->getRepository(Invitation::class)->findBy(['email' => $email]) as $inv) {
            $board = $inv->getBoard();
            if (null === $user->getId() || null === $board->getMembership($user)) {
                $board->addMember(new BoardMember($board, $user));
                $this->notifier->notify([$user], $inv->getInvitedBy(), 'board_invite',
                    sprintf('%s vous a ajouté au tableau « %s »', $inv->getInvitedBy()?->getName() ?? 'Quelqu\'un', $board->getTitle()), $board);
                $boardIds[] = $board->getId();
            }
            $this->em->remove($inv);
        }

        return $boardIds;
    }
}
