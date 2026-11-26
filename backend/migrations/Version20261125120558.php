<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125120558 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE activity (id INT AUTO_INCREMENT NOT NULL, external_actor VARCHAR(120) DEFAULT NULL, type VARCHAR(40) NOT NULL, data JSON NOT NULL, created_at DATETIME NOT NULL, card_id INT NOT NULL, actor_id INT DEFAULT NULL, INDEX idx_activity_card_date (card_id, created_at), INDEX IDX_AC74095A4ACC9A20 (card_id), INDEX IDX_AC74095A10DAF24A (actor_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('ALTER TABLE activity ADD CONSTRAINT FK_AC74095A4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE activity ADD CONSTRAINT FK_AC74095A10DAF24A FOREIGN KEY (actor_id) REFERENCES `user` (id) ON DELETE SET NULL');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE activity DROP FOREIGN KEY FK_AC74095A4ACC9A20');
        $this->addSql('ALTER TABLE activity DROP FOREIGN KEY FK_AC74095A10DAF24A');
        $this->addSql('DROP TABLE activity');
    }
}
