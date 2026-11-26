<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125162308 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Trash (soft delete) for boards, lists and cards';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE board ADD deleted_at DATETIME DEFAULT NULL, ADD deleted_by_id INT DEFAULT NULL');
        $this->addSql('ALTER TABLE board ADD CONSTRAINT FK_58562B47C76F1F52 FOREIGN KEY (deleted_by_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('CREATE INDEX IDX_58562B47C76F1F52 ON board (deleted_by_id)');
        $this->addSql('ALTER TABLE board_list ADD deleted_at DATETIME DEFAULT NULL, ADD deleted_by_id INT DEFAULT NULL');
        $this->addSql('ALTER TABLE board_list ADD CONSTRAINT FK_9E5EA13BC76F1F52 FOREIGN KEY (deleted_by_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('CREATE INDEX IDX_9E5EA13BC76F1F52 ON board_list (deleted_by_id)');
        $this->addSql('ALTER TABLE card ADD deleted_at DATETIME DEFAULT NULL, ADD deleted_by_id INT DEFAULT NULL');
        $this->addSql('ALTER TABLE card ADD CONSTRAINT FK_161498D3C76F1F52 FOREIGN KEY (deleted_by_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('CREATE INDEX IDX_161498D3C76F1F52 ON card (deleted_by_id)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE board DROP FOREIGN KEY FK_58562B47C76F1F52');
        $this->addSql('DROP INDEX IDX_58562B47C76F1F52 ON board');
        $this->addSql('ALTER TABLE board DROP deleted_at, DROP deleted_by_id');
        $this->addSql('ALTER TABLE board_list DROP FOREIGN KEY FK_9E5EA13BC76F1F52');
        $this->addSql('DROP INDEX IDX_9E5EA13BC76F1F52 ON board_list');
        $this->addSql('ALTER TABLE board_list DROP deleted_at, DROP deleted_by_id');
        $this->addSql('ALTER TABLE card DROP FOREIGN KEY FK_161498D3C76F1F52');
        $this->addSql('DROP INDEX IDX_161498D3C76F1F52 ON card');
        $this->addSql('ALTER TABLE card DROP deleted_at, DROP deleted_by_id');
    }
}
