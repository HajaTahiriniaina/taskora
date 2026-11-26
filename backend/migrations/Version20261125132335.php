<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125132335 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Card attachments (files uploaded on a card or posted in comments)';
    }

    public function isTransactional(): bool
    {
        return false; // MariaDB DDL commits implicitly
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE attachment (id INT AUTO_INCREMENT NOT NULL, name VARCHAR(255) NOT NULL, storage_key VARCHAR(255) NOT NULL, mime_type VARCHAR(120) NOT NULL, size INT NOT NULL, created_at DATETIME NOT NULL, card_id INT NOT NULL, uploader_id INT DEFAULT NULL, comment_id INT DEFAULT NULL, UNIQUE INDEX UNIQ_795FD9BB111795A5 (storage_key), INDEX idx_attachment_card_date (card_id, created_at), INDEX IDX_795FD9BB4ACC9A20 (card_id), INDEX IDX_795FD9BB16678C77 (uploader_id), INDEX IDX_795FD9BBF8697D13 (comment_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('ALTER TABLE attachment ADD CONSTRAINT FK_795FD9BB4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE attachment ADD CONSTRAINT FK_795FD9BB16678C77 FOREIGN KEY (uploader_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('ALTER TABLE attachment ADD CONSTRAINT FK_795FD9BBF8697D13 FOREIGN KEY (comment_id) REFERENCES comment (id) ON DELETE CASCADE');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE attachment DROP FOREIGN KEY FK_795FD9BB4ACC9A20');
        $this->addSql('ALTER TABLE attachment DROP FOREIGN KEY FK_795FD9BB16678C77');
        $this->addSql('ALTER TABLE attachment DROP FOREIGN KEY FK_795FD9BBF8697D13');
        $this->addSql('DROP TABLE attachment');
    }
}
