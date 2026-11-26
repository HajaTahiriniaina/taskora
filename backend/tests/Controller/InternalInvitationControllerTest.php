<?php

namespace App\Tests\Controller;

use App\Entity\Board;
use App\Entity\BoardMember;
use App\Entity\Invitation;
use App\Entity\Notification;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Tools\SchemaTool;
use PHPUnit\Framework\Attributes\TestDox;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Endpoint transitoire POST /internal/invitations/claim (U2-a, ADR A10),
 * contrat docs/contracts/openapi/identity-service.internal.yaml.
 * Base de test dédiée (taskora_test), schéma recréé à chaque test. Données inventées (@exemple.test).
 */
final class InternalInvitationControllerTest extends WebTestCase
{
    private const SECRET = 'test-internal-secret-0123456789abcdef';
    private const URL = '/internal/invitations/claim';

    private KernelBrowser $client;
    private EntityManagerInterface $em;
    private ?string $previousSecret = null;

    protected function setUp(): void
    {
        $this->previousSecret = $_SERVER['INTERNAL_API_SECRET'] ?? null;
        // Indépendant de l'environnement du processus : une vraie variable INTERNAL_API_SECRET
        // (injectée par compose) l'emporterait sur .env.test ; on force la valeur de test.
        $this->setServerSecret(self::SECRET);
        $this->boot();
    }

    protected function tearDown(): void
    {
        $this->setServerSecret($this->previousSecret);
        parent::tearDown();
    }

    private function boot(): void
    {
        self::ensureKernelShutdown();
        $this->client = static::createClient();
        $this->client->disableReboot();
        $this->em = static::getContainer()->get(EntityManagerInterface::class);
        // Garde-fou : le schéma est détruit puis recréé, jamais ailleurs que dans une base *_test.
        $database = (string) $this->em->getConnection()->getDatabase();
        if (!str_ends_with($database, '_test')) {
            self::fail(sprintf('Base "%s" refusée : les tests PHPUnit ne tournent que sur une base *_test.', $database));
        }
        // API non dépréciée (Doctrine ORM 3.x → 4.0) : failOnDeprecation reste actif.
        $this->em->getConfiguration()->setUseSchemaEditorApi(true);
        $tool = new SchemaTool($this->em);
        $metadata = $this->em->getMetadataFactory()->getAllMetadata();
        // Base *_test vidée entièrement (tables d'éventuelles migrations comprises), puis schéma recréé.
        $connection = $this->em->getConnection();
        $connection->executeStatement('SET FOREIGN_KEY_CHECKS = 0');
        foreach ($connection->createSchemaManager()->listTableNames() as $table) {
            $connection->executeStatement('DROP TABLE '.$connection->quoteSingleIdentifier($table));
        }
        $connection->executeStatement('SET FOREIGN_KEY_CHECKS = 1');
        $tool->createSchema($metadata);
    }

    private function setServerSecret(?string $value): void
    {
        if (null === $value) {
            unset($_SERVER['INTERNAL_API_SECRET'], $_ENV['INTERNAL_API_SECRET']);
            putenv('INTERNAL_API_SECRET');
        } else {
            $_SERVER['INTERNAL_API_SECRET'] = $_ENV['INTERNAL_API_SECRET'] = $value;
            putenv('INTERNAL_API_SECRET='.$value);
        }
    }

    private function user(string $email, string $name): User
    {
        $u = (new User())->setEmail($email)->setName($name)->setPassword('$2y$04$hashfactice');
        $this->em->persist($u);

        return $u;
    }

    private function board(User $owner, string $title, bool $trashed = false): Board
    {
        $b = new Board($owner, $title);
        $b->addMember(new BoardMember($b, $owner, BoardMember::ROLE_OWNER));
        if ($trashed) {
            $b->trash($owner);
        }
        $this->em->persist($b);

        return $b;
    }

    private function invite(Board $board, string $email, ?User $by): Invitation
    {
        $i = new Invitation($board, $email, $by);
        $this->em->persist($i);

        return $i;
    }

    /** @param array<string, mixed>|string $body */
    private function claim(array|string $body, ?string $secret = self::SECRET): array
    {
        $headers = ['CONTENT_TYPE' => 'application/json'];
        if (null !== $secret) {
            $headers['HTTP_X_INTERNAL_SECRET'] = $secret;
        }
        $this->client->request('POST', self::URL, [], [], $headers, is_string($body) ? $body : json_encode($body));
        $response = $this->client->getResponse();

        return [$response->getStatusCode(), json_decode((string) $response->getContent(), true)];
    }

    /** @return array{0: User, 1: Board, 2: Board} */
    private function camilleInvitedToSprintAndRoadmap(): array
    {
        $alice = $this->user('alice@exemple.test', 'Alice');
        $bob = $this->user('bob@exemple.test', 'Bob');
        $sprint = $this->board($alice, 'Sprint');
        $roadmap = $this->board($bob, 'Roadmap');
        $this->invite($sprint, 'camille@exemple.test', $alice);
        $this->invite($roadmap, 'camille@exemple.test', $bob);
        $camille = $this->user('camille@exemple.test', 'Camille');
        $this->em->flush();

        return [$camille, $sprint, $roadmap];
    }

    private function countRows(string $class, array $criteria = []): int
    {
        $this->em->clear();

        return $this->em->getRepository($class)->count($criteria);
    }

    private function body(User $u): array
    {
        return ['eventId' => '0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17', 'userId' => $u->getId(), 'email' => $u->getEmail()];
    }

    #[TestDox('SC-IDENT-005 — sans en-tête X-Internal-Secret → 401 « Accès interne refusé. », aucune écriture')]
    public function testMissingSecret(): void
    {
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim($this->body($camille), null);
        self::assertSame(401, $status);
        self::assertSame(['error' => 'Accès interne refusé.'], $json);
        self::assertSame(2, $this->countRows(Invitation::class));
        self::assertSame(0, $this->countRows(Notification::class));
    }

    #[TestDox('SC-IDENT-005 — secret faux → 401 « Accès interne refusé. », aucune écriture')]
    public function testWrongSecret(): void
    {
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim($this->body($camille), 'mauvais-secret-0123456789abcdef0123');
        self::assertSame(401, $status);
        self::assertSame(['error' => 'Accès interne refusé.'], $json);
        self::assertSame(2, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-005 — INTERNAL_API_SECRET vide côté serveur → 401 même avec un en-tête vide')]
    public function testEmptyServerSecretAlwaysRefuses(): void
    {
        $this->setServerSecret('');
        $this->boot();
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        foreach (['', self::SECRET] as $sent) {
            [$status, $json] = $this->claim($this->body($camille), $sent);
            self::assertSame(401, $status);
            self::assertSame(['error' => 'Accès interne refusé.'], $json);
        }
        self::assertSame(2, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-005 — deux invitations → 200 {claimed: 2, boardIds}, adhésions member, notifications board_invite, invitations supprimées')]
    public function testClaimsTwoInvitations(): void
    {
        [$camille, $sprint, $roadmap] = $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim($this->body($camille));
        self::assertSame(200, $status);
        self::assertSame(2, $json['claimed']);
        $ids = $json['boardIds'];
        sort($ids);
        $expected = [$sprint->getId(), $roadmap->getId()];
        sort($expected);
        self::assertSame($expected, $ids);
        self::assertSame(['boardIds', 'claimed'], (function (array $k) { sort($k); return $k; })(array_keys($json)));

        $this->em->clear();
        $members = $this->em->getRepository(BoardMember::class)->findBy(['user' => $camille->getId()]);
        self::assertCount(2, $members);
        foreach ($members as $m) {
            self::assertSame(BoardMember::ROLE_MEMBER, $m->getRole());
        }
        $notifications = $this->em->getRepository(Notification::class)->findBy(['recipient' => $camille->getId()]);
        $messages = array_map(fn (Notification $n) => $n->getMessage(), $notifications);
        sort($messages);
        self::assertSame(['Alice vous a ajouté au tableau « Sprint »', 'Bob vous a ajouté au tableau « Roadmap »'], $messages);
        foreach ($notifications as $n) {
            self::assertSame('board_invite', $n->getType());
            self::assertNotNull($n->getActor());
        }
        self::assertSame(0, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-005 — rejeu du même appel → 200 {claimed: 0, boardIds: []}, rien n\'est doublé (idempotent)')]
    public function testReplayIsIdempotent(): void
    {
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        $this->claim($this->body($camille));
        [$status, $json] = $this->claim($this->body($camille));
        self::assertSame(200, $status);
        self::assertSame(['claimed' => 0, 'boardIds' => []], $json);
        self::assertSame(2, $this->countRows(BoardMember::class, ['user' => $camille->getId()]));
        self::assertSame(2, $this->countRows(Notification::class, ['recipient' => $camille->getId()]));
    }

    #[TestDox('SC-IDENT-005 — adhésion déjà existante → ni doublon ni notification, invitation supprimée quand même')]
    public function testExistingMembership(): void
    {
        $alice = $this->user('alice@exemple.test', 'Alice');
        $camille = $this->user('camille@exemple.test', 'Camille');
        $sprint = $this->board($alice, 'Sprint');
        $sprint->addMember(new BoardMember($sprint, $camille));
        $this->invite($sprint, 'camille@exemple.test', $alice);
        $this->em->flush();

        [$status, $json] = $this->claim($this->body($camille));
        self::assertSame(200, $status);
        self::assertSame(['claimed' => 0, 'boardIds' => []], $json);
        self::assertSame(1, $this->countRows(BoardMember::class, ['user' => $camille->getId()]));
        self::assertSame(0, $this->countRows(Notification::class));
        self::assertSame(0, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-006 — invited_by NULL → « Quelqu\'un vous a ajouté au tableau « Roadmap » », acteur nul')]
    public function testInviterGone(): void
    {
        $bob = $this->user('bob@exemple.test', 'Bob');
        $roadmap = $this->board($bob, 'Roadmap');
        $this->invite($roadmap, 'camille@exemple.test', null);
        $camille = $this->user('camille@exemple.test', 'Camille');
        $this->em->flush();

        [$status, $json] = $this->claim($this->body($camille));
        self::assertSame(200, $status);
        self::assertSame(1, $json['claimed']);
        $this->em->clear();
        $n = $this->em->getRepository(Notification::class)->findOneBy(['recipient' => $camille->getId()]);
        self::assertNotNull($n);
        self::assertSame('Quelqu\'un vous a ajouté au tableau « Roadmap »', $n->getMessage());
        self::assertNull($n->getActor());
    }

    #[TestDox('SC-IDENT-005 — tableau dans la corbeille → l\'adhésion est créée quand même (comportement actuel)')]
    public function testTrashedBoard(): void
    {
        $alice = $this->user('alice@exemple.test', 'Alice');
        $old = $this->board($alice, 'Ancien', true);
        $this->invite($old, 'camille@exemple.test', $alice);
        $camille = $this->user('camille@exemple.test', 'Camille');
        $this->em->flush();

        [$status, $json] = $this->claim($this->body($camille));
        self::assertSame(200, $status);
        self::assertSame([$old->getId()], $json['boardIds']);
        self::assertSame(1, $this->countRows(BoardMember::class, ['user' => $camille->getId()]));
    }

    #[TestDox('SC-IDENT-005 — userId inconnu → 404 « Utilisateur introuvable. » (rejouable)')]
    public function testUnknownUser(): void
    {
        $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim(['eventId' => '0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17', 'userId' => 999999, 'email' => 'camille@exemple.test']);
        self::assertSame(404, $status);
        self::assertSame(['error' => 'Utilisateur introuvable.'], $json);
        self::assertSame(2, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-005 — e-mail différent de celui de l\'utilisateur → 409, aucune écriture')]
    public function testEmailMismatch(): void
    {
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim(['eventId' => '0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17', 'userId' => $camille->getId(), 'email' => 'alice@exemple.test']);
        self::assertSame(409, $status);
        self::assertSame(['error' => 'L\'e-mail ne correspond pas à l\'utilisateur.'], $json);
        self::assertSame(2, $this->countRows(Invitation::class));
    }

    #[TestDox('SC-IDENT-005 — corps non JSON → 400 « JSON invalide. »')]
    public function testInvalidJson(): void
    {
        [$status, $json] = $this->claim('pas du json');
        self::assertSame(400, $status);
        self::assertSame(['error' => 'JSON invalide.'], $json);
    }

    #[TestDox('SC-IDENT-005 — champ manquant ou invalide → 400 avec le message du contrat')]
    public function testMissingOrInvalidFields(): void
    {
        [$camille] = $this->camilleInvitedToSprintAndRoadmap();
        [$status, $json] = $this->claim(['eventId' => '0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17', 'userId' => $camille->getId()]);
        self::assertSame(400, $status);
        self::assertSame(['error' => 'Le champ "email" est obligatoire.'], $json);

        [$status, $json] = $this->claim(['eventId' => '0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17', 'userId' => 'abc', 'email' => 'camille@exemple.test']);
        self::assertSame(400, $status);
        self::assertSame(['error' => 'Le champ "userId" est invalide.'], $json);
        self::assertSame(2, $this->countRows(Invitation::class));
    }

    #[TestDox('Non-régression — POST /api/auth/register de Symfony consomme toujours les invitations')]
    public function testSymfonyRegisterStillClaims(): void
    {
        $alice = $this->user('alice@exemple.test', 'Alice');
        $sprint = $this->board($alice, 'Sprint');
        $this->invite($sprint, 'camille@exemple.test', $alice);
        $this->em->flush();

        $this->client->request('POST', '/api/auth/register', [], [], ['CONTENT_TYPE' => 'application/json'],
            json_encode(['name' => 'Camille', 'email' => 'camille@exemple.test', 'password' => 'motdepasse']));
        self::assertSame(201, $this->client->getResponse()->getStatusCode());

        $this->em->clear();
        $camille = $this->em->getRepository(User::class)->findOneBy(['email' => 'camille@exemple.test']);
        self::assertNotNull($camille);
        self::assertSame(1, $this->countRows(BoardMember::class, ['user' => $camille->getId(), 'role' => BoardMember::ROLE_MEMBER]));
        $n = $this->em->getRepository(Notification::class)->findOneBy(['recipient' => $camille->getId()]);
        self::assertSame('Alice vous a ajouté au tableau « Sprint »', $n?->getMessage());
        self::assertSame(0, $this->countRows(Invitation::class));
    }
}
