<?php

namespace App\Entity;

use App\Repository\TrelloImportFileRepository;
use Doctrine\ORM\Mapping as ORM;

/**
 * A file attached on Trello, waiting to be copied into Taskora (Amazon S3) after a board import.
 * The copy is driven by the importer's browser, which holds the Trello key / token in memory only:
 * nothing allowing access to the Trello account is ever stored here.
 */
#[ORM\Entity(repositoryClass: TrelloImportFileRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_trello_file', columns: ['board_id', 'trello_id'])]
#[ORM\Index(name: 'idx_trello_file_status', columns: ['board_id', 'status'])]
class TrelloImportFile
{
    public const PENDING = 'pending';
    public const DONE = 'done';
    public const FAILED = 'failed';

    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\Column(length: 24)]
    private string $trelloId;

    #[ORM\Column(length: 1000)]
    private string $url;

    #[ORM\Column(length: 255)]
    private string $name;

    #[ORM\Column]
    private int $bytes;

    #[ORM\Column]
    private \DateTimeImmutable $date;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $uploader;

    /** The Trello card used this file as its cover. */
    #[ORM\Column]
    private bool $cover;

    #[ORM\Column(length: 10)]
    private string $status = self::PENDING;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $error = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Attachment $attachment = null;

    public function __construct(Board $board, Card $card, string $trelloId, string $url, string $name, int $bytes, \DateTimeImmutable $date, ?User $uploader, bool $cover)
    {
        $this->board = $board;
        $this->card = $card;
        $this->trelloId = $trelloId;
        $this->url = mb_substr($url, 0, 1000);
        $this->name = mb_substr($name, 0, 255) ?: 'fichier';
        $this->bytes = $bytes;
        $this->date = $date;
        $this->uploader = $uploader;
        $this->cover = $cover;
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getCard(): Card { return $this->card; }
    public function getTrelloId(): string { return $this->trelloId; }
    public function getUrl(): string { return $this->url; }
    public function getName(): string { return $this->name; }
    public function getBytes(): int { return $this->bytes; }
    public function getDate(): \DateTimeImmutable { return $this->date; }
    public function getUploader(): ?User { return $this->uploader; }
    public function isCover(): bool { return $this->cover; }
    public function getStatus(): string { return $this->status; }
    public function getError(): ?string { return $this->error; }
    public function getAttachment(): ?Attachment { return $this->attachment; }

    public function done(Attachment $attachment): void
    {
        $this->status = self::DONE;
        $this->error = null;
        $this->attachment = $attachment;
    }

    public function fail(string $error): void
    {
        $this->status = self::FAILED;
        $this->error = mb_substr($error, 0, 255);
    }

    public function retry(): void
    {
        $this->status = self::PENDING;
        $this->error = null;
    }
}
