<?php

namespace App\Service;

use App\Entity\Board;
use App\Entity\BoardMember;
use App\Entity\User;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

class BoardAccess
{
    public function member(?Board $board, User $user): BoardMember
    {
        if (!$board || $board->isDeleted()) {
            throw new NotFoundHttpException($board ? 'Ce tableau est dans la corbeille.' : 'Tableau introuvable.');
        }
        $m = $board->getMembership($user);
        if (!$m) {
            throw new AccessDeniedHttpException("Vous n'êtes pas membre de ce tableau.");
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
}
