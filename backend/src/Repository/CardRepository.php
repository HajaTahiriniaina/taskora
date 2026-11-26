<?php

namespace App\Repository;

use App\Entity\BoardList;
use App\Entity\Card;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\ORM\QueryBuilder;
use Doctrine\Persistence\ManagerRegistry;

/**
 * Paginated, N+1-free access to cards.
 *
 * Filters: ['q' => string, 'assignee' => ?int, 'label' => ?int]
 *
 * @extends ServiceEntityRepository<Card>
 */
class CardRepository extends ServiceEntityRepository
{
    public const MAX_LIMIT = 200;

    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, Card::class); }

    /**
     * One page of a list, fully hydrated.
     * @return array{items: Card[], total: int, stats: array<int, array{comments:int,total:int,done:int,attachments:int}>}
     */
    public function page(int $listId, int $offset, int $limit, array $filters = []): array
    {
        $limit = max(1, min(self::MAX_LIMIT, $limit));
        $ids = array_column($this->filtered($filters)
            ->select('c.id')
            ->andWhere('c.list = :list')->setParameter('list', $listId)
            ->orderBy('c.position', 'ASC')->addOrderBy('c.id', 'ASC')
            ->setFirstResult(max(0, $offset))->setMaxResults($limit)
            ->getQuery()->getScalarResult(), 'id');

        $total = $this->hasFilters($filters)
            ? $this->totals([$listId], $filters)[$listId] ?? 0
            : (int) $this->getEntityManager()->getConnection()->fetchOne('SELECT COUNT(*) FROM card WHERE list_id = ? AND archived_at IS NULL AND deleted_at IS NULL', [$listId]);

        return ['items' => $this->hydrate($ids), 'total' => $total, 'stats' => $this->stats($ids)];
    }

    /** @return array<int, int> listId => number of cards matching the filters */
    public function totals(array $listIds, array $filters = []): array
    {
        if (!$listIds) {
            return [];
        }
        $rows = $this->filtered($filters)
            ->select('IDENTITY(c.list) AS list, COUNT(c.id) AS n')
            ->andWhere('c.list IN (:lists)')->setParameter('lists', $listIds)
            ->groupBy('c.list')
            ->getQuery()->getScalarResult();
        $out = array_fill_keys($listIds, 0);
        foreach ($rows as $r) {
            $out[(int) $r['list']] = (int) $r['n'];
        }
        return $out;
    }

    /**
     * Loads cards with assignees, labels and custom values in ONE query, keeping the given order.
     * @param int[] $ids
     * @return Card[]
     */
    public function hydrate(array $ids): array
    {
        if (!$ids) {
            return [];
        }
        $cards = $this->createQueryBuilder('c')
            ->select('c', 'a', 'l', 'v', 'f', 'ca')
            ->leftJoin('c.assignees', 'a')
            ->leftJoin('c.labels', 'l')
            ->leftJoin('c.customValues', 'v')
            ->leftJoin('v.field', 'f')
            ->leftJoin('c.coverAttachment', 'ca')
            ->where('c.id IN (:ids)')->setParameter('ids', $ids)
            ->getQuery()->getResult();
        $byId = [];
        foreach ($cards as $c) {
            $byId[$c->getId()] = $c;
        }
        return array_values(array_filter(array_map(fn ($id) => $byId[$id] ?? null, $ids)));
    }

    /**
     * Comment and checklist counters for many cards in two grouped queries.
     * @param int[] $ids
     * @return array<int, array{comments:int,total:int,done:int,attachments:int}>
     */
    public function stats(array $ids): array
    {
        if (!$ids) {
            return [];
        }
        $conn = $this->getEntityManager()->getConnection();
        $in = implode(',', array_map('intval', $ids));
        $out = array_fill_keys($ids, ['comments' => 0, 'total' => 0, 'done' => 0, 'attachments' => 0]);
        foreach ($conn->fetchAllAssociative("SELECT card_id, COUNT(*) n FROM attachment WHERE card_id IN ($in) GROUP BY card_id") as $r) {
            $out[(int) $r['card_id']]['attachments'] = (int) $r['n'];
        }
        foreach ($conn->fetchAllAssociative("SELECT card_id, COUNT(*) n FROM comment WHERE card_id IN ($in) GROUP BY card_id") as $r) {
            $out[(int) $r['card_id']]['comments'] = (int) $r['n'];
        }
        foreach ($conn->fetchAllAssociative("SELECT card_id, COUNT(*) t, SUM(done) d FROM checklist_item WHERE card_id IN ($in) GROUP BY card_id") as $r) {
            $out[(int) $r['card_id']]['total'] = (int) $r['t'];
            $out[(int) $r['card_id']]['done'] = (int) $r['d'];
        }
        return $out;
    }

    /** @return array<int, array{lists:int,cards:int}> boardId => counters */
    public function boardCounters(array $boardIds): array
    {
        if (!$boardIds) {
            return [];
        }
        $in = implode(',', array_map('intval', $boardIds));
        $rows = $this->getEntityManager()->getConnection()->fetchAllAssociative(
            "SELECT l.board_id, COUNT(DISTINCT l.id) lists, COUNT(c.id) cards
             FROM board_list l LEFT JOIN card c ON c.list_id = l.id AND c.archived_at IS NULL AND c.deleted_at IS NULL
             WHERE l.board_id IN ($in) AND l.archived_at IS NULL AND l.deleted_at IS NULL GROUP BY l.board_id");
        $out = array_fill_keys($boardIds, ['lists' => 0, 'cards' => 0]);
        foreach ($rows as $r) {
            $out[(int) $r['board_id']] = ['lists' => (int) $r['lists'], 'cards' => (int) $r['cards']];
        }
        return $out;
    }

    /** Atomically reserves the next card number of a board (safe under concurrent inserts). */
    public function nextNumber(\App\Entity\Board $board): int
    {
        $conn = $this->getEntityManager()->getConnection();
        $conn->executeStatement('UPDATE board SET card_seq = LAST_INSERT_ID(card_seq + 1) WHERE id = ?', [$board->getId()]);
        $n = (int) $conn->fetchOne('SELECT LAST_INSERT_ID()');
        $board->setCardSeq($n);
        return $n;
    }

    public function countInList(BoardList $list): int
    {
        return (int) $this->getEntityManager()->getConnection()->fetchOne('SELECT COUNT(*) FROM card WHERE list_id = ? AND archived_at IS NULL AND deleted_at IS NULL', [$list->getId()]);
    }

    /** Shifts positions with a single UPDATE (no entity loading). */
    public function shift(BoardList $list, int $fromPosition, int $delta, ?int $toPosition = null): void
    {
        $qb = $this->getEntityManager()->createQueryBuilder()
            ->update(Card::class, 'c')
            ->set('c.position', 'c.position + :d')->setParameter('d', $delta)
            ->where('c.list = :l')->setParameter('l', $list)
            ->andWhere('c.archivedAt IS NULL')
            ->andWhere('c.deletedAt IS NULL')
            ->andWhere('c.position >= :from')->setParameter('from', $fromPosition);
        if (null !== $toPosition) {
            $qb->andWhere('c.position <= :to')->setParameter('to', $toPosition);
        }
        $qb->getQuery()->execute();
    }

    private function hasFilters(array $f): bool
    {
        return '' !== ($f['q'] ?? '') || !empty($f['mine']) || !empty($f['noMember']) || !empty($f['members'])
            || !empty($f['noLabel']) || !empty($f['labels']) || !empty($f['status']) || !empty($f['due']) || !empty($f['cf']);
    }

    /** OR inside each section, AND between sections. */
    /**
     * Archived cards of a board, most recently archived first (optionally matching a text / #number).
     * @return array{items: Card[], total: int}
     */
    public function archived(\App\Entity\Board $board, string $q, int $offset, int $limit): array
    {
        $qb = $this->createQueryBuilder('c')
            ->join('c.list', 'al')
            ->where('c.board = :b')->setParameter('b', $board)
            ->andWhere('c.archivedAt IS NOT NULL')
            ->andWhere('c.deletedAt IS NULL AND al.deletedAt IS NULL');
        if ('' !== $q) {
            $or = $qb->expr()->orX('c.title LIKE :q', 'c.description LIKE :q');
            if (preg_match('/^#?(\d+)$/', $q, $m)) {
                $or->add('c.number = :n');
                $qb->setParameter('n', (int) $m[1]);
            }
            $qb->andWhere($or)->setParameter('q', '%'.addcslashes($q, '%_').'%');
        }
        $total = (int) (clone $qb)->select('COUNT(c.id)')->getQuery()->getSingleScalarResult();
        $ids = array_column($qb->select('c.id')->orderBy('c.archivedAt', 'DESC')->addOrderBy('c.id', 'DESC')
            ->setFirstResult($offset)->setMaxResults($limit)->getQuery()->getScalarResult(), 'id');
        return ['items' => $this->hydrate($ids), 'total' => $total];
    }

    private function filtered(array $f): QueryBuilder
    {
        $qb = $this->createQueryBuilder('c')->andWhere('c.archivedAt IS NULL')->andWhere('c.deletedAt IS NULL');
        $card = Card::class;

        if ('' !== ($q = $f['q'] ?? '')) {
            $like = '%'.addcslashes($q, '%_').'%';
            $qb->andWhere($qb->expr()->orX(
                'c.title LIKE :q',
                'c.description LIKE :q',
                "c.id IN (SELECT kc1.id FROM $card kc1 JOIN kc1.labels kl WHERE kl.name LIKE :q)",
                "c.id IN (SELECT kc2.id FROM $card kc2 JOIN kc2.assignees ka WHERE ka.name LIKE :q OR ka.email LIKE :q)",
                "c.id IN (SELECT IDENTITY(kv.card) FROM App\\Entity\\CustomFieldValue kv WHERE kv.value LIKE :q)",
                ...(preg_match('/^#?(\d{1,9})$/', $q, $m) ? ['c.number = :qnum'] : []),
            ))->setParameter('q', $like);
            if (preg_match('/^#?(\d{1,9})$/', $q, $m)) {
                $qb->setParameter('qnum', (int) $m[1]);
            }
        }

        $members = [];
        if (!empty($f['mine'])) {
            $members[] = ':mine MEMBER OF c.assignees';
            $qb->setParameter('mine', (int) $f['mine']);
        }
        if (!empty($f['noMember'])) {
            $members[] = 'SIZE(c.assignees) = 0';
        }
        if (!empty($f['members'])) {
            $members[] = "c.id IN (SELECT mc.id FROM $card mc JOIN mc.assignees ma WHERE ma.id IN (:members))";
            $qb->setParameter('members', $f['members']);
        }
        if ($members) {
            $qb->andWhere($qb->expr()->orX(...$members));
        }

        $labels = [];
        if (!empty($f['noLabel'])) {
            $labels[] = 'SIZE(c.labels) = 0';
        }
        if (!empty($f['labels'])) {
            $labels[] = "c.id IN (SELECT lc.id FROM $card lc JOIN lc.labels ll WHERE ll.id IN (:labels))";
            $qb->setParameter('labels', $f['labels']);
        }
        if ($labels) {
            $qb->andWhere($qb->expr()->orX(...$labels));
        }

        if (!empty($f['status'])) {
            $qb->andWhere('c.completed = :completed')->setParameter('completed', 'done' === $f['status']);
        }

        if (!empty($f['due'])) {
            $now = new \DateTimeImmutable();
            $due = [];
            foreach ($f['due'] as $d) {
                $due[] = match ($d) {
                    'none' => 'c.dueDate IS NULL',
                    'overdue' => '(c.dueDate < :now AND c.completed = false)',
                    'day' => '(c.dueDate >= :now AND c.dueDate < :in1d)',
                    'week' => '(c.dueDate >= :now AND c.dueDate < :in7d)',
                    'month' => '(c.dueDate >= :now AND c.dueDate < :in30d)',
                };
            }
            $qb->andWhere($qb->expr()->orX(...$due));
            foreach (['now' => 'now', 'in1d' => '+1 day', 'in7d' => '+7 days', 'in30d' => '+30 days'] as $p => $mod) {
                if (str_contains(implode(' ', $due), ':'.$p)) {
                    $qb->setParameter($p, $now->modify($mod));
                }
            }
        }

        // Custom fields: values are stored JSON-encoded ("opt_id" / true)
        foreach ($f['cf'] ?? [] as $i => $values) {
            $fieldId = (int) $i;
            $alias = 'cf'.$fieldId;
            $encoded = array_map(fn ($v) => 'true' === $v ? 'true' : json_encode((string) $v), $values);
            $qb->andWhere("c.id IN (SELECT IDENTITY({$alias}v.card) FROM App\\Entity\\CustomFieldValue {$alias}v WHERE {$alias}v.field = :{$alias}f AND {$alias}v.value IN (:{$alias}vals))")
               ->setParameter($alias.'f', (int) $fieldId)
               ->setParameter($alias.'vals', $encoded);
        }

        return $qb;
    }
}
