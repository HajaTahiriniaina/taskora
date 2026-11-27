<?php

namespace App\Tests\Controller;

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
use App\Entity\TrelloImportFile;
use App\Entity\User;
use App\Tests\Support\ApiTestCase;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\TestDox;

/**
 * U8-a (SC-BOARD-021, SC-BOARD-006, SC-BOARD-034) — BoardAccess de Symfony passe en 404 : un
 * non-membre reçoit exactement la réponse d'une ressource inexistante, sur les routes T1 et T2–T5
 * encore servies par Symfony, pièces jointes et fichiers Trello compris (spec phase 3 §5.3) :
 * « Tableau introuvable. » pour /api/boards/{id}/**, « Ressource introuvable. » pour les ressources
 * enfants, « Cet élément n’est pas dans la corbeille. » / « Ce tableau n’est pas dans la corbeille. »
 * pour /api/trash/**. Plus aucune fuite « dans la corbeille » pour un non-membre ; messages actuels
 * pour un membre ; 403 de gestion inchangés ; aucune écriture.
 */
final class BoardAccess404Test extends ApiTestCase
{
    private const MISSING = 999999;
    private const BOARD_NOT_FOUND = 'Tableau introuvable.';
    private const RESOURCE_NOT_FOUND = 'Ressource introuvable.';
    private const ITEM_NOT_IN_TRASH = 'Cet élément n’est pas dans la corbeille.';
    private const BOARD_NOT_IN_TRASH = 'Ce tableau n’est pas dans la corbeille.';

    private User $alice;
    private User $bob;
    private User $carla;
    private User $nadia;
    /** @var array<string, int> */
    private array $ids = [];

    protected function setUp(): void
    {
        parent::setUp();
        $this->alice = $this->user('alice@exemple.test', 'Alice');
        $this->bob = $this->user('bob@exemple.test', 'Bob');
        $this->carla = $this->user('carla@exemple.test', 'Carla');
        $this->nadia = $this->user('nadia@exemple.test', 'Nadia');

        $b = $this->board($this->alice, 'Lancement');
        $b->addMember(new BoardMember($b, $this->bob, BoardMember::ROLE_ADMIN));
        $b->addMember(new BoardMember($b, $this->carla));
        $list = new BoardList($b, 'À faire', 0);
        $trashedList = (new BoardList($b, 'Supprimée', 1))->trash($this->alice);
        $card = new Card($list, 'Carte', 0, $this->alice, 1);
        $trashedCard = (new Card($list, 'Carte supprimée', 1, $this->alice, 2))->trash($this->alice);
        $cardInTrashedList = new Card($trashedList, 'Dans liste supprimée', 0, $this->alice, 3);
        $label = new Label($b, 'Urgent', '#ef4444');
        $field = new CustomField($b, 'Estimation', 'number', 0);
        $item = new ChecklistItem($card, 'Vérifier', 0);
        $comment = new Comment($card, $this->carla, '<p>Bonjour</p>');
        $attachment = new Attachment($card, $this->alice, 'plan.pdf', 'uploads/test/plan.pdf', 'application/pdf', 10);
        $invitation = new Invitation($b, 'invite@exemple.test', $this->alice);
        $trello = new TrelloImportFile($b, $card, 'trello-0001', 'https://trello.exemple.test/a.png', 'a.png', 10, new \DateTimeImmutable('2026-07-14 09:15:00'), $this->alice, false);

        $t = $this->board($this->alice, 'Ancien');
        $t->addMember(new BoardMember($t, $this->bob, BoardMember::ROLE_ADMIN));
        $t->addMember(new BoardMember($t, $this->carla));
        $listOfTrashedBoard = new BoardList($t, 'Vieille liste', 0);
        $cardOfTrashedBoard = new Card($listOfTrashedBoard, 'Vieille carte', 0, $this->alice, 1);
        $t->trash($this->alice);

        foreach ([$list, $trashedList, $card, $trashedCard, $cardInTrashedList, $label, $field, $item, $comment, $attachment, $invitation, $trello, $listOfTrashedBoard, $cardOfTrashedBoard] as $o) {
            $this->em->persist($o);
        }
        $this->em->flush();
        $this->ids = [
            'B' => $b->getId(), 'T' => $t->getId(), 'L' => $list->getId(), 'LTr' => $trashedList->getId(),
            'C' => $card->getId(), 'CTr' => $trashedCard->getId(), 'CinLTr' => $cardInTrashedList->getId(),
            'Lb' => $label->getId(), 'F' => $field->getId(), 'I' => $item->getId(), 'Co' => $comment->getId(),
            'A' => $attachment->getId(), 'Inv' => $invitation->getId(), 'LT' => $listOfTrashedBoard->getId(),
            'CT' => $cardOfTrashedBoard->getId(), 'carla' => $this->carla->getId(),
        ];
    }

    private function path(string $template, bool $missing): string
    {
        // Seul le premier identifiant (la ressource visée) est remplacé par un inexistant.
        $template = $missing ? preg_replace('/\{\w+\}/', (string) self::MISSING, $template, 1) : $template;

        return preg_replace_callback('/\{(\w+)\}/', fn (array $m): string => (string) $this->ids[$m[1]], $template);
    }

    /** @return iterable<string, array{string, string, string, ?string}> */
    public static function nonMemberRoutes(): iterable
    {
        $board = self::BOARD_NOT_FOUND;
        $res = self::RESOURCE_NOT_FOUND;
        $json = '{"title":"x","email":"dora@exemple.test","role":"admin","ids":[],"name":"x","color":"#000000","type":"text","content":"x","listId":1,"position":0,"userId":1}';
        // T1 (avant la bascule) et T2–T5, pièces jointes, fichiers Trello : toutes les routes Symfony du tableau.
        yield 'GET board' => ['GET', '/api/boards/{B}', $board, null];
        yield 'PATCH board' => ['PATCH', '/api/boards/{B}', $board, $json];
        yield 'DELETE board' => ['DELETE', '/api/boards/{B}', $board, null];
        yield 'POST favorite' => ['POST', '/api/boards/{B}/favorite', $board, null];
        yield 'POST members' => ['POST', '/api/boards/{B}/members', $board, $json];
        yield 'PATCH member' => ['PATCH', '/api/boards/{B}/members/{carla}', $board, $json];
        yield 'DELETE member' => ['DELETE', '/api/boards/{B}/members/{carla}', $board, null];
        yield 'DELETE invitation' => ['DELETE', '/api/boards/{B}/invitations/{Inv}', $board, null];
        yield 'GET board cards' => ['GET', '/api/boards/{B}/cards?lists={L}', $board, null];
        yield 'GET archived-lists' => ['GET', '/api/boards/{B}/archived-lists', $board, null];
        yield 'GET archived' => ['GET', '/api/boards/{B}/archived', $board, null];
        yield 'POST lists' => ['POST', '/api/boards/{B}/lists', $board, $json];
        yield 'POST lists/reorder' => ['POST', '/api/boards/{B}/lists/reorder', $board, $json];
        yield 'POST labels' => ['POST', '/api/boards/{B}/labels', $board, $json];
        yield 'POST custom-fields' => ['POST', '/api/boards/{B}/custom-fields', $board, $json];
        yield 'GET board trash' => ['GET', '/api/boards/{B}/trash', $board, null];
        yield 'GET trello-files' => ['GET', '/api/boards/{B}/trello-files', $board, null];
        yield 'POST trello-files/transfer' => ['POST', '/api/boards/{B}/trello-files/transfer', $board, $json];
        yield 'POST trello-files/retry' => ['POST', '/api/boards/{B}/trello-files/retry', $board, null];
        yield 'DELETE trello-files' => ['DELETE', '/api/boards/{B}/trello-files', $board, null];
        yield 'PATCH list' => ['PATCH', '/api/lists/{L}', $res, $json];
        yield 'DELETE list' => ['DELETE', '/api/lists/{L}', $res, null];
        yield 'POST list archive' => ['POST', '/api/lists/{L}/archive', $res, null];
        yield 'POST list restore' => ['POST', '/api/lists/{L}/restore', $res, null];
        yield 'GET list cards' => ['GET', '/api/lists/{L}/cards', $res, null];
        yield 'POST list cards' => ['POST', '/api/lists/{L}/cards', $res, $json];
        yield 'PATCH label' => ['PATCH', '/api/labels/{Lb}', $res, $json];
        yield 'DELETE label' => ['DELETE', '/api/labels/{Lb}', $res, null];
        yield 'PATCH custom-field' => ['PATCH', '/api/custom-fields/{F}', $res, $json];
        yield 'DELETE custom-field' => ['DELETE', '/api/custom-fields/{F}', $res, null];
        yield 'GET card' => ['GET', '/api/cards/{C}', $res, null];
        yield 'PATCH card' => ['PATCH', '/api/cards/{C}', $res, $json];
        yield 'DELETE card' => ['DELETE', '/api/cards/{C}', $res, null];
        yield 'POST card move' => ['POST', '/api/cards/{C}/move', $res, $json];
        yield 'POST card archive' => ['POST', '/api/cards/{C}/archive', $res, null];
        yield 'POST card restore' => ['POST', '/api/cards/{C}/restore', $res, null];
        yield 'POST card assignees' => ['POST', '/api/cards/{C}/assignees', $res, $json];
        yield 'POST card comments' => ['POST', '/api/cards/{C}/comments', $res, $json];
        yield 'POST card checklist' => ['POST', '/api/cards/{C}/checklist', $res, $json];
        yield 'GET card activity' => ['GET', '/api/cards/{C}/activity', $res, null];
        yield 'PUT card custom-field' => ['PUT', '/api/cards/{C}/custom-fields/{F}', $res, '{"value":"3"}'];
        yield 'PATCH checklist' => ['PATCH', '/api/checklist/{I}', $res, $json];
        yield 'DELETE checklist' => ['DELETE', '/api/checklist/{I}', $res, null];
        yield 'DELETE comment' => ['DELETE', '/api/comments/{Co}', $res, null];
        yield 'GET card attachments' => ['GET', '/api/cards/{C}/attachments', $res, null];
        yield 'POST card attachments' => ['POST', '/api/cards/{C}/attachments', $res, null];
        yield 'PATCH attachment' => ['PATCH', '/api/attachments/{A}', $res, $json];
        yield 'DELETE attachment' => ['DELETE', '/api/attachments/{A}', $res, null];
        yield 'GET attachment content' => ['GET', '/api/attachments/{A}/content', $res, null];
        yield 'GET attachment preview' => ['GET', '/api/attachments/{A}/preview', $res, null];
        yield 'POST trash card restore' => ['POST', '/api/trash/cards/{CTr}/restore', self::ITEM_NOT_IN_TRASH, null];
        yield 'DELETE trash card' => ['DELETE', '/api/trash/cards/{CTr}', self::ITEM_NOT_IN_TRASH, null];
        yield 'POST trash list restore' => ['POST', '/api/trash/lists/{LTr}/restore', self::ITEM_NOT_IN_TRASH, null];
        yield 'DELETE trash list' => ['DELETE', '/api/trash/lists/{LTr}', self::ITEM_NOT_IN_TRASH, null];
        yield 'POST trash board restore' => ['POST', '/api/trash/boards/{T}/restore', self::BOARD_NOT_IN_TRASH, null];
        yield 'DELETE trash board' => ['DELETE', '/api/trash/boards/{T}', self::BOARD_NOT_IN_TRASH, null];
    }

    #[DataProvider('nonMemberRoutes')]
    #[TestDox('U8-a / SC-BOARD-021 — non-membre ≡ inexistant (statut, corps), 404 du §5.3, aucune écriture : $method $template')]
    public function testNonMemberGetsNotFound(string $method, string $template, string $message, ?string $body): void
    {
        $before = $this->fingerprint();
        [$status, $json, $type] = $this->api($method, $this->path($template, false), $this->nadia, $body);
        [$missingStatus, $missingJson, $missingType] = $this->api($method, $this->path($template, true), $this->nadia, $body);

        self::assertSame(404, $status, sprintf('%s %s : %s', $method, $template, json_encode($json)));
        self::assertSame(['error' => $message], $json);
        self::assertSame([$missingStatus, $missingJson, $missingType], [$status, $json, $type], 'non-membre ≡ inexistant');
        self::assertSame($before, $this->fingerprint(), 'aucune écriture');
    }

    /** @return iterable<string, array{string, string}> */
    public static function nonMemberTrashedElements(): iterable
    {
        yield 'carte dans la corbeille' => ['/api/cards/{CTr}', self::RESOURCE_NOT_FOUND];
        yield 'carte d\'une liste dans la corbeille' => ['/api/cards/{CinLTr}', self::RESOURCE_NOT_FOUND];
        yield 'liste d\'un tableau dans la corbeille' => ['/api/lists/{LT}/cards', self::RESOURCE_NOT_FOUND];
        yield 'carte d\'un tableau dans la corbeille' => ['/api/cards/{CT}', self::RESOURCE_NOT_FOUND];
        yield 'tableau dans la corbeille' => ['/api/boards/{T}', self::BOARD_NOT_FOUND];
        yield 'cartes d\'un tableau dans la corbeille' => ['/api/boards/{T}/archived', self::BOARD_NOT_FOUND];
    }

    #[DataProvider('nonMemberTrashedElements')]
    #[TestDox('SC-BOARD-021 — non-membre + élément dans la corbeille → message du non-membre (plus de fuite « dans la corbeille ») : $template')]
    public function testNoTrashLeakForNonMember(string $template, string $message): void
    {
        [$status, $json] = $this->api('GET', $this->path($template, false), $this->nadia);
        self::assertSame(404, $status);
        self::assertSame(['error' => $message], $json);
    }

    /** @return iterable<string, array{string, string}> */
    public static function memberTrashedElements(): iterable
    {
        yield 'carte dans la corbeille' => ['/api/cards/{CTr}', 'Cet élément est dans la corbeille.'];
        yield 'carte d\'une liste dans la corbeille' => ['/api/cards/{CinLTr}', 'Cet élément est dans la corbeille.'];
        yield 'liste d\'un tableau dans la corbeille' => ['/api/lists/{LT}/cards', 'Cet élément est dans la corbeille.'];
        yield 'tableau dans la corbeille' => ['/api/boards/{T}', 'Ce tableau est dans la corbeille.'];
        yield 'archives d\'un tableau dans la corbeille' => ['/api/boards/{T}/archived', 'Ce tableau est dans la corbeille.'];
    }

    #[DataProvider('memberTrashedElements')]
    #[TestDox('SC-BOARD-034 — membre : messages actuels « dans la corbeille » conservés : $template')]
    public function testMemberKeepsCurrentMessages(string $template, string $message): void
    {
        [$status, $json] = $this->api('GET', $this->path($template, false), $this->carla);
        self::assertSame(404, $status);
        self::assertSame(['error' => $message], $json);
    }

    #[TestDox('SC-BOARD-010 — gestion par un simple membre → 403 inchangés ; propriétaire seul pour la corbeille')]
    public function testManagementForbiddenUnchanged(): void
    {
        [$status, $json] = $this->api('POST', $this->path('/api/boards/{B}/members', false), $this->carla, '{"email":"dora@exemple.test"}');
        self::assertSame([403, ['error' => 'Action réservée aux administrateurs du tableau.']], [$status, $json]);
        [$status, $json] = $this->api('DELETE', $this->path('/api/boards/{B}', false), $this->bob);
        self::assertSame([403, ['error' => 'Seul le propriétaire peut supprimer le tableau.']], [$status, $json]);
        [$status, $json] = $this->api('POST', $this->path('/api/trash/boards/{T}/restore', false), $this->bob);
        self::assertSame([403, ['error' => 'Seul le propriétaire du tableau peut le restaurer ou le supprimer.']], [$status, $json]);
        [$status, $json] = $this->api('DELETE', $this->path('/api/trash/cards/{CTr}', false), $this->carla);
        self::assertSame([403, ['error' => 'Seuls les administrateurs du tableau peuvent supprimer définitivement.']], [$status, $json]);
    }

    #[TestDox('SC-BOARD-003 — membre : accès inchangé (non-régression)')]
    public function testMemberStillReads(): void
    {
        [$status] = $this->api('GET', $this->path('/api/boards/{B}', false), $this->carla);
        self::assertSame(200, $status);
        [$status] = $this->api('GET', $this->path('/api/cards/{C}', false), $this->carla);
        self::assertSame(200, $status);
        [$status] = $this->api('GET', $this->path('/api/cards/{C}/attachments', false), $this->carla);
        self::assertSame(200, $status);
    }

    #[TestDox('SC-BOARD-021 — membre retiré : réponse du non-membre dès la requête suivante')]
    public function testRemovedMember(): void
    {
        [$status] = $this->api('DELETE', $this->path('/api/boards/{B}/members/{carla}', false), $this->alice);
        self::assertSame(204, $status);
        [$status, $json] = $this->api('GET', $this->path('/api/cards/{C}', false), $this->carla);
        self::assertSame([404, ['error' => self::RESOURCE_NOT_FOUND]], [$status, $json]);
        [$status, $json] = $this->api('GET', $this->path('/api/boards/{B}', false), $this->carla);
        self::assertSame([404, ['error' => self::BOARD_NOT_FOUND]], [$status, $json]);
    }
}
