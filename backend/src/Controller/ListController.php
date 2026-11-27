<?php

namespace App\Controller;

use App\Entity\BoardList;
use App\Entity\Card;
use App\Entity\Label;
use App\Repository\CardRepository;
use App\Service\ActivityLogger;
use App\Service\BoardAccess;
use App\Service\Trash;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api')]
class ListController extends ApiController
{
    #[Route('/lists/{id}', methods: ['PATCH'])]
    public function update(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        $data = $this->body($request);
        if (array_key_exists('title', $data)) {
            $list->setTitle($this->requireString($data, 'title', 160));
        }
        if (array_key_exists('color', $data)) {
            $list->setColor($data['color'] ?: null);
        }
        $this->em->flush();
        return $this->json($this->presenter->list($list, $this->em->getRepository(Card::class)->countInList($list)));
    }

    /** Moves the list (with its cards) to the board's trash. */
    #[Route('/lists/{id}', methods: ['DELETE'])]
    public function delete(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        $trash->trashList($list, $this->me());
        return $this->json(null, 204);
    }

    /** Archives a list: it leaves the board with its cards, and goes to the board's archives. */
    #[Route('/lists/{id}/archive', methods: ['POST'])]
    public function archive(int $id, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        $list->setArchivedAt($list->getArchivedAt() ?? new \DateTimeImmutable());
        $this->em->flush();
        return $this->json($this->presenter->list($list, $cards->countInList($list)));
    }

    /** Restores an archived list at the right end of the board. */
    #[Route('/lists/{id}/restore', methods: ['POST'])]
    public function restore(int $id, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        if ($list->isArchived()) {
            $last = max([-1, ...array_map(fn ($l) => $l->getPosition(), $list->getBoard()->getOpenLists())]);
            $list->setArchivedAt(null)->setPosition($last + 1);
            $this->em->flush();
        }
        return $this->json($this->presenter->list($list, $cards->countInList($list)));
    }

    /** Paginated cards of a list: GET /api/lists/{id}/cards?offset=0&limit=30&q=&mine=1&label= */
    #[Route('/lists/{id}/cards', methods: ['GET'])]
    public function cards(int $id, Request $request, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        $offset = max(0, $request->query->getInt('offset'));
        $page = $cards->page($id, $offset, $request->query->getInt('limit', 30), BoardController::filters($request, $this->me()));
        return $this->json($this->presenter->cardPage($page, $offset));
    }

    #[Route('/lists/{id}/cards', methods: ['POST'])]
    public function addCard(int $id, Request $request, BoardAccess $access, CardRepository $cards, ActivityLogger $activity): JsonResponse
    {
        $list = $this->findForMember(BoardList::class, $id, $access);
        $card = new Card($list, $this->requireString($this->body($request), 'title'), $cards->countInList($list), $this->me(), $cards->nextNumber($list->getBoard()));
        $this->em->persist($card);
        $activity->log($card, $this->me(), 'created', ['list' => $list->getTitle()]);
        $this->em->flush();
        return $this->json($this->presenter->card($card, ['comments' => 0, 'total' => 0, 'done' => 0]), 201);
    }

    #[Route('/labels/{id}', methods: ['PATCH'])]
    public function updateLabel(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $label = $this->findForMember(Label::class, $id, $access);
        $data = $this->body($request);
        if (isset($data['name'])) {
            $label->setName($this->requireString($data, 'name', 60));
        }
        if (isset($data['color'])) {
            $label->setColor($data['color']);
        }
        $this->em->flush();
        return $this->json($this->presenter->label($label));
    }

    #[Route('/labels/{id}', methods: ['DELETE'])]
    public function deleteLabel(int $id, BoardAccess $access): JsonResponse
    {
        $label = $this->findForMember(Label::class, $id, $access);
        $this->em->getConnection()->executeStatement('DELETE FROM card_label WHERE label_id = ?', [$id]);
        $this->em->remove($label);
        $this->em->flush();
        return $this->json(null, 204);
    }
}
