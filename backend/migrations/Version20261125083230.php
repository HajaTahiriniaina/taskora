<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125083230 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE board ADD card_seq INT DEFAULT 0 NOT NULL, ADD show_card_numbers TINYINT DEFAULT 1 NOT NULL');
        // Add as nullable, backfill existing cards, then enforce NOT NULL
        $this->addSql('ALTER TABLE card ADD number INT DEFAULT NULL, ADD board_id INT DEFAULT NULL');
        $this->addSql('UPDATE card c INNER JOIN board_list l ON l.id = c.list_id SET c.board_id = l.board_id');
        // Number existing cards per board in creation order (#1, #2, …)
        $this->addSql('UPDATE card c INNER JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY board_id ORDER BY created_at, id) AS rn FROM card) x ON x.id = c.id SET c.number = x.rn');
        $this->addSql('UPDATE board b SET b.card_seq = (SELECT COALESCE(MAX(c.number), 0) FROM card c WHERE c.board_id = b.id)');
        $this->addSql('ALTER TABLE card MODIFY number INT NOT NULL, MODIFY board_id INT NOT NULL');
        $this->addSql('ALTER TABLE card ADD CONSTRAINT FK_161498D3E7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('CREATE UNIQUE INDEX uniq_card_board_number ON card (board_id, number)');
        $this->addSql('CREATE INDEX IDX_161498D3E7EC5785 ON card (board_id)');
        $this->addSql('ALTER TABLE comment ADD external_author VARCHAR(120) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE board DROP card_seq, DROP show_card_numbers');
        $this->addSql('ALTER TABLE card DROP FOREIGN KEY FK_161498D3E7EC5785');
        $this->addSql('DROP INDEX uniq_card_board_number ON card');
        $this->addSql('DROP INDEX IDX_161498D3E7EC5785 ON card');
        $this->addSql('ALTER TABLE card DROP number, DROP board_id');
        $this->addSql('ALTER TABLE comment DROP external_author');
    }
}
