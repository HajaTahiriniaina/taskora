<?php

namespace App\Service;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\BoardMember;
use App\Entity\Card;
use App\Entity\ChecklistItem;
use App\Entity\Comment;
use App\Entity\Label;
use App\Entity\TrelloImportFile;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * Imports a Trello board JSON export (Menu → Imprimer, exporter et partager → Exporter au format JSON).
 *
 * Kept: lists, cards with their Trello number (idShort), description, due date, completion, cover color,
 * labels, checklists, comments (with original author and date), member assignment
 * when the Trello member matches a member of the importer's boards by full name.
 * Archived cards are imported archived; archived lists (and their cards) are skipped. Trello exports contain no e-mail addresses, so unmatched members
 * are only reported.
 *
 * Files uploaded on Trello are recorded as TrelloImportFile and copied afterwards into Taskora
 * (TrelloFileTransfer, driven by the importer's browser with a Trello key / token kept in memory).
 * Plain link attachments stay links. Links to other cards of the board ("trello.com/c/…") are
 * rewritten to the Taskora cards.
 */
class TrelloImporter
{
    private const COLORS = [
        'green' => '#4bce97', 'yellow' => '#e2b203', 'orange' => '#fea362', 'red' => '#f87168', 'purple' => '#9f8fef',
        'blue' => '#579dff', 'sky' => '#6cc3e0', 'lime' => '#94c748', 'pink' => '#e774bb', 'black' => '#8590a2',
        'green_dark' => '#1f845a', 'yellow_dark' => '#946f00', 'orange_dark' => '#c25100', 'red_dark' => '#c9372c',
        'purple_dark' => '#6e5dc6', 'blue_dark' => '#0c66e4', 'sky_dark' => '#227d9b', 'lime_dark' => '#5b7f24',
        'pink_dark' => '#ae4787', 'black_dark' => '#626f86',
        'green_light' => '#7ee2b8', 'yellow_light' => '#f5cd47', 'orange_light' => '#fec195', 'red_light' => '#fd9891',
        'purple_light' => '#b8acf6', 'blue_light' => '#85b8ff', 'sky_light' => '#9dd9ee', 'lime_light' => '#b3df72',
        'pink_light' => '#f797d2', 'black_light' => '#b3b9c4',
    ];
    private const BACKGROUNDS = ['blue' => 'ocean', 'orange' => 'sunset', 'green' => 'forest', 'red' => 'berry', 'purple' => 'aurora',
        'pink' => 'peach', 'lime' => 'lagoon', 'sky' => 'lagoon', 'grey' => 'graphite'];

    public function __construct(private EntityManagerInterface $em, private ActivityLogger $activity) {}

    /** @return array{board: Board, report: array<string, mixed>} */
    public function import(array $data, User $owner): array
    {
        if (!isset($data['name'], $data['lists'], $data['cards']) || !is_array($data['lists']) || !is_array($data['cards'])) {
            throw new BadRequestHttpException("Ce fichier n'est pas un export JSON de tableau Trello.");
        }

        return $this->em->wrapInTransaction(function () use ($data, $owner) {
            $board = new Board($owner, mb_substr(trim((string) $data['name']) ?: 'Import Trello', 0, 160));
            $board->setDescription(trim((string) ($data['desc'] ?? '')) ?: null);
            $board->setBackground(self::BACKGROUNDS[$data['prefs']['background'] ?? ''] ?? 'midnight');
            $board->addMember(new BoardMember($board, $owner, BoardMember::ROLE_OWNER));
            $this->em->persist($board);

            // ---- Members: match by full name with people the importer already works with
            $known = $this->knownUsers($owner);
            $memberMap = [];
            $unmatched = [];
            foreach ($data['members'] ?? [] as $m) {
                $user = $known[$this->norm($m['fullName'] ?? '')] ?? null;
                if ($user) {
                    $memberMap[$m['id']] = $user;
                    if (!$board->getMembership($user)) {
                        $board->addMember(new BoardMember($board, $user));
                    }
                } else {
                    $unmatched[] = $m['fullName'] ?? $m['username'] ?? '?';
                }
            }
            $memberNames = [];
            foreach ($data['members'] ?? [] as $m) {
                $memberNames[$m['id']] = $m['fullName'] ?? $m['username'] ?? 'Membre Trello';
            }

            // ---- Labels
            $labelMap = [];
            foreach ($data['labels'] ?? [] as $l) {
                $label = new Label($board, mb_substr((string) ($l['name'] ?? ''), 0, 60), self::COLORS[$l['color'] ?? ''] ?? '#94a3b8');
                $this->em->persist($label);
                $labelMap[$l['id']] = $label;
            }

            // ---- Lists (open only, Trello order)
            // All lists, archived ones imported archived (Taskora board archives)
            $openLists = array_values($data['lists']);
            usort($openLists, fn ($a, $b) => ($a['pos'] ?? 0) <=> ($b['pos'] ?? 0));
            $listMap = [];
            foreach ($openLists as $i => $l) {
                $list = new BoardList($board, mb_substr((string) $l['name'], 0, 160) ?: 'Liste', $i);
                if (!empty($l['closed'])) {
                    $list->setArchivedAt(new \DateTimeImmutable());
                }
                $this->em->persist($list);
                $listMap[$l['id']] = $list;
            }

            // ---- Cards (open cards in open lists), Trello numbers preserved
            $maxNumber = 0;
            foreach ($data['cards'] as $c) {
                $maxNumber = max($maxNumber, (int) ($c['idShort'] ?? 0));
            }
            // Cards of open lists; archived ones are imported archived (Taskora board archives)
            $cards = array_values(array_filter($data['cards'], fn ($c) => isset($listMap[$c['idList'] ?? ''])));
            usort($cards, fn ($a, $b) => [$a['idList'], $a['pos'] ?? 0] <=> [$b['idList'], $b['pos'] ?? 0]);
            $checklistsByCard = [];
            foreach ($data['checklists'] ?? [] as $cl) {
                $checklistsByCard[$cl['idCard']][] = $cl;
            }

            $cardMap = [];
            $positions = [];
            $nextFree = $maxNumber;
            $assigned = 0;
            $attachments = 0;
            $files = 0;
            $filesBytes = 0;
            $archivedCards = 0;
            $malicious = 0;
            $shortLinks = [];
            foreach ($cards as $c) {
                $list = $listMap[$c['idList']];
                $archived = !empty($c['closed']);
                $pos = $archived ? -1 : ($positions[$c['idList']] = ($positions[$c['idList']] ?? -1) + 1);
                $number = (int) ($c['idShort'] ?? 0) ?: ++$nextFree;
                $card = new Card($list, mb_substr((string) $c['name'], 0, 255) ?: 'Sans titre', $pos, null, $number);
                $card->setCreatedAt($this->dateFromId($c['id'] ?? ''));
                if ($archived) {
                    $card->setArchivedAt(new \DateTimeImmutable($c['dateLastActivity'] ?? 'now'));
                    ++$archivedCards;
                }

                $desc = trim((string) ($c['desc'] ?? ''));
                $links = [];
                $uploads = [];
                foreach ($c['attachments'] ?? [] as $a) {
                    if (empty($a['url'])) {
                        continue;
                    }
                    if (!empty($a['isUpload']) && TrelloFileTransfer::isTrelloFileUrl($a['url'])) {
                        if (!empty($a['isMalicious'])) { // flagged by Trello: never copied
                            ++$malicious;
                        } else {
                            $uploads[] = $a;
                        }
                        continue;
                    }
                    $links[] = sprintf('- [%s](%s)', $a['name'] ?? 'Lien', $a['url']);
                    ++$attachments;
                }
                if ($links) {
                    $desc .= ($desc ? "\n\n---\n" : '')."Liens (Trello) :\n".implode("\n", $links);
                }
                $card->setDescription($desc ?: null);
                if (!empty($c['due'])) {
                    $card->setDueDate(new \DateTimeImmutable($c['due']));
                }
                $card->setCompleted(!empty($c['dueComplete']));
                if (!empty($c['cover']['color'])) {
                    $card->setCover(self::COLORS[$c['cover']['color']] ?? null);
                }
                foreach ($c['idLabels'] ?? [] as $lid) {
                    if (isset($labelMap[$lid])) {
                        $card->getLabels()->add($labelMap[$lid]);
                    }
                }
                foreach ($c['idMembers'] ?? [] as $mid) {
                    if (isset($memberMap[$mid]) && !$card->getAssignees()->contains($memberMap[$mid])) {
                        $card->getAssignees()->add($memberMap[$mid]);
                        ++$assigned;
                    }
                }
                $i = 0;
                foreach ($checklistsByCard[$c['id']] ?? [] as $cl) {
                    $items = $cl['checkItems'] ?? [];
                    usort($items, fn ($a, $b) => ($a['pos'] ?? 0) <=> ($b['pos'] ?? 0));
                    foreach ($items as $it) {
                        $item = new ChecklistItem($card, mb_substr((string) $it['name'], 0, 255), $i++);
                        $item->setDone('complete' === ($it['state'] ?? ''));
                        $this->em->persist($item);
                    }
                }
                $this->em->persist($card);
                $cardMap[$c['id']] = $card;
                if (!empty($c['shortLink'])) {
                    $shortLinks[$c['shortLink']] = $card;
                }
                // Files uploaded on Trello: copied after the import (see TrelloFileTransfer)
                foreach ($uploads as $a) {
                    $this->em->persist(new TrelloImportFile(
                        $board, $card, (string) $a['id'], (string) $a['url'],
                        (string) ($a['name'] ?? $a['fileName'] ?? 'fichier'), (int) ($a['bytes'] ?? 0),
                        new \DateTimeImmutable($a['date'] ?? 'now'), $memberMap[$a['idMember'] ?? ''] ?? null,
                        ($c['cover']['idAttachment'] ?? null) === $a['id'],
                    ));
                    ++$files;
                    $filesBytes += (int) ($a['bytes'] ?? 0);
                }
            }

            // ---- Comments (only the ones present in the export's action history)
            $comments = 0;
            $commentEntities = [];
            foreach ($data['actions'] ?? [] as $a) {
                if ('commentCard' !== ($a['type'] ?? '') || !isset($cardMap[$a['data']['card']['id'] ?? ''])) {
                    continue;
                }
                $authorId = $a['idMemberCreator'] ?? '';
                $user = $memberMap[$authorId] ?? null;
                $comment = new Comment($cardMap[$a['data']['card']['id']], $user ?? $owner, (string) ($a['data']['text'] ?? ''));
                if (!$user) {
                    $comment->setExternalAuthor($a['memberCreator']['fullName'] ?? $memberNames[$authorId] ?? 'Membre Trello');
                }
                $comment->setCreatedAt(new \DateTimeImmutable($a['date'] ?? 'now'));
                $this->em->persist($comment);
                $commentEntities[] = $comment;
                ++$comments;
            }

            // ---- History (Trello actions → Taskora activity), within the export's 1000 last actions
            $history = 0;
            foreach ($data['actions'] ?? [] as $a) {
                $card = $cardMap[$a['data']['card']['id'] ?? ''] ?? null;
                if (!$card) {
                    continue;
                }
                $entries = $this->historyEntries($a, $memberNames);
                if (!$entries) {
                    continue;
                }
                $authorId = $a['idMemberCreator'] ?? '';
                $user = $memberMap[$authorId] ?? null;
                $external = $user ? null : ($a['memberCreator']['fullName'] ?? $memberNames[$authorId] ?? 'Membre Trello');
                foreach ($entries as [$type, $payload]) {
                    $this->activity->log($card, $user, $type, $payload, new \DateTimeImmutable($a['date'] ?? 'now'), $external);
                    ++$history;
                }
            }

            $board->setCardSeq($maxNumber);
            $this->em->flush();

            // ---- Links to cards of this board ("https://trello.com/c/<shortLink>/…") → Taskora cards
            $cardLinks = 0;
            $rewrite = function (?string $text) use ($shortLinks, $board, &$cardLinks): ?string {
                if (null === $text || !str_contains($text, 'trello.com/c/')) {
                    return $text;
                }
                return preg_replace_callback('#https?://trello\.com/c/([A-Za-z0-9]{8})(?:/[^\s)\]"\'<>]*)?#', function ($m) use ($shortLinks, $board, &$cardLinks) {
                    if (!isset($shortLinks[$m[1]])) {
                        return $m[0]; // card of another board, or archived
                    }
                    ++$cardLinks;
                    return sprintf('/boards/%d?card=%d', $board->getId(), $shortLinks[$m[1]]->getId());
                }, $text);
            };
            foreach ($cardMap as $card) {
                $card->setDescription($rewrite($card->getDescription()));
            }
            foreach ($commentEntities as $comment) {
                $comment->setContent((string) $rewrite($comment->getContent()));
            }
            $this->em->flush();

            $expectedComments = array_sum(array_map(fn ($c) => (int) ($c['badges']['comments'] ?? 0), $cards));

            return ['board' => $board, 'report' => [
                'lists' => count($listMap),
                'cards' => count($cardMap),
                'labels' => count($labelMap),
                'comments' => $comments,
                'history' => $history,
                'commentsInTrello' => $expectedComments,
                'attachments' => $attachments,
                'files' => $files,
                'filesBytes' => $filesBytes,
                'filesMalicious' => $malicious,
                'cardLinks' => $cardLinks,
                'assignments' => $assigned,
                'archivedLists' => count(array_filter($data['lists'], fn ($l) => !empty($l['closed']))),
                'skippedArchivedLists' => 0,
                'archivedCards' => $archivedCards,
                'skippedArchivedCards' => count($data['cards']) - count($cardMap), // cards of unknown lists
                'membersMatched' => array_values(array_unique(array_map(fn (User $u) => $u->getName(), $memberMap))),
                'membersUnmatched' => $unmatched,
                'nextCardNumber' => $maxNumber + 1,
            ]];
        });
    }

    /**
     * Maps one Trello action to zero or more activity entries [type, data].
     * @return array<int, array{0: string, 1: array}>
     */
    private function historyEntries(array $a, array $memberNames): array
    {
        $d = $a['data'] ?? [];
        $old = $d['old'] ?? [];
        $out = [];
        switch ($a['type'] ?? '') {
            case 'createCard':
                $out[] = ['created', ['list' => $d['list']['name'] ?? '']];
                break;
            case 'updateCard':
                if (isset($d['listBefore'], $d['listAfter'])) {
                    $out[] = ['moved', ['from' => $d['listBefore']['name'] ?? '', 'to' => $d['listAfter']['name'] ?? '']];
                }
                if (array_key_exists('dueComplete', $old)) {
                    $out[] = [!empty($d['card']['dueComplete']) ? 'completed' : 'reopened', []];
                }
                if (array_key_exists('due', $old)) {
                    $out[] = empty($d['card']['due']) ? ['due_removed', []] : ['due_set', ['due' => $d['card']['due']]];
                }
                if (array_key_exists('name', $old)) {
                    $out[] = ['renamed', ['from' => $old['name'], 'to' => $d['card']['name'] ?? '']];
                }
                if (array_key_exists('desc', $old)) {
                    $out[] = ['description', []];
                }
                break;
            case 'addMemberToCard':
            case 'removeMemberFromCard':
                $name = $a['member']['fullName'] ?? $memberNames[$d['idMember'] ?? ''] ?? 'un membre';
                $out[] = ['addMemberToCard' === $a['type'] ? 'member_added' : 'member_removed', ['name' => $name]];
                break;
            case 'addAttachmentToCard':
                $out[] = ['attachment', ['name' => $d['attachment']['name'] ?? 'pièce jointe']];
                break;
            case 'addLabelToCard':
            case 'removeLabelFromCard':
                $out[] = ['addLabelToCard' === $a['type'] ? 'label_added' : 'label_removed',
                    ['name' => $d['label']['name'] ?? $d['text'] ?? '', 'color' => self::COLORS[$d['label']['color'] ?? ''] ?? '#94a3b8']];
                break;
            case 'updateCheckItemStateOnCard':
                $out[] = [('complete' === ($d['checkItem']['state'] ?? '')) ? 'checklist_done' : 'checklist_undone', ['item' => $d['checkItem']['name'] ?? '']];
                break;
        }
        return $out;
    }

    /** @return array<string, User> normalized full name → user, among people sharing a board with the owner */
    private function knownUsers(User $owner): array
    {
        $users = $this->em->createQuery(
            'SELECT DISTINCT u FROM App\Entity\User u JOIN App\Entity\BoardMember m WITH m.user = u
             WHERE m.board IN (SELECT IDENTITY(m2.board) FROM App\Entity\BoardMember m2 WHERE m2.user = :me)')
            ->setParameter('me', $owner)->getResult();
        $out = [$this->norm($owner->getName()) => $owner];
        foreach ($users as $u) {
            $out[$this->norm($u->getName())] = $u;
        }
        return $out;
    }

    private function norm(string $name): string
    {
        $n = mb_strtolower(trim($name));
        return (string) preg_replace('/\s+/', ' ', iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $n) ?: $n);
    }

    /** Trello ids embed their creation timestamp in the first 8 hex chars. */
    private function dateFromId(string $id): \DateTimeImmutable
    {
        return preg_match('/^[0-9a-f]{8}/', $id) ? (new \DateTimeImmutable())->setTimestamp(hexdec(substr($id, 0, 8))) : new \DateTimeImmutable();
    }
}
