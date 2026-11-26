<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125134314 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Attachment thumbnails / PDF previews, image cover on cards';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE attachment ADD thumb_key VARCHAR(255) DEFAULT NULL, ADD preview_key VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE card ADD cover_attachment_id INT DEFAULT NULL');
        $this->addSql('ALTER TABLE card ADD CONSTRAINT FK_161498D357AB2477 FOREIGN KEY (cover_attachment_id) REFERENCES attachment (id) ON DELETE SET NULL');
        $this->addSql('CREATE INDEX IDX_161498D357AB2477 ON card (cover_attachment_id)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE attachment DROP thumb_key, DROP preview_key');
        $this->addSql('ALTER TABLE card DROP FOREIGN KEY FK_161498D357AB2477');
        $this->addSql('DROP INDEX IDX_161498D357AB2477 ON card');
        $this->addSql('ALTER TABLE card DROP cover_attachment_id');
    }
}
