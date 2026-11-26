<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20261125070204 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE board (id INT AUTO_INCREMENT NOT NULL, title VARCHAR(160) NOT NULL, description LONGTEXT DEFAULT NULL, background VARCHAR(40) NOT NULL, created_at DATETIME NOT NULL, owner_id INT NOT NULL, INDEX IDX_58562B477E3C61F9 (owner_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE board_list (id INT AUTO_INCREMENT NOT NULL, title VARCHAR(160) NOT NULL, position INT NOT NULL, color VARCHAR(20) DEFAULT NULL, board_id INT NOT NULL, INDEX IDX_9E5EA13BE7EC5785 (board_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE board_member (id INT AUTO_INCREMENT NOT NULL, role VARCHAR(20) NOT NULL, favorite TINYINT NOT NULL, joined_at DATETIME NOT NULL, board_id INT NOT NULL, user_id INT NOT NULL, UNIQUE INDEX UNIQ_DCFABEDFE7EC5785A76ED395 (board_id, user_id), INDEX IDX_DCFABEDFE7EC5785 (board_id), INDEX IDX_DCFABEDFA76ED395 (user_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE card (id INT AUTO_INCREMENT NOT NULL, title VARCHAR(255) NOT NULL, description LONGTEXT DEFAULT NULL, position INT NOT NULL, due_date DATETIME DEFAULT NULL, completed TINYINT NOT NULL, cover VARCHAR(20) DEFAULT NULL, priority VARCHAR(10) NOT NULL, created_at DATETIME NOT NULL, list_id INT NOT NULL, created_by_id INT DEFAULT NULL, INDEX IDX_161498D33DAE168B (list_id), INDEX IDX_161498D3B03A8386 (created_by_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE card_assignee (card_id INT NOT NULL, user_id INT NOT NULL, INDEX IDX_C5AC328F4ACC9A20 (card_id), INDEX IDX_C5AC328FA76ED395 (user_id), PRIMARY KEY (card_id, user_id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE card_label (card_id INT NOT NULL, label_id INT NOT NULL, INDEX IDX_3693A12E4ACC9A20 (card_id), INDEX IDX_3693A12E33B92F39 (label_id), PRIMARY KEY (card_id, label_id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE checklist_item (id INT AUTO_INCREMENT NOT NULL, content VARCHAR(255) NOT NULL, done TINYINT NOT NULL, position INT NOT NULL, card_id INT NOT NULL, INDEX IDX_99EB20F94ACC9A20 (card_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE comment (id INT AUTO_INCREMENT NOT NULL, content LONGTEXT NOT NULL, created_at DATETIME NOT NULL, card_id INT NOT NULL, author_id INT NOT NULL, INDEX IDX_9474526C4ACC9A20 (card_id), INDEX IDX_9474526CF675F31B (author_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE custom_field (id INT AUTO_INCREMENT NOT NULL, name VARCHAR(80) NOT NULL, type VARCHAR(20) NOT NULL, options JSON NOT NULL, show_on_card TINYINT NOT NULL, position INT NOT NULL, board_id INT NOT NULL, INDEX IDX_98F8BD31E7EC5785 (board_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE custom_field_value (id INT AUTO_INCREMENT NOT NULL, value JSON DEFAULT NULL, card_id INT NOT NULL, field_id INT NOT NULL, UNIQUE INDEX UNIQ_EC7B054ACC9A20443707B0 (card_id, field_id), INDEX IDX_EC7B054ACC9A20 (card_id), INDEX IDX_EC7B05443707B0 (field_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE invitation (id INT AUTO_INCREMENT NOT NULL, email VARCHAR(180) NOT NULL, created_at DATETIME NOT NULL, board_id INT NOT NULL, invited_by_id INT DEFAULT NULL, UNIQUE INDEX UNIQ_F11D61A2E7EC5785E7927C74 (board_id, email), INDEX IDX_F11D61A2E7EC5785 (board_id), INDEX IDX_F11D61A2A7B4A7E3 (invited_by_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE label (id INT AUTO_INCREMENT NOT NULL, name VARCHAR(60) NOT NULL, color VARCHAR(20) NOT NULL, board_id INT NOT NULL, INDEX IDX_EA750E8E7EC5785 (board_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE notification (id INT AUTO_INCREMENT NOT NULL, type VARCHAR(40) NOT NULL, message VARCHAR(500) NOT NULL, is_read TINYINT NOT NULL, created_at DATETIME NOT NULL, recipient_id INT NOT NULL, actor_id INT DEFAULT NULL, board_id INT DEFAULT NULL, card_id INT DEFAULT NULL, INDEX IDX_BF5476CAE92F8F78DA46F46 (recipient_id, is_read), INDEX IDX_BF5476CAE92F8F78 (recipient_id), INDEX IDX_BF5476CA10DAF24A (actor_id), INDEX IDX_BF5476CAE7EC5785 (board_id), INDEX IDX_BF5476CA4ACC9A20 (card_id), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('CREATE TABLE `user` (id INT AUTO_INCREMENT NOT NULL, email VARCHAR(180) NOT NULL, name VARCHAR(120) NOT NULL, password VARCHAR(255) NOT NULL, avatar_color VARCHAR(20) NOT NULL, roles JSON NOT NULL, created_at DATETIME NOT NULL, UNIQUE INDEX UNIQ_8D93D649E7927C74 (email), PRIMARY KEY (id)) DEFAULT CHARACTER SET utf8mb4 COLLATE `utf8mb4_unicode_ci`');
        $this->addSql('ALTER TABLE board ADD CONSTRAINT FK_58562B477E3C61F9 FOREIGN KEY (owner_id) REFERENCES `user` (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE board_list ADD CONSTRAINT FK_9E5EA13BE7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE board_member ADD CONSTRAINT FK_DCFABEDFE7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE board_member ADD CONSTRAINT FK_DCFABEDFA76ED395 FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE card ADD CONSTRAINT FK_161498D33DAE168B FOREIGN KEY (list_id) REFERENCES board_list (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE card ADD CONSTRAINT FK_161498D3B03A8386 FOREIGN KEY (created_by_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('ALTER TABLE card_assignee ADD CONSTRAINT FK_C5AC328F4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE card_assignee ADD CONSTRAINT FK_C5AC328FA76ED395 FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE card_label ADD CONSTRAINT FK_3693A12E4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE card_label ADD CONSTRAINT FK_3693A12E33B92F39 FOREIGN KEY (label_id) REFERENCES label (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE checklist_item ADD CONSTRAINT FK_99EB20F94ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE comment ADD CONSTRAINT FK_9474526C4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE comment ADD CONSTRAINT FK_9474526CF675F31B FOREIGN KEY (author_id) REFERENCES `user` (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE custom_field ADD CONSTRAINT FK_98F8BD31E7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE custom_field_value ADD CONSTRAINT FK_EC7B054ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE custom_field_value ADD CONSTRAINT FK_EC7B05443707B0 FOREIGN KEY (field_id) REFERENCES custom_field (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE invitation ADD CONSTRAINT FK_F11D61A2E7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE invitation ADD CONSTRAINT FK_F11D61A2A7B4A7E3 FOREIGN KEY (invited_by_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('ALTER TABLE label ADD CONSTRAINT FK_EA750E8E7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE notification ADD CONSTRAINT FK_BF5476CAE92F8F78 FOREIGN KEY (recipient_id) REFERENCES `user` (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE notification ADD CONSTRAINT FK_BF5476CA10DAF24A FOREIGN KEY (actor_id) REFERENCES `user` (id) ON DELETE SET NULL');
        $this->addSql('ALTER TABLE notification ADD CONSTRAINT FK_BF5476CAE7EC5785 FOREIGN KEY (board_id) REFERENCES board (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE notification ADD CONSTRAINT FK_BF5476CA4ACC9A20 FOREIGN KEY (card_id) REFERENCES card (id) ON DELETE CASCADE');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE board DROP FOREIGN KEY FK_58562B477E3C61F9');
        $this->addSql('ALTER TABLE board_list DROP FOREIGN KEY FK_9E5EA13BE7EC5785');
        $this->addSql('ALTER TABLE board_member DROP FOREIGN KEY FK_DCFABEDFE7EC5785');
        $this->addSql('ALTER TABLE board_member DROP FOREIGN KEY FK_DCFABEDFA76ED395');
        $this->addSql('ALTER TABLE card DROP FOREIGN KEY FK_161498D33DAE168B');
        $this->addSql('ALTER TABLE card DROP FOREIGN KEY FK_161498D3B03A8386');
        $this->addSql('ALTER TABLE card_assignee DROP FOREIGN KEY FK_C5AC328F4ACC9A20');
        $this->addSql('ALTER TABLE card_assignee DROP FOREIGN KEY FK_C5AC328FA76ED395');
        $this->addSql('ALTER TABLE card_label DROP FOREIGN KEY FK_3693A12E4ACC9A20');
        $this->addSql('ALTER TABLE card_label DROP FOREIGN KEY FK_3693A12E33B92F39');
        $this->addSql('ALTER TABLE checklist_item DROP FOREIGN KEY FK_99EB20F94ACC9A20');
        $this->addSql('ALTER TABLE comment DROP FOREIGN KEY FK_9474526C4ACC9A20');
        $this->addSql('ALTER TABLE comment DROP FOREIGN KEY FK_9474526CF675F31B');
        $this->addSql('ALTER TABLE custom_field DROP FOREIGN KEY FK_98F8BD31E7EC5785');
        $this->addSql('ALTER TABLE custom_field_value DROP FOREIGN KEY FK_EC7B054ACC9A20');
        $this->addSql('ALTER TABLE custom_field_value DROP FOREIGN KEY FK_EC7B05443707B0');
        $this->addSql('ALTER TABLE invitation DROP FOREIGN KEY FK_F11D61A2E7EC5785');
        $this->addSql('ALTER TABLE invitation DROP FOREIGN KEY FK_F11D61A2A7B4A7E3');
        $this->addSql('ALTER TABLE label DROP FOREIGN KEY FK_EA750E8E7EC5785');
        $this->addSql('ALTER TABLE notification DROP FOREIGN KEY FK_BF5476CAE92F8F78');
        $this->addSql('ALTER TABLE notification DROP FOREIGN KEY FK_BF5476CA10DAF24A');
        $this->addSql('ALTER TABLE notification DROP FOREIGN KEY FK_BF5476CAE7EC5785');
        $this->addSql('ALTER TABLE notification DROP FOREIGN KEY FK_BF5476CA4ACC9A20');
        $this->addSql('DROP TABLE board');
        $this->addSql('DROP TABLE board_list');
        $this->addSql('DROP TABLE board_member');
        $this->addSql('DROP TABLE card');
        $this->addSql('DROP TABLE card_assignee');
        $this->addSql('DROP TABLE card_label');
        $this->addSql('DROP TABLE checklist_item');
        $this->addSql('DROP TABLE comment');
        $this->addSql('DROP TABLE custom_field');
        $this->addSql('DROP TABLE custom_field_value');
        $this->addSql('DROP TABLE invitation');
        $this->addSql('DROP TABLE label');
        $this->addSql('DROP TABLE notification');
        $this->addSql('DROP TABLE `user`');
    }
}
