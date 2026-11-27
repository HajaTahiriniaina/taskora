<?php

namespace App\Tests\Controller;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\Card;
use App\Entity\Notification;
use App\Entity\User;
use App\Service\PushSender;
use App\Tests\Support\ApiTestCase;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\Attributes\TestDox;

/**
 * SC-BOARD-025 (U7-a, B-ADR-5) — POST /internal/notifications : contrat symfony.internal.yaml
 * (corps NotificationRequested de board-service.internal.yaml). Secret INTERNAL_SECRET_BOARD_TO_SYMFONY
 * (U12) ; règles de Notifier (jamais l'acteur, sans doublon, destinataire inconnu ignoré) ; tableau ou
 * carte supprimé → created 0 ; idempotence par eventId (table internal_event_receipt, même
 * transaction) ; Web Push mis en file avec le titre, l'URL et l'étiquette actuels.
 */
final class InternalNotificationsTest extends ApiTestCase
{
    private const URL = '/internal/notifications';
    private const EVENT = '3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c';

    private User $alice;
    private User $carla;
    private User $dora;
    private Board $board;
    private Card $card;
    /** Seconde connexion DBAL de TB11 (livraison concurrente), fermée en tearDown. */
    private ?Connection $concurrent = null;
    private ?string $concurrentEventId = null;

    protected function setUp(): void
    {
        parent::setUp();
        $this->alice = $this->user('alice@exemple.test', 'Alice Martin');
        $this->carla = $this->user('carla@exemple.test', 'Carla');
        $this->dora = $this->user('dora@exemple.test', 'Dora');
        $this->board = $this->board($this->alice, 'Lancement produit');
        $list = new BoardList($this->board, 'À faire', 0);
        $this->card = new Card($list, 'Carte', 0, $this->alice, 1);
        $this->em->persist($list);
        $this->em->persist($this->card);
        $this->em->flush();
    }

    /** @return array{0: int, 1: mixed} */
    private function post(array|string $body, ?string $secret = self::BOARD_SECRET): array
    {
        $headers = null === $secret ? [] : ['X-Internal-Secret' => $secret];
        [$status, $json] = $this->api('POST', self::URL, null, is_string($body) ? $body : json_encode($body, JSON_UNESCAPED_UNICODE), $headers);

        return [$status, $json];
    }

    private function body(array $overrides = []): array
    {
        return array_merge([
            'eventId' => self::EVENT,
            'type' => 'board_invite',
            'actorId' => $this->alice->getId(),
            'recipientIds' => [$this->dora->getId()],
            'message' => 'Alice Martin vous a ajouté au tableau « Lancement produit »',
            'boardId' => $this->board->getId(),
        ], $overrides);
    }

    /** @return Notification[] */
    private function notifications(): array
    {
        $this->em->clear();

        return $this->em->getRepository(Notification::class)->findBy([], ['id' => 'ASC']);
    }

    private function receipts(): int
    {
        return $this->countSql('SELECT COUNT(*) FROM internal_event_receipt');
    }

    #[TestDox('SC-BOARD-025 — board_invite : 200 {created: 1}, message stocké tel quel, acteur et tableau')]
    public function testCreatesNotification(): void
    {
        [$status, $json] = $this->post($this->body());
        self::assertSame(200, $status);
        self::assertSame(['created' => 1], $json);
        $n = $this->notifications();
        self::assertCount(1, $n);
        self::assertSame('board_invite', $n[0]->getType());
        self::assertSame('Alice Martin vous a ajouté au tableau « Lancement produit »', $n[0]->getMessage());
        self::assertSame($this->dora->getId(), $n[0]->getRecipient()->getId());
        self::assertSame($this->alice->getId(), $n[0]->getActor()?->getId());
        self::assertSame($this->board->getId(), $n[0]->getBoard()?->getId());
        self::assertSame(1, $this->receipts());
    }

    #[TestDox('SC-BOARD-025 — jamais l\'acteur, sans doublon, destinataire inconnu ignoré')]
    public function testNotifierRules(): void
    {
        [$status, $json] = $this->post($this->body([
            'type' => 'member_joined',
            'recipientIds' => [$this->carla->getId(), $this->alice->getId(), $this->carla->getId(), 999999, $this->dora->getId()],
            'message' => 'Dora a rejoint le tableau « Lancement produit »',
        ]));
        self::assertSame(200, $status);
        self::assertSame(['created' => 2], $json);
        $recipients = array_map(fn (Notification $n) => $n->getRecipient()->getId(), $this->notifications());
        sort($recipients);
        $expected = [$this->carla->getId(), $this->dora->getId()];
        sort($expected);
        self::assertSame($expected, $recipients);
    }

    #[TestDox('SC-BOARD-025 — member_removed sans tableau (boardId absent) : notification sans tableau')]
    public function testMemberRemovedWithoutBoard(): void
    {
        $body = $this->body(['type' => 'member_removed', 'message' => 'Alice Martin vous a retiré du tableau « Lancement produit »']);
        unset($body['boardId']);
        [$status, $json] = $this->post($body);
        self::assertSame([200, ['created' => 1]], [$status, $json]);
        self::assertNull($this->notifications()[0]->getBoard());
    }

    #[TestDox('SC-BOARD-025 — acteur null (« Quelqu\'un ») accepté')]
    public function testNullActor(): void
    {
        [$status, $json] = $this->post($this->body(['actorId' => null, 'message' => 'Quelqu\'un vous a ajouté au tableau « Lancement produit »']));
        self::assertSame([200, ['created' => 1]], [$status, $json]);
        self::assertNull($this->notifications()[0]->getActor());
    }

    #[TestDox('SC-BOARD-025 — boardId ou cardId inexistant (supprimé entre-temps) → 200 {created: 0}')]
    public function testDeletedTarget(): void
    {
        self::assertSame([200, ['created' => 0]], $this->post($this->body(['boardId' => 999999])));
        self::assertSame([200, ['created' => 0]], $this->post($this->body(['eventId' => '9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a', 'cardId' => 999999])));
        self::assertCount(0, $this->notifications());
    }

    #[TestDox('SC-BOARD-025 — rejeu du même eventId → 200 {created: 0}, aucun doublon, aucun push')]
    public function testReplayIsIdempotent(): void
    {
        self::assertSame([200, ['created' => 1]], $this->post($this->body()), 'premier appel');
        self::assertCount(1, $this->pushed, 'premier appel : un push en file (capture active)');
        $this->resetPushes();
        [$status, $json] = $this->post($this->body());
        self::assertSame([200, ['created' => 0]], [$status, $json]);
        self::assertCount(1, $this->notifications());
        self::assertSame(1, $this->receipts());
        self::assertSame([], $this->pushed, 'aucun push au rejeu');
    }

    #[TestDox('SC-BOARD-025 — Web Push mis en file avec titre du tableau, URL /boards/{id}?card={id} et étiquette card-<id>')]
    public function testPushQueued(): void
    {
        self::assertTrue(static::getContainer()->get(PushSender::class)->isConfigured(), 'VAPID de test posé par ApiTestCase');
        [$status, $json] = $this->post($this->body(['type' => 'card_assigned', 'cardId' => $this->card->getId(), 'message' => 'Alice Martin vous a assigné la carte « Carte »']));
        self::assertSame([200, ['created' => 1]], [$status, $json]);
        self::assertCount(1, $this->pushed);
        self::assertSame($this->dora->getId(), $this->pushed[0]['user']);
        self::assertSame('Lancement produit', $this->pushed[0]['payload']['title'] ?? null);
        self::assertSame('Alice Martin vous a assigné la carte « Carte »', $this->pushed[0]['payload']['body'] ?? null);
        self::assertSame('/boards/'.$this->board->getId().'?card='.$this->card->getId(), $this->pushed[0]['payload']['url'] ?? null);
        self::assertSame('card-'.$this->card->getId(), $this->pushed[0]['payload']['tag'] ?? null);
    }

    #[TestDox('SC-BOARD-025 — une transaction : panne à l\'écriture → ni notification ni reçu (rejouable)')]
    public function testAtomic(): void
    {
        $connection = $this->em->getConnection();
        $connection->executeStatement("CREATE TRIGGER it_fail_notification BEFORE INSERT ON notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'panne simulée'");
        try {
            [$status, $json] = $this->post($this->body());
        } finally {
            $connection->executeStatement('DROP TRIGGER IF EXISTS it_fail_notification');
        }
        self::assertSame(500, $status);
        self::assertSame(['error' => 'Erreur interne du serveur.'], $json);
        self::assertCount(0, $this->notifications());
        self::assertSame(0, $this->receipts());
        self::assertSame([200, ['created' => 1]], $this->post($this->body()), 'rejeu après la panne');
    }

    #[TestDox('SC-BOARD-025 / U12 — secret absent, faux, ou d\'un autre appelant → 401 « Accès interne refusé. », aucune écriture')]
    public function testSecret(): void
    {
        foreach ([null, '', 'mauvais-secret-0123456789abcdef0123', self::IDENTITY_SECRET] as $secret) {
            [$status, $json] = $this->post($this->body(), $secret);
            self::assertSame([401, ['error' => 'Accès interne refusé.']], [$status, $json], var_export($secret === null ? null : 'secret', true));
        }
        self::assertCount(0, $this->notifications());
    }

    #[TestDox('SC-BOARD-025 — secret vide côté serveur → 401 même avec un en-tête vide')]
    public function testEmptyServerSecret(): void
    {
        $this->setSecrets(self::IDENTITY_SECRET, '');
        $this->boot();
        foreach (['', self::BOARD_SECRET] as $sent) {
            [$status, $json] = $this->post(['eventId' => self::EVENT], $sent);
            self::assertSame([401, ['error' => 'Accès interne refusé.']], [$status, $json]);
        }
    }

    /** @return iterable<string, array{string, string}> */
    public static function invalidBodies(): iterable
    {
        yield 'non JSON' => ['pas du json', 'JSON invalide.'];
        yield 'tableau' => ['[]', 'JSON invalide.'];
        yield 'recipientIds absent' => ['{"eventId":"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c","type":"board_invite","actorId":null,"message":"x"}', 'Le champ "recipientIds" est obligatoire.'];
        yield 'type inconnu' => ['{"eventId":"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c","type":"inconnu","actorId":null,"recipientIds":[1],"message":"x"}', 'Le champ "type" est invalide.'];
        yield 'eventId absent' => ['{"type":"board_invite","actorId":null,"recipientIds":[1],"message":"x"}', 'Le champ "eventId" est obligatoire.'];
        yield 'message absent' => ['{"eventId":"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c","type":"board_invite","actorId":null,"recipientIds":[1]}', 'Le champ "message" est obligatoire.'];
    }

    #[DataProvider('invalidBodies')]
    #[TestDox('SC-BOARD-025 — corps invalide → 400 exact (non rejouable), aucune écriture : $_dataName')]
    public function testInvalidBody(string $body, string $message): void
    {
        [$status, $json] = $this->post($body);
        self::assertSame([400, ['error' => $message]], [$status, $json]);
        self::assertCount(0, $this->notifications());
        self::assertSame(0, $this->receipts());
    }

    protected function tearDown(): void
    {
        if (null !== $this->concurrent) {
            if (null !== $this->concurrentEventId) {
                $this->concurrent->executeStatement('DELETE FROM internal_event_receipt WHERE event_id = ?', [$this->concurrentEventId]);
            }
            $this->concurrent->close();
            $this->concurrent = null;
        }
        parent::tearDown();
    }

    /** UUID v4 neuf (aléatoire), en minuscules. */
    private static function newEventId(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0F) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3F) | 0x80);

        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($b), 4));
    }

    #[TestDox('m1 (§12.2) — livraison concurrente du même eventId validée pendant la transaction → 200 {created: 0}, aucune notification, aucun push, aucun journal ERROR')]
    public function testConcurrentDuplicateReceiptAnswersCreatedZero(): void
    {
        $eventId = self::newEventId();
        $this->concurrentEventId = $eventId;
        $this->concurrent = DriverManager::getConnection($this->em->getConnection()->getParams());
        $concurrent = $this->concurrent;
        // Écouteur à usage unique : la seconde livraison insère et VALIDE son reçu dans la
        // transaction du contrôleur, après le find() et avant l'INSERT du reçu (flush).
        $listener = new class($concurrent, $eventId) {
            public bool $fired = false;

            public function __construct(private Connection $connection, private string $eventId)
            {
            }

            public function preFlush(PreFlushEventArgs $args): void
            {
                if ($this->fired) {
                    return;
                }
                $this->fired = true;
                $this->connection->executeStatement(
                    'INSERT INTO internal_event_receipt (event_id, type, received_at) VALUES (?, ?, ?)',
                    [$this->eventId, 'NotificationRequested', (new \DateTimeImmutable())->format('Y-m-d H:i:s')],
                );
            }
        };
        $events = $this->em->getEventManager();
        $events->addEventListener([Events::preFlush], $listener);
        $logFile = static::getContainer()->getParameter('kernel.logs_dir').'/test.log';
        clearstatcache();
        $logOffset = is_file($logFile) ? (int) filesize($logFile) : 0;
        $this->resetPushes();
        try {
            [$status, $json] = $this->post($this->body(['eventId' => $eventId]));
        } finally {
            $events->removeEventListener([Events::preFlush], $listener);
        }
        clearstatcache();
        $log = is_file($logFile) ? (string) file_get_contents($logFile, false, null, $logOffset) : '';

        self::assertTrue($listener->fired, 'la livraison concurrente a bien eu lieu pendant la transaction');
        self::assertSame([200, ['created' => 0]], [$status, $json]);
        self::assertCount(0, $this->notifications(), 'aucune notification');
        self::assertSame([], $this->pushed, 'aucun push');
        self::assertSame(1, (int) $concurrent->fetchOne('SELECT COUNT(*) FROM internal_event_receipt WHERE event_id = ?', [$eventId]), 'un seul reçu, celui de la livraison concurrente');
        self::assertDoesNotMatchRegularExpression('/\.(ERROR|CRITICAL|ALERT|EMERGENCY):/', $log, 'aucun journal ERROR');
    }
}
