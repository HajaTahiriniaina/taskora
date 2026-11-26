<?php

namespace App\Service;

use App\Entity\Activity;
use App\Entity\Attachment;
use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\BoardMember;
use App\Entity\Card;
use App\Entity\ChecklistItem;
use App\Entity\Comment;
use App\Entity\CustomField;
use App\Entity\Invitation;
use App\Entity\Label;
use App\Entity\Notification;
use App\Entity\User;

/** Turns entities into plain arrays for JSON responses. */
class Presenter
{
    public function user(?User $u): ?array
    {
        if (!$u) {
            return null;
        }
        return ['id' => $u->getId(), 'name' => $u->getName(), 'email' => $u->getEmail(), 'avatarColor' => $u->getAvatarColor()];
    }

    public function member(BoardMember $m): array
    {
        return [...$this->user($m->getUser()), 'role' => $m->getRole(), 'joinedAt' => $this->date($m->getJoinedAt())];
    }

    /** @param array{lists:int,cards:int} $counters */
    public function boardSummary(Board $b, User $viewer, array $counters = ['lists' => 0, 'cards' => 0]): array
    {
        return [
            'id' => $b->getId(),
            'title' => $b->getTitle(),
            'description' => $b->getDescription(),
            'background' => $b->getBackground(),
            'showCardNumbers' => $b->isShowCardNumbers(),
            'favorite' => $b->getMembership($viewer)?->isFavorite() ?? false,
            'role' => $b->getMembership($viewer)?->getRole(),
            'members' => array_map(fn ($m) => $this->member($m), $b->getMembers()->toArray()),
            'listCount' => $counters['lists'],
            'cardCount' => $counters['cards'],
            'createdAt' => $this->date($b->getCreatedAt()),
        ];
    }

    /**
     * Board "shell": lists come WITHOUT cards (loaded lazily, page by page).
     * @param Invitation[] $invitations
     * @param array<int,int> $listTotals listId => card count
     */
    public function board(Board $b, User $viewer, array $invitations, array $listTotals, int $trelloFiles = 0): array
    {
        return [
            ...$this->boardSummary($b, $viewer, ['lists' => count($listTotals), 'cards' => array_sum($listTotals)]),
            /** Files of a Trello import still to copy (pending or failed). */
            'trelloFiles' => $trelloFiles,
            'lists' => array_map(fn ($l) => $this->list($l, $listTotals[$l->getId()] ?? 0), $b->getOpenLists()),
            'labels' => array_map(fn ($l) => $this->label($l), $b->getLabels()->toArray()),
            'customFields' => array_map(fn ($f) => $this->customField($f), $b->getCustomFields()->toArray()),
            'invitations' => array_map(fn (Invitation $i) => ['id' => $i->getId(), 'email' => $i->getEmail(), 'createdAt' => $this->date($i->getCreatedAt())], $invitations),
        ];
    }

    public function list(BoardList $l, int $cardCount = 0): array
    {
        return [
            'id' => $l->getId(),
            'title' => $l->getTitle(),
            'position' => $l->getPosition(),
            'color' => $l->getColor(),
            'archivedAt' => $this->date($l->getArchivedAt()),
            'cardCount' => $cardCount,
        ];
    }

    /**
     * @param array{items: Card[], total: int, stats: array} $page
     */
    public function cardPage(array $page, int $offset): array
    {
        $items = array_map(fn (Card $c) => $this->card($c, $page['stats'][$c->getId()] ?? null), $page['items']);
        return ['items' => $items, 'total' => $page['total'], 'offset' => $offset, 'hasMore' => $offset + count($items) < $page['total']];
    }

    /** @param array{comments:int,total:int,done:int,attachments:int}|null $stats precomputed counters (avoids N+1) */
    public function card(Card $c, ?array $stats = null): array
    {
        if (null === $stats) {
            $done = 0;
            foreach ($c->getChecklist() as $i) {
                $done += $i->isDone() ? 1 : 0;
            }
            $stats = ['comments' => $c->getComments()->count(), 'total' => $c->getChecklist()->count(), 'done' => $done, 'attachments' => $c->getAttachments()->count()];
        }
        $values = [];
        foreach ($c->getCustomValues() as $v) {
            $values[(string) $v->getField()->getId()] = $v->getValue();
        }
        return [
            'id' => $c->getId(),
            'listId' => $c->getList()->getId(),
            'number' => $c->getNumber(),
            'title' => $c->getTitle(),
            'description' => $c->getDescription(),
            'position' => $c->getPosition(),
            'dueDate' => $this->date($c->getDueDate()),
            'completed' => $c->isCompleted(),
            'archivedAt' => $this->date($c->getArchivedAt()),
            'cover' => $c->getCover(),
            'coverImage' => ($ca = $c->getCoverAttachment()) ? '/uploads/'.($ca->getThumbKey() ?? $ca->getStorageKey()) : null,
            'coverAttachmentId' => $c->getCoverAttachment()?->getId(),
            'priority' => $c->getPriority(),
            'labelIds' => array_map(fn (Label $l) => $l->getId(), $c->getLabels()->toArray()),
            'assignees' => array_map(fn (User $u) => $this->user($u), $c->getAssignees()->toArray()),
            'commentCount' => $stats['comments'],
            'attachmentCount' => $stats['attachments'] ?? 0,
            'checklistTotal' => $stats['total'],
            'checklistDone' => $stats['done'],
            'customValues' => (object) $values,
            'createdAt' => $this->date($c->getCreatedAt()),
        ];
    }

    public function cardDetail(Card $c): array
    {
        return [
            ...$this->card($c),
            'listTitle' => $c->getList()->getTitle(),
            'listArchived' => $c->getList()->isArchived(),
            'createdBy' => $this->user($c->getCreatedBy()),
            'comments' => array_map(fn ($cm) => $this->comment($cm), $c->getComments()->toArray()),
            'checklist' => array_map(fn ($i) => $this->checklistItem($i), $c->getChecklist()->toArray()),
            'attachments' => array_map(fn ($a) => $this->attachment($a), $c->getAttachments()->toArray()),
        ];
    }

    public function attachment(Attachment $a): array
    {
        return [
            'id' => $a->getId(),
            'name' => $a->getName(),
            'url' => '/uploads/'.$a->getStorageKey(),
            'mime' => $a->getMimeType(),
            'size' => $a->getSize(),
            'image' => $a->isImage(),
            'thumbUrl' => $a->getThumbKey() ? '/uploads/'.$a->getThumbKey() : null,
            'ext' => $a->getExtension(),
            'commentId' => $a->getComment()?->getId(),
            'uploader' => $this->user($a->getUploader()),
            'createdAt' => $this->date($a->getCreatedAt()),
        ];
    }

    public function activity(Activity $a): array
    {
        $actor = $a->getExternalActor()
            ? ['id' => 0, 'name' => $a->getExternalActor(), 'email' => '', 'avatarColor' => '#94a3b8', 'external' => true]
            : ($this->user($a->getActor()) ?? ['id' => 0, 'name' => 'Utilisateur supprimé', 'email' => '', 'avatarColor' => '#94a3b8']);
        return ['id' => $a->getId(), 'type' => $a->getType(), 'data' => (object) $a->getData(), 'actor' => $actor, 'createdAt' => $this->date($a->getCreatedAt())];
    }

    public function comment(Comment $c): array
    {
        $author = $c->getExternalAuthor()
            ? ['id' => 0, 'name' => $c->getExternalAuthor(), 'email' => '', 'avatarColor' => '#94a3b8', 'external' => true]
            : $this->user($c->getAuthor());
        return ['id' => $c->getId(), 'content' => $c->getContent(), 'author' => $author, 'createdAt' => $this->date($c->getCreatedAt())];
    }

    public function checklistItem(ChecklistItem $i): array
    {
        return ['id' => $i->getId(), 'content' => $i->getContent(), 'done' => $i->isDone(), 'position' => $i->getPosition()];
    }

    public function label(Label $l): array
    {
        return ['id' => $l->getId(), 'name' => $l->getName(), 'color' => $l->getColor()];
    }

    public function customField(CustomField $f): array
    {
        return ['id' => $f->getId(), 'name' => $f->getName(), 'type' => $f->getType(), 'options' => $f->getOptions(), 'showOnCard' => $f->isShowOnCard(), 'position' => $f->getPosition()];
    }

    public function notification(Notification $n): array
    {
        return [
            'id' => $n->getId(),
            'type' => $n->getType(),
            'message' => $n->getMessage(),
            'read' => $n->isRead(),
            'actor' => $this->user($n->getActor()),
            'boardId' => $n->getBoard()?->getId(),
            'boardTitle' => $n->getBoard()?->getTitle(),
            'cardId' => $n->getCard()?->getId(),
            'createdAt' => $this->date($n->getCreatedAt()),
        ];
    }

    private function date(?\DateTimeInterface $d): ?string
    {
        return $d?->format(\DateTimeInterface::ATOM);
    }
}
