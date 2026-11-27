<?php

namespace App\Tests\Migrations;

use Doctrine\DBAL\Connection;
use PHPUnit\Framework\Attributes\TestDox;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Input\ArrayInput;
use Symfony\Component\Console\Output\BufferedOutput;

/**
 * R1, R10 (board-migration.md §2.1) — la migration Doctrine écrite à la main qui crée
 * internal_event_receipt (event_id CHAR(36) PK, type VARCHAR(64), received_at DATETIME) : migrate
 * puis doctrine:schema:validate vert ; down() réversible ; aucune autre table modifiée. Base *_test
 * seulement, vidée puis reconstruite par les migrations réelles.
 */
final class InternalEventReceiptMigrationTest extends KernelTestCase
{
    /** Dernière migration avant la phase 3 (12 migrations, docs/ci/requests/backend.md §B6). */
    private const BEFORE = 'DoctrineMigrations\\Version20261125165023';

    private Connection $connection;
    private Application $console;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->connection = static::getContainer()->get(Connection::class);
        $database = (string) $this->connection->getDatabase();
        if (!str_ends_with($database, '_test')) {
            self::fail(sprintf('Base "%s" refusée : les tests PHPUnit ne tournent que sur une base *_test.', $database));
        }
        $this->connection->executeStatement('SET FOREIGN_KEY_CHECKS = 0');
        foreach ($this->connection->createSchemaManager()->listTableNames() as $table) {
            $this->connection->executeStatement('DROP TABLE '.$this->connection->quoteSingleIdentifier($table));
        }
        $this->connection->executeStatement('SET FOREIGN_KEY_CHECKS = 1');
        $this->console = new Application(self::$kernel);
        $this->console->setAutoExit(false);
    }

    /** @return array{0: int, 1: string} */
    private function console(array $input): array
    {
        $output = new BufferedOutput();
        $code = $this->console->run(new ArrayInput($input + ['--no-interaction' => true]), $output);

        return [$code, $output->fetch()];
    }

    /** Structure de la base, hors internal_event_receipt et doctrine_migration_versions. */
    private function fingerprint(): string
    {
        $rows = $this->connection->fetchAllAssociative(
            "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLLATION_NAME, EXTRA
               FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME NOT IN ('internal_event_receipt', 'doctrine_migration_versions')
              ORDER BY TABLE_NAME, ORDINAL_POSITION");
        $indexes = $this->connection->fetchAllAssociative(
            "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
               FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME NOT IN ('internal_event_receipt', 'doctrine_migration_versions')
              ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX");

        return json_encode([$rows, $indexes]);
    }

    private function receiptExists(): bool
    {
        return in_array('internal_event_receipt', $this->connection->createSchemaManager()->listTableNames(), true);
    }

    #[TestDox('R10 — exactement une nouvelle migration après Version20261125165023, écrite à la main pour internal_event_receipt')]
    public function testSingleNewMigration(): void
    {
        $dir = dirname(__DIR__, 2).'/migrations';
        $versions = array_map(fn ($f) => basename($f, '.php'), glob($dir.'/Version*.php'));
        sort($versions);
        $after = array_values(array_filter($versions, fn ($v) => $v > 'Version20261125165023'));
        self::assertCount(1, $after, 'une seule migration de phase 3');
        $source = (string) file_get_contents($dir.'/'.$after[0].'.php');
        self::assertStringContainsString('internal_event_receipt', $source);
        self::assertStringContainsString('function down', $source);
    }

    #[TestDox('R1 — migrate : internal_event_receipt créée (event_id CHAR(36) PK, type VARCHAR(64), received_at DATETIME), aucune autre table modifiée')]
    public function testMigrateCreatesOnlyReceipt(): void
    {
        [$code, $out] = $this->console(['command' => 'doctrine:migrations:migrate', 'version' => self::BEFORE]);
        self::assertSame(0, $code, $out);
        $before = $this->fingerprint();
        self::assertFalse($this->receiptExists());

        [$code, $out] = $this->console(['command' => 'doctrine:migrations:migrate']);
        self::assertSame(0, $code, $out);
        self::assertTrue($this->receiptExists());
        self::assertSame($before, $this->fingerprint(), 'aucune autre table modifiée');

        $columns = $this->connection->fetchAllAssociative(
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'internal_event_receipt' ORDER BY ORDINAL_POSITION");
        self::assertSame([
            ['COLUMN_NAME' => 'event_id', 'COLUMN_TYPE' => 'char(36)', 'IS_NULLABLE' => 'NO', 'COLUMN_KEY' => 'PRI'],
            ['COLUMN_NAME' => 'type', 'COLUMN_TYPE' => 'varchar(64)', 'IS_NULLABLE' => 'NO', 'COLUMN_KEY' => ''],
            ['COLUMN_NAME' => 'received_at', 'COLUMN_TYPE' => 'datetime', 'IS_NULLABLE' => 'NO', 'COLUMN_KEY' => ''],
        ], $columns);
    }

    #[TestDox('R1 — après migrate, doctrine:schema:validate est vert (entité et base en accord)')]
    public function testSchemaValidate(): void
    {
        [$code, $out] = $this->console(['command' => 'doctrine:migrations:migrate']);
        self::assertSame(0, $code, $out);
        [$code, $out] = $this->console(['command' => 'doctrine:schema:validate']);
        self::assertSame(0, $code, $out);
    }

    #[TestDox('R1 — down() réversible : retour à Version20261125165023 → table supprimée, structure d\'avant identique')]
    public function testDownIsReversible(): void
    {
        $this->console(['command' => 'doctrine:migrations:migrate', 'version' => self::BEFORE]);
        $before = $this->fingerprint();
        [$code, $out] = $this->console(['command' => 'doctrine:migrations:migrate']);
        self::assertSame(0, $code, $out);
        [$code, $out] = $this->console(['command' => 'doctrine:migrations:migrate', 'version' => self::BEFORE]);
        self::assertSame(0, $code, $out);
        self::assertFalse($this->receiptExists());
        self::assertSame($before, $this->fingerprint());
    }
}
