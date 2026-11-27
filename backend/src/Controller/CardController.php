<?php

namespace App\Controller;

use App\Entity\Attachment;
use App\Entity\BoardList;
use App\Entity\Card;
use App\Entity\ChecklistItem;
use App\Entity\Comment;
use App\Entity\CustomField;
use App\Entity\CustomFieldValue;
use App\Entity\Label;
use App\Entity\User;
use App\Entity\Activity;
use App\Repository\ActivityRepository;
use App\Repository\CardRepository;
use App\Service\ActivityLogger;
use App\Service\CommentAttachments;
use App\Service\Trash;
use App\Service\Uploads;
use App\Service\Presenter;
use Doctrine\ORM\EntityManagerInterface;
use App\Service\BoardAccess;
use App\Service\Notifier;
use App\Service\RichText;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api')]
class CardController extends ApiController
{
    private const PRIORITIES = ['none', 'low', 'medium', 'high', 'urgent'];

    public function __construct(EntityManagerInterface $em, Presenter $presenter, private ActivityLogger $activity)
    {
        parent::__construct($em, $presenter);
    }

    private function card(int $id, BoardAccess $access): Card
    {
        $card = $this->findForMember(Card::class, $id, $access);
        return $card;
    }

    #[Route('/cards/{id}', methods: ['GET'])]
    public function show(int $id, BoardAccess $access): JsonResponse
    {
        return $this->json($this->presenter->cardDetail($this->card($id, $access)));
    }

    #[Route('/cards/{id}', methods: ['PATCH'])]
    public function update(int $id, Request $request, BoardAccess $access, Notifier $notifier, RichText $rich, Uploads $uploads): JsonResponse
    {
        $card = $this->card($id, $access);
        $data = $this->body($request);
        $me = $this->me();
        $before = [
            'title' => $card->getTitle(), 'description' => $card->getDescription(), 'due' => $card->getDueDate(),
            'completed' => $card->isCompleted(), 'cover' => $card->getCover(), 'priority' => $card->getPriority(),
            'coverAttachment' => $card->getCoverAttachment(),
            'labels' => array_combine(array_map(fn (Label $l) => $l->getId(), $card->getLabels()->toArray()), $card->getLabels()->toArray()),
        ];

        if (array_key_exists('title', $data)) {
            $card->setTitle($this->requireString($data, 'title'));
        }
        if (array_key_exists('description', $data)) {
            $card->setDescription($rich->clean($data['description']));
        }
        if (array_key_exists('dueDate', $data)) {
            $card->setDueDate($data['dueDate'] ? new \DateTimeImmutable($data['dueDate']) : null);
            if ($data['dueDate']) {
                $notifier->notify($card->getAssignees(), $me, 'card_due',
                    sprintf('%s a fixé l\'échéance de « %s » au %s', $me->getName(), $card->getTitle(), $card->getDueDate()->format('d/m/Y')), $card->getBoard(), $card);
            }
        }
        if (array_key_exists('completed', $data)) {
            $card->setCompleted((bool) $data['completed']);
        }
        if (array_key_exists('cover', $data)) {
            $card->setCover($data['cover'] ?: null);
            $card->setCoverAttachment(null); // a color replaces the image
        }
        if (array_key_exists('coverAttachmentId', $data)) {
            $attachment = $data['coverAttachmentId'] ? $this->em->find(Attachment::class, (int) $data['coverAttachmentId']) : null;
            if ($attachment && ($attachment->getCard() !== $card || !$attachment->isImage())) {
                throw new BadRequestHttpException('Seule une image jointe à cette carte peut servir de couverture.');
            }
            if ($attachment) {
                $uploads->thumbnailFor($attachment); // images posted in comments have no thumbnail yet
                $card->setCover(null);
            }
            $card->setCoverAttachment($attachment);
        }
        if (array_key_exists('priority', $data)) {
            if (!in_array($data['priority'], self::PRIORITIES, true)) {
                throw new BadRequestHttpException('Priorité invalide.');
            }
            $card->setPriority($data['priority']);
        }
        if (array_key_exists('labelIds', $data)) {
            $card->getLabels()->clear();
            foreach ((array) $data['labelIds'] as $labelId) {
                $label = $this->em->find(Label::class, (int) $labelId);
                if ($label && $label->getBoard() === $card->getBoard()) {
                    $card->getLabels()->add($label);
                }
            }
        }
        $this->logChanges($card, $me, $before);
        $this->em->flush();
        return $this->json($this->presenter->cardDetail($card));
    }

    /** Moves the card to the board's trash (restorable for Trash::RETENTION_DAYS days). */
    #[Route('/cards/{id}', methods: ['DELETE'])]
    public function delete(int $id, BoardAccess $access, Trash $trash): JsonResponse
    {
        $card = $this->card($id, $access);
        $trash->trashCard($card, $this->me());
        return $this->json(null, 204);
    }

    /**
     * Moves a card with at most 2 bulk UPDATEs, whatever the size of the lists.
     * Positions are contiguous (0..n-1) inside a list.
     */
    #[Route('/cards/{id}/move', methods: ['POST'])]
    public function move(int $id, Request $request, BoardAccess $access, Notifier $notifier, CardRepository $cards): JsonResponse
    {
        $card = $this->card($id, $access);
        if ($card->isArchived()) {
            throw new BadRequestHttpException('Restaurez la carte avant de la déplacer.');
        }
        $data = $this->body($request);
        $target = $this->find(BoardList::class, (int) ($data['listId'] ?? 0));
        if ($target->isArchived()) {
            throw new BadRequestHttpException('Impossible de déplacer une carte vers une liste archivée.');
        }
        if ($target->getBoard() !== $card->getBoard()) {
            throw new BadRequestHttpException('Impossible de déplacer une carte vers un autre tableau.');
        }
        $source = $card->getList();
        $old = $card->getPosition();
        $this->em->wrapInTransaction(function () use ($cards, $card, $source, $target, $old, $data) {
            if ($source === $target) {
                $new = min(max(0, (int) ($data['position'] ?? 0)), $cards->countInList($target) - 1);
                if ($new > $old) {
                    $cards->shift($target, $old + 1, -1, $new);
                } elseif ($new < $old) {
                    $cards->shift($target, $new, 1, $old - 1);
                }
            } else {
                $new = min(max(0, (int) ($data['position'] ?? 0)), $cards->countInList($target));
                $cards->shift($source, $old + 1, -1);
                $cards->shift($target, $new, 1);
                $card->setList($target);
            }
            $card->setPosition($new);
        });
        if ($source !== $target) {
            $me = $this->me();
            $this->activity->log($card, $me, 'moved', ['from' => $source->getTitle(), 'to' => $target->getTitle()]);
            $notifier->notify($card->getAssignees(), $me, 'card_moved',
                sprintf('%s a déplacé « %s » vers « %s »', $me->getName(), $card->getTitle(), $target->getTitle()), $card->getBoard(), $card);
        }
        $this->em->flush();
        return $this->json(['id' => $card->getId(), 'listId' => $target->getId(), 'position' => $card->getPosition()]);
    }

    /** Archives a card: it leaves its list (the following cards move up) and goes to the board's archives. */
    #[Route('/cards/{id}/archive', methods: ['POST'])]
    public function archive(int $id, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $card = $this->card($id, $access);
        if (!$card->isArchived()) {
            $position = $card->getPosition();
            $this->em->wrapInTransaction(function () use ($card, $cards, $position) {
                $card->setArchivedAt(new \DateTimeImmutable())->setPosition(-1);
                $this->em->flush();
                $cards->shift($card->getList(), $position + 1, -1);
            });
            $this->activity->log($card, $this->me(), 'archived');
            $this->em->flush();
        }
        return $this->json($this->presenter->cardDetail($card));
    }

    /** Restores an archived card at the bottom of its list. */
    #[Route('/cards/{id}/restore', methods: ['POST'])]
    public function restore(int $id, BoardAccess $access, CardRepository $cards): JsonResponse
    {
        $card = $this->card($id, $access);
        if ($card->getList()->isArchived()) {
            throw new BadRequestHttpException(sprintf('La liste « %s » est archivée : restaurez-la d’abord.', $card->getList()->getTitle()));
        }
        if ($card->isArchived()) {
            $card->setPosition($cards->countInList($card->getList()))->setArchivedAt(null);
            $this->activity->log($card, $this->me(), 'unarchived', ['list' => $card->getList()->getTitle()]);
            $this->em->flush();
        }
        return $this->json($this->presenter->cardDetail($card));
    }

    #[Route('/cards/{id}/assignees', methods: ['POST'])]
    public function toggleAssignee(int $id, Request $request, BoardAccess $access, Notifier $notifier): JsonResponse
    {
        $card = $this->card($id, $access);
        $user = $this->find(User::class, (int) ($this->body($request)['userId'] ?? 0));
        if (!$card->getBoard()->getMembership($user)) {
            throw new BadRequestHttpException("Cet utilisateur n'est pas membre du tableau.");
        }
        $me = $this->me();
        if ($card->getAssignees()->contains($user)) {
            $card->getAssignees()->removeElement($user);
            $this->activity->log($card, $me, 'member_removed', ['name' => $user->getName()]);
        } else {
            $card->getAssignees()->add($user);
            $this->activity->log($card, $me, 'member_added', ['name' => $user->getName()]);
            $notifier->notify([$user], $me, 'card_assigned',
                sprintf('%s vous a assigné la carte « %s »', $me->getName(), $card->getTitle()), $card->getBoard(), $card);
        }
        $this->em->flush();
        return $this->json($this->presenter->cardDetail($card));
    }

    #[Route('/cards/{id}/comments', methods: ['POST'])]
    public function comment(int $id, Request $request, BoardAccess $access, Notifier $notifier, RichText $rich, CommentAttachments $files): JsonResponse
    {
        $card = $this->card($id, $access);
        $me = $this->me();
        $content = $rich->clean($this->requireString($this->body($request), 'content', 100000))
            ?? throw new BadRequestHttpException('Le commentaire est vide.');
        $plain = trim(html_entity_decode(strip_tags(str_replace(['</p>', '<br>'], ' ', $content))));
        $comment = new Comment($card, $me, $content);
        $this->em->persist($comment);
        $card->getComments()->add($comment);
        // Files posted in the comment are listed with the card attachments
        $attachments = $files->collect($comment);

        // Notify assignees, the card creator and anyone @mentioned by name
        $recipients = [...$card->getAssignees()->toArray(), $card->getCreatedBy()];
        // @mentions: chips inserted from the "@" list carry the member id; plain "@Name" text still works
        preg_match_all('/data-type="mention"[^>]*data-id="(\d+)"|data-id="(\d+)"[^>]*data-type="mention"/', $content, $m);
        $mentionedIds = array_map('intval', array_filter([...$m[1], ...$m[2]]));
        foreach ($card->getBoard()->getMemberUsers() as $u) {
            if (in_array($u->getId(), $mentionedIds, true) || false !== mb_stripos($plain, '@'.$u->getName())) {
                $recipients[] = $u;
            }
        }
        $notifier->notify(array_filter($recipients), $me, 'card_comment',
            sprintf('%s a commenté « %s » : %s', $me->getName(), $card->getTitle(), mb_strimwidth($plain, 0, 80, '…')), $card->getBoard(), $card);
        $this->em->flush();
        return $this->json([
            ...$this->presenter->comment($comment),
            'attachments' => array_map(fn (Attachment $a) => $this->presenter->attachment($a), $attachments),
        ], 201);
    }

    #[Route('/comments/{id}', methods: ['DELETE'])]
    public function deleteComment(int $id, BoardAccess $access): JsonResponse
    {
        $comment = $this->findForMember(Comment::class, $id, $access);
        $m = $access->memberOfChild($comment->getCard()->getBoard(), $this->me());
        if ($comment->getAuthor() !== $this->me() && !$m->canManage()) {
            throw new BadRequestHttpException('Vous ne pouvez supprimer que vos propres commentaires.');
        }
        $this->em->remove($comment);
        $this->em->flush();
        return $this->json(null, 204);
    }

    #[Route('/cards/{id}/checklist', methods: ['POST'])]
    public function addChecklistItem(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $card = $this->card($id, $access);
        $item = new ChecklistItem($card, $this->requireString($this->body($request), 'content'), $card->getChecklist()->count());
        $this->em->persist($item);
        $this->activity->log($card, $this->me(), 'checklist_added', ['item' => $item->getContent()]);
        $this->em->flush();
        return $this->json($this->presenter->checklistItem($item), 201);
    }

    #[Route('/checklist/{id}', methods: ['PATCH'])]
    public function updateChecklistItem(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $item = $this->findForMember(ChecklistItem::class, $id, $access);
        $data = $this->body($request);
        if (array_key_exists('done', $data) && (bool) $data['done'] !== $item->isDone()) {
            $item->setDone((bool) $data['done']);
            $this->activity->log($item->getCard(), $this->me(), $item->isDone() ? 'checklist_done' : 'checklist_undone', ['item' => $item->getContent()]);
        }
        if (array_key_exists('content', $data)) {
            $item->setContent($this->requireString($data, 'content'));
        }
        $this->em->flush();
        return $this->json($this->presenter->checklistItem($item));
    }

    #[Route('/checklist/{id}', methods: ['DELETE'])]
    public function deleteChecklistItem(int $id, BoardAccess $access): JsonResponse
    {
        $item = $this->findForMember(ChecklistItem::class, $id, $access);
        $this->activity->log($item->getCard(), $this->me(), 'checklist_removed', ['item' => $item->getContent()]);
        $this->em->remove($item);
        $this->em->flush();
        return $this->json(null, 204);
    }

    #[Route('/cards/{id}/custom-fields/{fieldId}', methods: ['PUT'])]
    public function setCustomValue(int $id, int $fieldId, Request $request, BoardAccess $access): JsonResponse
    {
        $card = $this->card($id, $access);
        $field = $this->find(CustomField::class, $fieldId);
        if ($field->getBoard() !== $card->getBoard()) {
            throw new NotFoundHttpException('Champ introuvable sur ce tableau.');
        }
        $value = $this->normalizeValue($field, $this->body($request)['value'] ?? null);

        $existing = null;
        foreach ($card->getCustomValues() as $v) {
            if ($v->getField() === $field) {
                $existing = $v;
            }
        }
        $this->activity->log($card, $this->me(), null === $value ? 'field_cleared' : 'field_set',
            ['field' => $field->getName(), 'value' => null === $value ? null : $this->displayValue($field, $value)]);
        if (null === $value) {
            if ($existing) {
                $card->getCustomValues()->removeElement($existing);
                $this->em->remove($existing);
            }
        } else {
            if (!$existing) {
                $existing = new CustomFieldValue($card, $field);
                $card->getCustomValues()->add($existing);
                $this->em->persist($existing);
            }
            $existing->setValue($value);
        }
        $this->em->flush();
        return $this->json($this->presenter->cardDetail($card));
    }

    /** Card history, newest first: GET /api/cards/{id}/activity?before={activityId}&limit=30 */
    #[Route('/cards/{id}/activity', methods: ['GET'])]
    public function activity(int $id, Request $request, BoardAccess $access, ActivityRepository $repo): JsonResponse
    {
        $card = $this->card($id, $access);
        $limit = max(1, min(100, $request->query->getInt('limit', 30)));
        $before = $request->query->getInt('before') ? $this->em->find(Activity::class, $request->query->getInt('before')) : null;
        $items = $repo->page($card, $before?->getCard() === $card ? $before : null, $limit + 1);
        return $this->json([
            'items' => array_map(fn (Activity $a) => $this->presenter->activity($a), array_slice($items, 0, $limit)),
            'hasMore' => count($items) > $limit,
        ]);
    }

    /** Diff between the snapshot taken before an update and the card now → activity entries. */
    private function logChanges(Card $card, User $me, array $b): void
    {
        $log = fn (string $type, array $data = []) => $this->activity->log($card, $me, $type, $data);
        if ($b['title'] !== $card->getTitle()) {
            $log('renamed', ['from' => $b['title'], 'to' => $card->getTitle()]);
        }
        if ($b['description'] !== $card->getDescription()) {
            $log('description');
        }
        if ($b['due']?->getTimestamp() !== $card->getDueDate()?->getTimestamp()) {
            $card->getDueDate() ? $log('due_set', ['due' => $card->getDueDate()->format(\DateTimeInterface::ATOM)]) : $log('due_removed');
        }
        if ($b['completed'] !== $card->isCompleted()) {
            $log($card->isCompleted() ? 'completed' : 'reopened');
        }
        if ($b['coverAttachment'] !== $card->getCoverAttachment()) {
            $card->getCoverAttachment()
                ? $log('cover_set', ['image' => $card->getCoverAttachment()->getName()])
                : (!$card->getCover() ? $log('cover_removed') : null);
        }
        if ($b['cover'] !== $card->getCover() && ($card->getCover() || !$card->getCoverAttachment())) {
            $log($card->getCover() ? 'cover_set' : 'cover_removed', ['color' => $card->getCover()]);
        }
        if ($b['priority'] !== $card->getPriority()) {
            $log('priority', ['to' => $card->getPriority()]);
        }
        $now = [];
        foreach ($card->getLabels() as $l) {
            $now[$l->getId()] = $l;
            if (!isset($b['labels'][$l->getId()])) {
                $log('label_added', ['name' => $l->getName(), 'color' => $l->getColor()]);
            }
        }
        foreach ($b['labels'] as $lid => $l) {
            if (!isset($now[$lid])) {
                $log('label_removed', ['name' => $l->getName(), 'color' => $l->getColor()]);
            }
        }
    }

    private function displayValue(CustomField $field, mixed $value): string
    {
        return match ($field->getType()) {
            'select' => (string) (array_column($field->getOptions(), 'label', 'id')[$value] ?? $value),
            'checkbox' => $value ? 'Oui' : 'Non',
            'date' => (new \DateTimeImmutable((string) $value))->format('d/m/Y'),
            default => mb_strimwidth((string) $value, 0, 80, '…'),
        };
    }

    private function normalizeValue(CustomField $field, mixed $value): mixed
    {
        if (null === $value || '' === $value) {
            return null;
        }
        return match ($field->getType()) {
            'number' => is_numeric($value) ? $value + 0 : throw new BadRequestHttpException('Valeur numérique attendue.'),
            'checkbox' => (bool) $value,
            'date' => (new \DateTimeImmutable((string) $value))->format('Y-m-d'),
            'select' => in_array($value, array_column($field->getOptions(), 'id'), true) ? $value : throw new BadRequestHttpException('Option inconnue.'),
            'url' => filter_var($value, FILTER_VALIDATE_URL) ? $value : throw new BadRequestHttpException('URL invalide.'),
            default => mb_substr((string) $value, 0, 2000),
        };
    }
}
