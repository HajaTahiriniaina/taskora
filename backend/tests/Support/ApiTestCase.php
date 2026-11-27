<?php

namespace App\Tests\Support;

use App\Entity\Board;
use App\Entity\BoardMember;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Tools\SchemaTool;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Base des tests fonctionnels de l'API Symfony : base *_test seulement (garde-fou), schéma recréé à
 * chaque test, données inventées (@exemple.test), secrets internes factices et distincts forcés
 * dans l'environnement du processus (U12 : jamais les valeurs réelles de compose).
 */
abstract class ApiTestCase extends WebTestCase
{
    /** identity → Symfony (claim). */
    public const IDENTITY_SECRET = 'test-identity-to-symfony-0123456789abcdef';
    /** board-service → Symfony (/internal/notifications…). */
    public const BOARD_SECRET = 'test-board-to-symfony-fedcba9876543210ab';

    protected KernelBrowser $client;
    protected EntityManagerInterface $em;
    /** @var array<string, ?string> */
    private array $previous = [];

    protected function setUp(): void
    {
        foreach (['INTERNAL_API_SECRET', 'INTERNAL_SECRET_BOARD_TO_SYMFONY'] as $name) {
            $this->previous[$name] = $_SERVER[$name] ?? null;
        }
        $this->setSecrets(self::IDENTITY_SECRET, self::BOARD_SECRET);
        $this->boot();
    }

    protected function tearDown(): void
    {
        foreach ($this->previous as $name => $value) {
            self::setEnv($name, $value);
        }
        parent::tearDown();
    }

    protected function setSecrets(?string $identity, ?string $board): void
    {
        self::setEnv('INTERNAL_API_SECRET', $identity);
        self::setEnv('INTERNAL_SECRET_BOARD_TO_SYMFONY', $board);
    }

    private static function setEnv(string $name, ?string $value): void
    {
        if (null === $value) {
            unset($_SERVER[$name], $_ENV[$name]);
            putenv($name);
        } else {
            $_SERVER[$name] = $_ENV[$name] = $value;
            putenv($name.'='.$value);
        }
    }

    /** Redémarre le noyau (prise en compte des secrets) et recrée le schéma de la base *_test. */
    protected function boot(): void
    {
        self::ensureKernelShutdown();
        $this->client = static::createClient();
        $this->client->disableReboot();
        $this->em = static::getContainer()->get(EntityManagerInterface::class);
        $database = (string) $this->em->getConnection()->getDatabase();
        if (!str_ends_with($database, '_test')) {
            self::fail(sprintf('Base "%s" refusée : les tests PHPUnit ne tournent que sur une base *_test.', $database));
        }
        $this->em->getConfiguration()->setUseSchemaEditorApi(true);
        $connection = $this->em->getConnection();
        $connection->executeStatement('SET FOREIGN_KEY_CHECKS = 0');
        foreach ($connection->createSchemaManager()->listTableNames() as $table) {
            $connection->executeStatement('DROP TABLE '.$connection->quoteSingleIdentifier($table));
        }
        $connection->executeStatement('SET FOREIGN_KEY_CHECKS = 1');
        (new SchemaTool($this->em))->createSchema($this->em->getMetadataFactory()->getAllMetadata());
    }

    protected function user(string $email, string $name): User
    {
        $u = (new User())->setEmail($email)->setName($name)->setPassword('$2y$04$hashfactice');
        $this->em->persist($u);

        return $u;
    }

    protected function board(User $owner, string $title): Board
    {
        $b = new Board($owner, $title);
        $b->addMember(new BoardMember($b, $owner, BoardMember::ROLE_OWNER));
        $this->em->persist($b);

        return $b;
    }

    protected function token(User $u): string
    {
        return static::getContainer()->get(JWTTokenManagerInterface::class)->create($u);
    }

    /**
     * Requête JSON ; corps null = sans corps.
     *
     * @return array{0: int, 1: mixed, 2: string}
     */
    protected function api(string $method, string $path, ?User $as, ?string $body = null, array $headers = []): array
    {
        $server = ['CONTENT_TYPE' => 'application/json'];
        if (null !== $as) {
            $server['HTTP_AUTHORIZATION'] = 'Bearer '.$this->token($as);
        }
        foreach ($headers as $name => $value) {
            $server['HTTP_'.strtoupper(str_replace('-', '_', $name))] = $value;
        }
        $this->client->request($method, $path, [], [], $server, $body ?? '');
        $response = $this->client->getResponse();
        $content = (string) $response->getContent();

        return [$response->getStatusCode(), '' === $content ? null : json_decode($content, true), (string) $response->headers->get('Content-Type')];
    }

    /** Empreinte des données (CHECKSUM TABLE) : prouve « aucune écriture ». */
    protected function fingerprint(): array
    {
        $connection = $this->em->getConnection();
        $tables = $connection->createSchemaManager()->listTableNames();
        sort($tables);
        $out = [];
        foreach ($connection->fetchAllAssociative('CHECKSUM TABLE '.implode(', ', array_map(fn ($t) => $connection->quoteSingleIdentifier($t), $tables))) as $row) {
            $out[$row['Table']] = $row['Checksum'];
        }

        return $out;
    }

    protected function countSql(string $sql, array $params = []): int
    {
        return (int) $this->em->getConnection()->fetchOne($sql, $params);
    }
}
