<?php

namespace App\Service;

use App\Entity\Attachment;
use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\Card;
use App\Entity\User;
use App\Repository\CardRepository;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * Trash (soft delete) for boards, lists and cards.
 *
 * Deleting only marks the row (deletedAt / deletedBy): it disappears everywhere (ApiController::find,
 * BoardAccess and the repositories ignore it) but can be restored for RETENTION_DAYS. After that,
 * purgeExpired() (command app:trash:purge, run every 6 h by the api container) deletes it for good,
 * with the files of its cards on the storage (Amazon S3).
 */
class Trash
{
    public const RETENTION_DAYS = 30;

    public function __construct(
        private EntityManagerInterface $em,
        private CardRepository $cards,
        private Uploads $uploads,
        private ActivityLogger $activity,
    ) {}

    /** Card, list or board in the trash, directly or through its list / board. */
    public static function isTrashed(object $o): bool
    {
        return match (true) {
            $o instanceof Board => $o->isDeleted(),
            $o instanceof BoardList => $o->isDeleted() || $o->getBoard()->isDeleted(),
            $o instanceof Card => $o->isDeleted() || self::isTrashed($o->getList()),
            method_exists($o, 'getCard') => self::isTrashed($o->getCard()),
            default => false,
        };
    }

    public static function expiresAt(?\DateTimeImmutable $deletedAt): ?\DateTimeImmutable
    {
        return $deletedAt?->modify('+'.self::RETENTION_DAYS.' days');
    }

    // ---------------------------------------------------------------- trash / restore

    public function trashCard(Card $card, User $by): void
    {
        $this->em->wrapInTransaction(function () use ($card, $by) {
            $position = $card->getPosition();
            $onBoard = !$card->isArchived();
            $card->trash($by)->setPosition(-1);
            $this->em->flush();
            if ($onBoard) {
                $this->cards->shift($card->getList(), $position + 1, -1); // the next cards move up
            }
        });
    }

    public function restoreCard(Card $card, User $by): void
    {
        if (self::isTrashed($card->getList())) {
            throw new BadRequestHttpException(sprintf('La liste « %s » est dans la corbeille : restaurez-la d’abord.', $card->getList()->getTitle()));
        }
        $card->untrash();
        if (!$card->isArchived()) { // back at the bottom of its list (an archived card goes back to the archives)
            $card->setPosition($this->cards->countInList($card->getList()));
        }
        $this->activity->log($card, $by, 'restored', ['list' => $card->getList()->getTitle()]);
        $this->em->flush();
    }

    public function trashList(BoardList $list, User $by): void
    {
        $list->trash($by);
        $this->em->flush();
    }

    public function restoreList(BoardList $list): void
    {
        $last = max([-1, ...array_map(fn ($l) => $l->getPosition(), $list->getBoard()->getOpenLists())]);
        $list->untrash();
        if (!$list->isArchived()) {
            $list->setPosition($last + 1); // at the right end of the board
        }
        $this->em->flush();
    }

    public function trashBoard(Board $board, User $by): void
    {
        $board->trash($by);
        $this->em->flush();
    }

    public function restoreBoard(Board $board): void
    {
        $board->untrash();
        $this->em->flush();
    }

    // ---------------------------------------------------------------- permanent deletion

    public function purgeCard(Card $card): void
    {
        $keys = array_merge([], ...array_map(fn (Attachment $a) => $a->getAllKeys(), $card->getAttachments()->toArray()));
        $card->setCoverAttachment(null); // break the card ↔ cover attachment cycle before the cascade
        $this->em->flush();
        $this->em->remove($card);
        $this->em->flush();
        $this->uploads->deleteKeys($keys);
    }

    public function purgeList(BoardList $list): void
    {
        $this->purgeRows('c.list_id = ?', [$list->getId()], fn () => $this->em->remove($list));
    }

    public function purgeBoard(Board $board): void
    {
        $this->purgeRows('c.board_id = ?', [$board->getId()], fn () => $this->em->remove($board));
    }

    /**
     * Deletes for good what has been in the trash for more than $days days.
     * @return array{boards:int, lists:int, cards:int}
     */
    public function purgeExpired(int $days = self::RETENTION_DAYS): array
    {
        $before = new \DateTimeImmutable("-$days days");
        $out = ['boards' => 0, 'lists' => 0, 'cards' => 0];
        foreach ($this->em->createQuery('SELECT b FROM App\Entity\Board b WHERE b.deletedAt < :d')->setParameter('d', $before)->toIterable() as $b) {
            $this->purgeBoard($b);
            ++$out['boards'];
        }
        foreach ($this->em->createQuery('SELECT l FROM App\Entity\BoardList l WHERE l.deletedAt < :d')->setParameter('d', $before)->getResult() as $l) {
            $this->purgeList($l);
            ++$out['lists'];
        }
        foreach ($this->em->createQuery('SELECT c FROM App\Entity\Card c WHERE c.deletedAt < :d')->setParameter('d', $before)->getResult() as $c) {
            $this->purgeCard($c);
            ++$out['cards'];
        }
        return $out;
    }

    /** Deletes rows (cascade) then the files of the cards matching $where, read in SQL first. */
    private function purgeRows(string $where, array $params, callable $remove): void
    {
        $conn = $this->em->getConnection();
        $keys = [];
        foreach ($conn->fetchAllNumeric("SELECT a.storage_key, a.thumb_key, a.preview_key FROM attachment a JOIN card c ON c.id = a.card_id WHERE $where", $params) as $row) {
            array_push($keys, ...array_filter($row));
        }
        $conn->transactional(function () use ($conn, $where, $params, $remove) {
            $conn->executeStatement("UPDATE card c SET c.cover_attachment_id = NULL WHERE $where AND c.cover_attachment_id IS NOT NULL", $params);
            $remove();
            $this->em->flush();
        });
        $this->uploads->deleteKeys($keys);
    }
}
