<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125135622 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Indexes for thumbnail / preview key lookups';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE INDEX idx_attachment_thumb ON attachment (thumb_key)');
        $this->addSql('CREATE INDEX idx_attachment_preview ON attachment (preview_key)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('DROP INDEX idx_attachment_thumb ON attachment');
        $this->addSql('DROP INDEX idx_attachment_preview ON attachment');
    }
}
