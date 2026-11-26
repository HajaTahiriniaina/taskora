<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125165023 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Browser notifications (Web Push devices)';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE push_subscription (id INT AUTO_INCREMENT NOT NULL, kind VARCHAR(10) NOT NULL, endpoint LONGTEXT NOT NULL, endpoint_hash VARCHAR(64) NOT NULL, p256dh VARCHAR(200) NOT NULL, auth VARCHAR(100) NOT NULL, device VARCHAR(120) DEFAULT NULL, created_at DATETIME NOT NULL, last_success_at DATETIME DEFAULT NULL, user_id INT NOT NULL, UNIQUE INDEX uniq_push_endpoint (endpoint_hash), INDEX IDX_562830F3A76ED395 (user_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('ALTER TABLE push_subscription ADD CONSTRAINT FK_562830F3A76ED395 FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE push_subscription DROP FOREIGN KEY FK_562830F3A76ED395');
        $this->addSql('DROP TABLE push_subscription');
    }
}
