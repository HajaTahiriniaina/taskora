-- Schéma `taskora` généré depuis backend/migrations (12 migrations Doctrine, dernière : Version20261125165023).
-- NE PAS MODIFIER À LA MAIN : docker/mariadb/tools/taskora-schema.sh (contrôle CI : --check).
-- Structure seulement (aucune donnée). Chargement : client mariadb, /docker-entrypoint-initdb.d ou ScriptUtils.
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
CREATE DATABASE IF NOT EXISTS `taskora` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `taskora`;
DROP TABLE IF EXISTS `activity`;
CREATE TABLE `activity` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `external_actor` varchar(120) DEFAULT NULL,
  `type` varchar(40) NOT NULL,
  `data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`data`)),
  `created_at` datetime NOT NULL,
  `card_id` int(11) NOT NULL,
  `actor_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_activity_card_date` (`card_id`,`created_at`),
  KEY `IDX_AC74095A4ACC9A20` (`card_id`),
  KEY `IDX_AC74095A10DAF24A` (`actor_id`),
  CONSTRAINT `FK_AC74095A10DAF24A` FOREIGN KEY (`actor_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_AC74095A4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `attachment`;
CREATE TABLE `attachment` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `mime_type` varchar(120) NOT NULL,
  `size` int(11) NOT NULL,
  `created_at` datetime NOT NULL,
  `card_id` int(11) NOT NULL,
  `uploader_id` int(11) DEFAULT NULL,
  `comment_id` int(11) DEFAULT NULL,
  `thumb_key` varchar(255) DEFAULT NULL,
  `preview_key` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_795FD9BB111795A5` (`storage_key`),
  KEY `idx_attachment_card_date` (`card_id`,`created_at`),
  KEY `IDX_795FD9BB4ACC9A20` (`card_id`),
  KEY `IDX_795FD9BB16678C77` (`uploader_id`),
  KEY `IDX_795FD9BBF8697D13` (`comment_id`),
  KEY `idx_attachment_thumb` (`thumb_key`),
  KEY `idx_attachment_preview` (`preview_key`),
  CONSTRAINT `FK_795FD9BB16678C77` FOREIGN KEY (`uploader_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_795FD9BB4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_795FD9BBF8697D13` FOREIGN KEY (`comment_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `board`;
CREATE TABLE `board` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `title` varchar(160) NOT NULL,
  `description` longtext DEFAULT NULL,
  `background` varchar(40) NOT NULL,
  `created_at` datetime NOT NULL,
  `owner_id` int(11) NOT NULL,
  `card_seq` int(11) NOT NULL DEFAULT 0,
  `show_card_numbers` tinyint(4) NOT NULL DEFAULT 1,
  `deleted_at` datetime DEFAULT NULL,
  `deleted_by_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_58562B477E3C61F9` (`owner_id`),
  KEY `IDX_58562B47C76F1F52` (`deleted_by_id`),
  CONSTRAINT `FK_58562B477E3C61F9` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_58562B47C76F1F52` FOREIGN KEY (`deleted_by_id`) REFERENCES `user` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `board_list`;
CREATE TABLE `board_list` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `title` varchar(160) NOT NULL,
  `position` int(11) NOT NULL,
  `color` varchar(20) DEFAULT NULL,
  `board_id` int(11) NOT NULL,
  `archived_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `deleted_by_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_9E5EA13BE7EC5785` (`board_id`),
  KEY `IDX_9E5EA13BC76F1F52` (`deleted_by_id`),
  CONSTRAINT `FK_9E5EA13BC76F1F52` FOREIGN KEY (`deleted_by_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_9E5EA13BE7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `board_member`;
CREATE TABLE `board_member` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `role` varchar(20) NOT NULL,
  `favorite` tinyint(4) NOT NULL,
  `joined_at` datetime NOT NULL,
  `board_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_DCFABEDFE7EC5785A76ED395` (`board_id`,`user_id`),
  KEY `IDX_DCFABEDFE7EC5785` (`board_id`),
  KEY `IDX_DCFABEDFA76ED395` (`user_id`),
  CONSTRAINT `FK_DCFABEDFA76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_DCFABEDFE7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `card`;
CREATE TABLE `card` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `title` varchar(255) NOT NULL,
  `description` longtext DEFAULT NULL,
  `position` int(11) NOT NULL,
  `due_date` datetime DEFAULT NULL,
  `completed` tinyint(4) NOT NULL,
  `cover` varchar(20) DEFAULT NULL,
  `priority` varchar(10) NOT NULL,
  `created_at` datetime NOT NULL,
  `list_id` int(11) NOT NULL,
  `created_by_id` int(11) DEFAULT NULL,
  `number` int(11) NOT NULL,
  `board_id` int(11) NOT NULL,
  `cover_attachment_id` int(11) DEFAULT NULL,
  `archived_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `deleted_by_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_card_board_number` (`board_id`,`number`),
  KEY `IDX_161498D33DAE168B` (`list_id`),
  KEY `IDX_161498D3B03A8386` (`created_by_id`),
  KEY `idx_card_list_position` (`list_id`,`position`),
  KEY `IDX_161498D3E7EC5785` (`board_id`),
  KEY `IDX_161498D357AB2477` (`cover_attachment_id`),
  KEY `IDX_161498D3C76F1F52` (`deleted_by_id`),
  CONSTRAINT `FK_161498D33DAE168B` FOREIGN KEY (`list_id`) REFERENCES `board_list` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_161498D357AB2477` FOREIGN KEY (`cover_attachment_id`) REFERENCES `attachment` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_161498D3B03A8386` FOREIGN KEY (`created_by_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_161498D3C76F1F52` FOREIGN KEY (`deleted_by_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_161498D3E7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `card_assignee`;
CREATE TABLE `card_assignee` (
  `card_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  PRIMARY KEY (`card_id`,`user_id`),
  KEY `IDX_C5AC328F4ACC9A20` (`card_id`),
  KEY `IDX_C5AC328FA76ED395` (`user_id`),
  CONSTRAINT `FK_C5AC328F4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_C5AC328FA76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `card_label`;
CREATE TABLE `card_label` (
  `card_id` int(11) NOT NULL,
  `label_id` int(11) NOT NULL,
  PRIMARY KEY (`card_id`,`label_id`),
  KEY `IDX_3693A12E4ACC9A20` (`card_id`),
  KEY `IDX_3693A12E33B92F39` (`label_id`),
  CONSTRAINT `FK_3693A12E33B92F39` FOREIGN KEY (`label_id`) REFERENCES `label` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_3693A12E4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `checklist_item`;
CREATE TABLE `checklist_item` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `content` varchar(255) NOT NULL,
  `done` tinyint(4) NOT NULL,
  `position` int(11) NOT NULL,
  `card_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_99EB20F94ACC9A20` (`card_id`),
  CONSTRAINT `FK_99EB20F94ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `content` longtext NOT NULL,
  `created_at` datetime NOT NULL,
  `card_id` int(11) NOT NULL,
  `author_id` int(11) NOT NULL,
  `external_author` varchar(120) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_9474526C4ACC9A20` (`card_id`),
  KEY `IDX_9474526CF675F31B` (`author_id`),
  CONSTRAINT `FK_9474526C4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_9474526CF675F31B` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `custom_field`;
CREATE TABLE `custom_field` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(80) NOT NULL,
  `type` varchar(20) NOT NULL,
  `options` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`options`)),
  `show_on_card` tinyint(4) NOT NULL,
  `position` int(11) NOT NULL,
  `board_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_98F8BD31E7EC5785` (`board_id`),
  CONSTRAINT `FK_98F8BD31E7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `custom_field_value`;
CREATE TABLE `custom_field_value` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `value` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`value`)),
  `card_id` int(11) NOT NULL,
  `field_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_EC7B054ACC9A20443707B0` (`card_id`,`field_id`),
  KEY `IDX_EC7B054ACC9A20` (`card_id`),
  KEY `IDX_EC7B05443707B0` (`field_id`),
  CONSTRAINT `FK_EC7B05443707B0` FOREIGN KEY (`field_id`) REFERENCES `custom_field` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_EC7B054ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `doctrine_migration_versions`;
CREATE TABLE `doctrine_migration_versions` (
  `version` varchar(191) NOT NULL,
  `executed_at` datetime DEFAULT NULL,
  `execution_time` int(11) DEFAULT NULL,
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `invitation`;
CREATE TABLE `invitation` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `email` varchar(180) NOT NULL,
  `created_at` datetime NOT NULL,
  `board_id` int(11) NOT NULL,
  `invited_by_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_F11D61A2E7EC5785E7927C74` (`board_id`,`email`),
  KEY `IDX_F11D61A2E7EC5785` (`board_id`),
  KEY `IDX_F11D61A2A7B4A7E3` (`invited_by_id`),
  CONSTRAINT `FK_F11D61A2A7B4A7E3` FOREIGN KEY (`invited_by_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_F11D61A2E7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `label`;
CREATE TABLE `label` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(60) NOT NULL,
  `color` varchar(20) NOT NULL,
  `board_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_EA750E8E7EC5785` (`board_id`),
  CONSTRAINT `FK_EA750E8E7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `notification`;
CREATE TABLE `notification` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `type` varchar(40) NOT NULL,
  `message` varchar(500) NOT NULL,
  `is_read` tinyint(4) NOT NULL,
  `created_at` datetime NOT NULL,
  `recipient_id` int(11) NOT NULL,
  `actor_id` int(11) DEFAULT NULL,
  `board_id` int(11) DEFAULT NULL,
  `card_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_BF5476CAE92F8F78DA46F46` (`recipient_id`,`is_read`),
  KEY `IDX_BF5476CAE92F8F78` (`recipient_id`),
  KEY `IDX_BF5476CA10DAF24A` (`actor_id`),
  KEY `IDX_BF5476CAE7EC5785` (`board_id`),
  KEY `IDX_BF5476CA4ACC9A20` (`card_id`),
  CONSTRAINT `FK_BF5476CA10DAF24A` FOREIGN KEY (`actor_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_BF5476CA4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_BF5476CAE7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_BF5476CAE92F8F78` FOREIGN KEY (`recipient_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `push_subscription`;
CREATE TABLE `push_subscription` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `kind` varchar(10) NOT NULL,
  `endpoint` longtext NOT NULL,
  `endpoint_hash` varchar(64) NOT NULL,
  `p256dh` varchar(200) NOT NULL,
  `auth` varchar(100) NOT NULL,
  `device` varchar(120) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `last_success_at` datetime DEFAULT NULL,
  `user_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_push_endpoint` (`endpoint_hash`),
  KEY `IDX_562830F3A76ED395` (`user_id`),
  CONSTRAINT `FK_562830F3A76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `trello_import_file`;
CREATE TABLE `trello_import_file` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `trello_id` varchar(24) NOT NULL,
  `url` varchar(1000) NOT NULL,
  `name` varchar(255) NOT NULL,
  `bytes` int(11) NOT NULL,
  `date` datetime NOT NULL,
  `cover` tinyint(4) NOT NULL,
  `status` varchar(10) NOT NULL,
  `error` varchar(255) DEFAULT NULL,
  `board_id` int(11) NOT NULL,
  `card_id` int(11) NOT NULL,
  `uploader_id` int(11) DEFAULT NULL,
  `attachment_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_trello_file` (`board_id`,`trello_id`),
  KEY `idx_trello_file_status` (`board_id`,`status`),
  KEY `IDX_9AF0D34FE7EC5785` (`board_id`),
  KEY `IDX_9AF0D34F4ACC9A20` (`card_id`),
  KEY `IDX_9AF0D34F16678C77` (`uploader_id`),
  KEY `IDX_9AF0D34F464E68B` (`attachment_id`),
  CONSTRAINT `FK_9AF0D34F16678C77` FOREIGN KEY (`uploader_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_9AF0D34F464E68B` FOREIGN KEY (`attachment_id`) REFERENCES `attachment` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FK_9AF0D34F4ACC9A20` FOREIGN KEY (`card_id`) REFERENCES `card` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_9AF0D34FE7EC5785` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `email` varchar(180) NOT NULL,
  `name` varchar(120) NOT NULL,
  `password` varchar(255) NOT NULL,
  `avatar_color` varchar(20) NOT NULL,
  `roles` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`roles`)),
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_8D93D649E7927C74` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
