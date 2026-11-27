<?php

namespace App\Service;

use App\Entity\Board;
use App\Entity\BoardMember;
use App\Entity\User;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * Politique d'accès aux tableaux (U8-a, spec phase 3 §5.3, B-ADR-3) : un non-membre reçoit
 * exactement la réponse d'une ressource inexistante (404), jamais 403 ; « dans la corbeille »
 * n'est dit qu'à un membre. Les 403 de gestion sont inchangés.
 */
class BoardAccess
{
    public const BOARD_NOT_FOUND = 'Tableau introuvable.';
    public const BOARD_IN_TRASH = 'Ce tableau est dans la corbeille.';
    public const RESOURCE_NOT_FOUND = 'Ressource introuvable.';

    /** Routes /api/boards/{id}/** : inexistant ou non-membre → « Tableau introuvable. ». */
    public function member(?Board $board, User $user): BoardMember
    {
        $m = $board?->getMembership($user);
        if (!$board || !$m) {
            throw new NotFoundHttpException(self::BOARD_NOT_FOUND);
        }
        if ($board->isDeleted()) {
            throw new NotFoundHttpException(self::BOARD_IN_TRASH);
        }

        return $m;
    }

    public function manager(?Board $board, User $user): BoardMember
    {
        $m = $this->member($board, $user);
        if (!$m->canManage()) {
            throw new AccessDeniedHttpException('Action réservée aux administrateurs du tableau.');
        }
        return $m;
    }

    /**
     * Ressources enfants (listes, cartes, étiquettes, champs, checklist, commentaires, pièces
     * jointes) : tableau d'un non-membre → « Ressource introuvable. », comme une ressource
     * inexistante. La corbeille (élément, liste ou tableau) est contrôlée ensuite par l'appelant,
     * pour un membre seulement.
     */
    public function memberOfChild(?Board $board, User $user): BoardMember
    {
        return $board?->getMembership($user) ?? throw new NotFoundHttpException(self::RESOURCE_NOT_FOUND);
    }
}
