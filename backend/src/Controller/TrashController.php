<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\BoardMember;
use App\Entity\Card;
use App\Repository\BoardRepository;
use App\Service\BoardAccess;
use App\Service\Trash;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Trash: lists and cards of a board (any member restores, admins delete for good),
 * and the boards of their owner. Items are purged automatically after Trash::RETENTION_DAYS.
 */
#[Route('/api')]
class TrashController extends ApiController
{
    #[Route('/boards/{id}/trash', methods: ['GET'], requirements: ['id' => '\d+'])]
    public function board(int $id, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $lists = $this->em->createQuery('SELECT l FROM App\Entity\BoardList l WHERE l.board = :b AND l.deletedAt IS NOT NULL ORDER BY l.deletedAt DESC')
            ->setParameter('b', $board)->getResult();
        $counts = [];
        foreach ($this->em->getConnection()->fetchAllAssociative(
            'SELECT c.list_id, COUNT(*) n FROM card c JOIN board_list l ON l.id = c.list_id WHERE l.board_id = ? AND l.deleted_at IS NOT NULL AND c.deleted_at IS NULL GROUP BY c.list_id', [$board->getId()]) as $r) {
            $counts[(int) $r['list_id']] = (int) $r['n'];
        }
        $cards = $this->em->createQuery('SELECT c, l FROM App\Entity\Card c JOIN c.list l WHERE c.board = :b AND c.deletedAt IS NOT NULL ORDER BY c.deletedAt DESC')
            ->setParameter('b', $board)->setMaxResults(300)->getResult();
        return $this->json([
            'retentionDays' => Trash::RETENTION_DAYS,
            'lists' => array_map(fn (BoardList $l) => [
                ...$this->presenter->list($l, $counts[$l->getId()] ?? 0), ...$this->deletion($l),
            ], $lists),
            'cards' => array_map(fn (Card $c) => [
                'id' => $c->getId(), 'number' => $c->getNumber(), 'title' => $c->getTitle(),
                'listId' => $c->getList()->getId(), 'listTitle' => $c->getList()->getTitle(), 'listDeleted' => $c->getList()->isDeleted(),
                'archived' => $c->isArchived(), ...$this->deletion($c),
            ], $cards),
        ]);
    }

    #[Route('/trash/cards/{id}/restore', methods: ['POST'])]
    public function restoreCard(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $card = $this->trashed(Card::class, $id, $access);
        $trash->restoreCard($card, $this->me());
        return $this->json($this->presenter->cardDetail($card));
    }

    #[Route('/trash/cards/{id}', methods: ['DELETE'])]
    public function purgeCard(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $card = $this->trashed(Card::class, $id, $access, true);
        $trash->purgeCard($card);
        return $this->json(null, 204);
    }

    #[Route('/trash/lists/{id}/restore', methods: ['POST'])]
    public function restoreList(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $list = $this->trashed(BoardList::class, $id, $access);
        $trash->restoreList($list);
        return $this->json($this->presenter->list($list, $this->em->getRepository(Card::class)->countInList($list)));
    }

    #[Route('/trash/lists/{id}', methods: ['DELETE'])]
    public function purgeList(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $list = $this->trashed(BoardList::class, $id, $access, true);
        $trash->purgeList($list);
        return $this->json(null, 204);
    }

    #[Route('/trash/boards', methods: ['GET'])]
    public function boards(BoardRepository $boards): JsonResponse
    {
        return $this->json(array_map(fn (Board $b) => [
            'id' => $b->getId(), 'title' => $b->getTitle(), 'background' => $b->getBackground(), ...$this->deletion($b),
        ], $boards->trashedOwnedBy($this->me())));
    }

    #[Route('/trash/boards/{id}/restore', methods: ['POST'])]
    public function restoreBoard(int $id, Trash $trash): JsonResponse
    {
        $trash->restoreBoard($this->ownedTrashedBoard($id));
        return $this->json(['id' => $id]);
    }

    #[Route('/trash/boards/{id}', methods: ['DELETE'])]
    public function purgeBoard(int $id, Trash $trash): JsonResponse
    {
        $trash->purgeBoard($this->ownedTrashedBoard($id));
        return $this->json(null, 204);
    }

    /** A card / list of the trash, on a board (not itself in the trash) the user belongs to. */
    private function trashed(string $class, int $id, BoardAccess $access, bool $purge = false): Card|BoardList
    {
        $o = $this->em->find($class, $id);
        // U8-a : absent de la corbeille, inexistant ou non-membre → même 404 (aucune fuite).
        if (!$o || !$o->isDeleted() || !$o->getBoard()->getMembership($this->me())) {
            throw new NotFoundHttpException('Cet élément n’est pas dans la corbeille.');
        }
        $m = $access->member($o->getBoard(), $this->me());
        if ($purge && !$m->canManage()) {
            throw new AccessDeniedHttpException('Seuls les administrateurs du tableau peuvent supprimer définitivement.');
        }
        return $o;
    }

    private function ownedTrashedBoard(int $id): Board
    {
        $board = $this->em->find(Board::class, $id);
        $membership = $board?->getMembership($this->me());
        // U8-a : absent de la corbeille, inexistant ou non-membre → même 404 ; membre non propriétaire → 403.
        if (!$board || !$board->isDeleted() || !$membership) {
            throw new NotFoundHttpException('Ce tableau n’est pas dans la corbeille.');
        }
        if (BoardMember::ROLE_OWNER !== $membership->getRole()) {
            throw new AccessDeniedHttpException('Seul le propriétaire du tableau peut le restaurer ou le supprimer.');
        }
        return $board;
    }

    private function deletion(Board|BoardList|Card $o): array
    {
        return [
            'deletedAt' => $o->getDeletedAt()?->format(\DateTimeInterface::ATOM),
            'deletedBy' => $o->getDeletedBy()?->getName(),
            'purgeAt' => Trash::expiresAt($o->getDeletedAt())?->format(\DateTimeInterface::ATOM),
        ];
    }
}
