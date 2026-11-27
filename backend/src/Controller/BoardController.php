<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\BoardMember;
use App\Entity\Invitation;
use App\Entity\Label;
use App\Entity\User;
use App\Repository\BoardRepository;
use App\Repository\CardRepository;
use App\Service\Trash;
use App\Repository\TrelloImportFileRepository;
use App\Repository\UserRepository;
use App\Service\BoardAccess;
use App\Service\Notifier;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\ConflictHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api/boards')]
class BoardController extends ApiController
{
    public const BACKGROUNDS = ['aurora', 'sunset', 'ocean', 'forest', 'midnight', 'peach', 'lagoon', 'berry', 'graphite', 'sand'];

    #[Route('', methods: ['GET'])]
    public function index(BoardRepository $boards, CardRepository $cards): JsonResponse
    {
        $me = $this->me();
        $list = $boards->findForUser($me);
        $counters = $cards->boardCounters(array_map(fn (Board $b) => $b->getId(), $list));
        return $this->json(array_map(fn ($b) => $this->presenter->boardSummary($b, $me, $counters[$b->getId()]), $list));
    }

    #[Route('', methods: ['POST'])]
    public function create(Request $request): JsonResponse
    {
        $data = $this->body($request);
        $me = $this->me();
        $board = new Board($me, $this->requireString($data, 'title', 160));
        $board->setDescription($data['description'] ?? null);
        if (in_array($data['background'] ?? null, self::BACKGROUNDS, true)) {
            $board->setBackground($data['background']);
        }
        $board->addMember(new BoardMember($board, $me, BoardMember::ROLE_OWNER));
        $this->em->persist($board);

        if ($data['withDefaults'] ?? true) {
            foreach (['À faire', 'En cours', 'En revue', 'Terminé'] as $i => $title) {
                $list = new BoardList($board, $title, $i);
                $board->getLists()->add($list);
                $this->em->persist($list);
            }
            foreach ([['Urgent', '#ef4444'], ['Bug', '#f97316'], ['Feature', '#6366f1'], ['Design', '#ec4899'], ['Backend', '#14b8a6'], ['Idée', '#eab308']] as [$n, $c]) {
                $label = new Label($board, $n, $c);
                $board->getLabels()->add($label);
                $this->em->persist($label);
            }
        }
        $this->em->flush();

        return $this->json($this->presenter->board($board, $me, [], array_fill_keys(array_map(fn ($l) => $l->getId(), $board->getLists()->toArray()), 0)), 201);
    }

    #[Route('/{id}', methods: ['GET'], requirements: ['id' => '\d+'])]
    public function show(int $id, BoardAccess $access, CardRepository $cards, TrelloImportFileRepository $trelloFiles): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $invitations = $this->em->getRepository(Invitation::class)->findBy(['board' => $board]);
        $totals = $cards->totals(array_map(fn ($l) => $l->getId(), $board->getOpenLists()));
        return $this->json($this->presenter->board($board, $this->me(), $invitations, $totals, $trelloFiles->remaining($board)));
    }

    /**
     * Batch lazy-loading: first page of several lists in one request.
     * GET /api/boards/{id}/cards?lists=1,2,3&limit=30&q=&mine=1&label=
     */
    #[Route('/{id}/cards', methods: ['GET'], requirements: ['id' => '\\d+'])]
    public function cards(int $id, Request $request, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $wanted = array_filter(array_map('intval', explode(',', (string) $request->query->get('lists', ''))));
        $boardLists = array_map(fn ($l) => $l->getId(), $board->getOpenLists());
        $limit = $request->query->getInt('limit', 30);
        $filters = self::filters($request, $this->me());

        $out = [];
        foreach (array_slice(array_values(array_intersect($wanted, $boardLists)), 0, 20) as $listId) {
            $out[(string) $listId] = $this->presenter->cardPage($cards->page($listId, 0, $limit, $filters), 0);
        }
        return $this->json((object) $out);
    }

    /**
     * Card filters (Trello-like): OR inside a section, AND between sections.
     *  q        keyword (title, description, label, member, custom value)
     *  mine=1   assigned to me · noMember=1 · members=1,2
     *  noLabel=1 · labels=3,4
     *  status=done|open
     *  due=none,overdue,day,week,month
     *  cf[fieldId]=opt1,opt2   (select option ids, or "true" for checkboxes)
     */
    public static function filters(Request $request, User $me): array
    {
        $ints = fn (string $k) => array_values(array_filter(array_map('intval', explode(',', (string) $request->query->get($k, '')))));
        $due = array_values(array_intersect(explode(',', (string) $request->query->get('due', '')), ['none', 'overdue', 'day', 'week', 'month']));
        $status = $request->query->get('status');
        $cf = [];
        foreach ($request->query->all('cf') as $fieldId => $values) {
            $vals = array_values(array_filter(explode(',', (string) $values), 'strlen'));
            if ((int) $fieldId > 0 && $vals) {
                $cf[(int) $fieldId] = array_slice($vals, 0, 50);
            }
        }
        return [
            'q' => mb_substr(trim((string) $request->query->get('q', '')), 0, 100),
            'mine' => $request->query->getBoolean('mine') ? $me->getId() : null,
            'noMember' => $request->query->getBoolean('noMember'),
            'members' => array_slice($ints('members'), 0, 100),
            'noLabel' => $request->query->getBoolean('noLabel'),
            'labels' => array_slice($ints('labels'), 0, 100),
            'status' => in_array($status, ['done', 'open'], true) ? $status : null,
            'due' => $due,
            'cf' => $cf,
        ];
    }

    #[Route('/{id}', methods: ['PATCH'], requirements: ['id' => '\d+'])]
    public function update(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $data = $this->body($request);
        if (array_key_exists('title', $data)) {
            $board->setTitle($this->requireString($data, 'title', 160));
        }
        if (array_key_exists('description', $data)) {
            $board->setDescription($data['description'] ?: null);
        }
        if (in_array($data['background'] ?? null, self::BACKGROUNDS, true)) {
            $board->setBackground($data['background']);
        }
        if (array_key_exists('showCardNumbers', $data)) {
            $board->setShowCardNumbers((bool) $data['showCardNumbers']);
        }
        $this->em->flush();
        return $this->json($this->presenter->boardSummary($board, $this->me()));
    }

    /** Moves the board to its owner's trash (restorable for Trash::RETENTION_DAYS days). */
    #[Route('/{id}', methods: ['DELETE'], requirements: ['id' => '\d+'])]
    public function delete(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $m = $access->member($board, $this->me());
        if (BoardMember::ROLE_OWNER !== $m->getRole()) {
            throw new AccessDeniedHttpException('Seul le propriétaire peut supprimer le tableau.');
        }
        $trash->trashBoard($board, $this->me());
        return $this->json(null, 204);
    }

    /** Archived lists, most recently archived first, with their number of cards. */
    #[Route('/{id}/archived-lists', methods: ['GET'], requirements: ['id' => '\d+'])]
    public function archivedLists(int $id, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $board = $this->findForMember(Board::class, $id, $access);
        $lists = array_values(array_filter($board->getLists()->toArray(), fn ($l) => $l->isArchived() && !$l->isDeleted()));
        usort($lists, fn ($a, $b) => $b->getArchivedAt() <=> $a->getArchivedAt());
        $totals = $cards->totals(array_map(fn ($l) => $l->getId(), $lists));
        return $this->json(array_map(fn ($l) => $this->presenter->list($l, $totals[$l->getId()] ?? 0), $lists));
    }

    /** Archived cards: GET /api/boards/{id}/archived?q=&offset= */
    #[Route('/{id}/archived', methods: ['GET'], requirements: ['id' => '\d+'])]
    public function archived(int $id, Request $request, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $board = $this->findForMember(Board::class, $id, $access);
        $offset = max(0, $request->query->getInt('offset'));
        $page = $cards->archived($board, trim((string) $request->query->get('q', '')), $offset, 30);
        return $this->json([
            'items' => array_map(fn ($c) => [...$this->presenter->card($c), 'listTitle' => $c->getList()->getTitle()], $page['items']),
            'total' => $page['total'],
            'hasMore' => $offset + count($page['items']) < $page['total'],
        ]);
    }

    #[Route('/{id}/favorite', methods: ['POST'], requirements: ['id' => '\d+'])]
    public function favorite(int $id, BoardAccess $access): JsonResponse
    {
        $m = $access->member($this->em->find(Board::class, $id), $this->me());
        $m->setFavorite(!$m->isFavorite());
        $this->em->flush();
        return $this->json(['favorite' => $m->isFavorite()]);
    }

    #[Route('/{id}/members', methods: ['POST'], requirements: ['id' => '\d+'])]
    public function addMember(int $id, Request $request, BoardAccess $access, UserRepository $users, Notifier $notifier): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $me = $this->me();
        $access->manager($board, $me);
        $data = $this->body($request);
        $email = mb_strtolower($this->requireString($data, 'email', 180));
        if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
            throw new BadRequestHttpException('Adresse e-mail invalide.');
        }
        $role = in_array($data['role'] ?? null, [BoardMember::ROLE_ADMIN, BoardMember::ROLE_MEMBER], true) ? $data['role'] : BoardMember::ROLE_MEMBER;

        $user = $users->findOneByEmail($email);
        if (!$user) {
            $repo = $this->em->getRepository(Invitation::class);
            if ($repo->findOneBy(['board' => $board, 'email' => $email])) {
                throw new ConflictHttpException('Une invitation est déjà en attente pour cette adresse.');
            }
            $inv = new Invitation($board, $email, $me);
            $this->em->persist($inv);
            $this->em->flush();
            return $this->json(['status' => 'invited', 'message' => "Invitation enregistrée : $email rejoindra le tableau à son inscription."], 202);
        }

        if ($board->getMembership($user)) {
            throw new ConflictHttpException('Cet utilisateur est déjà membre du tableau.');
        }
        $member = new BoardMember($board, $user, $role);
        $board->addMember($member);
        $notifier->notify([$user], $me, 'board_invite', sprintf('%s vous a ajouté au tableau « %s »', $me->getName(), $board->getTitle()), $board);
        $notifier->notify(array_filter($board->getMemberUsers(), fn (User $u) => $u !== $user), $me,
            'member_joined', sprintf('%s a rejoint le tableau « %s »', $user->getName(), $board->getTitle()), $board);
        $this->em->flush();

        return $this->json(['status' => 'added', 'member' => $this->presenter->member($member)], 201);
    }

    #[Route('/{id}/members/{userId}', methods: ['PATCH'], requirements: ['id' => '\d+', 'userId' => '\d+'])]
    public function updateMember(int $id, int $userId, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->manager($board, $this->me());
        $target = $board->getMembership($this->find(User::class, $userId)) ?? throw new NotFoundHttpException('Membre introuvable.');
        if (BoardMember::ROLE_OWNER === $target->getRole()) {
            throw new AccessDeniedHttpException('Le rôle du propriétaire ne peut pas être modifié.');
        }
        $role = $this->body($request)['role'] ?? '';
        if (!in_array($role, [BoardMember::ROLE_ADMIN, BoardMember::ROLE_MEMBER], true)) {
            throw new BadRequestHttpException('Rôle invalide.');
        }
        $target->setRole($role);
        $this->em->flush();
        return $this->json($this->presenter->member($target));
    }

    #[Route('/{id}/members/{userId}', methods: ['DELETE'], requirements: ['id' => '\d+', 'userId' => '\d+'])]
    public function removeMember(int $id, int $userId, BoardAccess $access, Notifier $notifier): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $me = $this->me();
        $isSelf = $me->getId() === $userId;
        $isSelf ? $access->member($board, $me) : $access->manager($board, $me);

        $target = $board->getMembership($this->find(User::class, $userId)) ?? throw new NotFoundHttpException('Membre introuvable.');
        if (BoardMember::ROLE_OWNER === $target->getRole()) {
            throw new AccessDeniedHttpException('Le propriétaire ne peut pas quitter son tableau.');
        }
        // Unassign from every card of the board (single SQL statement)
        $this->em->getConnection()->executeStatement(
            'DELETE ca FROM card_assignee ca INNER JOIN card c ON c.id = ca.card_id INNER JOIN board_list l ON l.id = c.list_id WHERE l.board_id = ? AND ca.user_id = ?',
            [$board->getId(), $target->getUser()->getId()]);
        $board->removeMember($target);
        if (!$isSelf) {
            $notifier->notify([$target->getUser()], $me, 'member_removed', sprintf('%s vous a retiré du tableau « %s »', $me->getName(), $board->getTitle()));
        }
        $this->em->flush();
        return $this->json(null, 204);
    }

    #[Route('/{id}/invitations/{invId}', methods: ['DELETE'], requirements: ['id' => '\d+', 'invId' => '\d+'])]
    public function cancelInvitation(int $id, int $invId, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->manager($board, $this->me());
        $inv = $this->find(Invitation::class, $invId);
        if ($inv->getBoard() !== $board) {
            throw new NotFoundHttpException();
        }
        $this->em->remove($inv);
        $this->em->flush();
        return $this->json(null, 204);
    }

    #[Route('/{id}/lists', methods: ['POST'], requirements: ['id' => '\d+'])]
    public function addList(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $last = max([-1, ...array_map(fn ($l) => $l->getPosition(), $board->getLists()->toArray())]);
        $list = new BoardList($board, $this->requireString($this->body($request), 'title', 160), $last + 1);
        $this->em->persist($list);
        $this->em->flush();
        return $this->json($this->presenter->list($list, 0), 201);
    }

    #[Route('/{id}/lists/reorder', methods: ['POST'], requirements: ['id' => '\d+'])]
    public function reorderLists(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $ids = array_map('intval', $this->body($request)['ids'] ?? []);
        foreach ($board->getOpenLists() as $list) {
            $pos = array_search($list->getId(), $ids, true);
            if (false !== $pos) {
                $list->setPosition($pos);
            }
        }
        $this->em->flush();
        return $this->json(['ok' => true]);
    }

    #[Route('/{id}/labels', methods: ['POST'], requirements: ['id' => '\d+'])]
    public function addLabel(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $data = $this->body($request);
        $label = new Label($board, $this->requireString($data, 'name', 60), $data['color'] ?? '#6366f1');
        $this->em->persist($label);
        $this->em->flush();
        return $this->json($this->presenter->label($label), 201);
    }
}
