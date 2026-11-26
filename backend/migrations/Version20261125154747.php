<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125154747 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Files of Trello imports waiting to be copied';
    }

    public function isTransactional(): bool
    {
        return false;
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE trello_import_file (id INT AUTO_INCREMENT NOT NULL, trello_id VARCHAR(24) NOT NULL, url VARCHAR(1000) NOT NULL, name VARCHAR(255) NOT NULL, bytes INT NOT NULL, date DATETIME NOT NULL, cover TINYINT NOT NULL, status VARCHAR(10) NOT NULL, error VARCHAR(255) DEFAULT NULL, board_id INT NOT NULL, card_id INT NOT NULL, uploader_id INT DEFAULT NULL, attachment_id INT DEFAULT NULL, INDEX idx_trello_file_status (board_id, status), UNIQUE INDEX uniq_trello_file (board_id, trello_id), INDEX IDX_9AF0D34FE7EC5785 (board_id), INDEX IDX_9AF0D34F4ACC9A20 (card_id), INDEX IDX_9AF0D34F16678C77 (uploader_id), INDEX IDX_9AF0D34F464E68B (attachment_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('ALTER TABLE trello_import_file ADD CONSTRAINT FK_9AF0D34FE7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE trello_import_file ADD CONSTRAINT FK_9AF0D34F4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE trello_import_file ADD CONSTRAINT FK_9AF0D34F16678C77 FOREIGN KEY (uploader_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('ALTER TABLE trello_import_file ADD CONSTRAINT FK_9AF0D34F464E68B FOREIGN KEY (attachment_id) REFERENCES attachment (id) ON DELETE SET NULL');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE trello_import_file DROP FOREIGN KEY FK_9AF0D34FE7EC5785');
        $this->addSql('ALTER TABLE trello_import_file DROP FOREIGN KEY FK_9AF0D34F4ACC9A20');
        $this->addSql('ALTER TABLE trello_import_file DROP FOREIGN KEY FK_9AF0D34F16678C77');
        $this->addSql('ALTER TABLE trello_import_file DROP FOREIGN KEY FK_9AF0D34F464E68B');
        $this->addSql('DROP TABLE trello_import_file');
    }
}
