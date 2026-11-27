<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Phase 3 (board-migration.md §2.1, R1, R10) : migration ÉCRITE À LA MAIN, additive, décidée par
 * BackAli — table internal_event_receipt pour l'idempotence des endpoints internes appelés par
 * board-service (POST /internal/notifications). Aucune autre table modifiée ; jamais de
 * doctrine:migrations:diff pendant la cohabitation.
 */
final class Version20261128000100 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Phase 3 : reçus des événements internes (internal_event_receipt)';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    // Exécution directe (et non addSql) dans up() ET down() : Doctrine Migrations réutilise et gèle
    // l'instance après up() ; dans un même processus (migrate puis migrate <version>), down() lèverait
    // FrozenMigration et les requêtes planifiées de up() seraient rejouées au retour arrière.
    public function up(Schema $schema): void
    {
        $this->connection->executeStatement('CREATE TABLE internal_event_receipt (event_id CHAR(36) NOT NULL, type VARCHAR(64) NOT NULL, received_at DATETIME NOT NULL, PRIMARY KEY (event_id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
    }

    public function down(Schema $schema): void
    {
        $this->connection->executeStatement('DROP TABLE IF EXISTS internal_event_receipt');
    }
}
