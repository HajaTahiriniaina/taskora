<?php

namespace App\Entity;

use App\Repository\AttachmentRepository;
use Doctrine\ORM\Mapping as ORM;

/**
 * File attached to a card: added directly (button, drag & drop) or through a comment
 * (then $comment is set). The bytes live in FileStorage under $storageKey,
 * delivered at "/uploads/<storageKey>".
 */
#[ORM\Entity(repositoryClass: AttachmentRepository::class)]
#[ORM\Index(name: 'idx_attachment_card_date', columns: ['card_id', 'created_at'])]
#[ORM\Index(name: 'idx_attachment_thumb', columns: ['thumb_key'])]
#[ORM\Index(name: 'idx_attachment_preview', columns: ['preview_key'])]
class Attachment
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'attachments')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $uploader;

    /** Set when the file was posted in a comment; the row goes away with the comment. */
    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    private ?Comment $comment = null;

    #[ORM\Column(length: 255)]
    private string $name;

    #[ORM\Column(length: 255, unique: true)]
    private string $storageKey;

    #[ORM\Column(length: 120)]
    private string $mimeType;

    /** Small WebP version of an image (covers, thumbnails); null = use the original. */
    #[ORM\Column(length: 255, nullable: true)]
    private ?string $thumbKey = null;

    /** PDF rendering of an Office / LibreOffice document, made on first preview. */
    #[ORM\Column(length: 255, nullable: true)]
    private ?string $previewKey = null;

    #[ORM\Column]
    private int $size;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    public function __construct(Card $card, ?User $uploader, string $name, string $storageKey, string $mimeType, int $size, ?Comment $comment = null)
    {
        $this->card = $card;
        $this->uploader = $uploader;
        $this->name = mb_substr($name, 0, 255);
        $this->storageKey = $storageKey;
        $this->mimeType = mb_substr($mimeType, 0, 120);
        $this->size = $size;
        $this->comment = $comment;
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getCard(): Card { return $this->card; }
    public function getUploader(): ?User { return $this->uploader; }
    public function getComment(): ?Comment { return $this->comment; }
    public function getName(): string { return $this->name; }
    public function setName(string $name): static { $this->name = mb_substr($name, 0, 255); return $this; }
    public function getStorageKey(): string { return $this->storageKey; }
    public function getMimeType(): string { return $this->mimeType; }
    public function getSize(): int { return $this->size; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
    public function setCreatedAt(\DateTimeImmutable $d): static { $this->createdAt = $d; return $this; }
    public function getThumbKey(): ?string { return $this->thumbKey; }
    public function setThumbKey(?string $k): static { $this->thumbKey = $k; return $this; }
    public function getPreviewKey(): ?string { return $this->previewKey; }
    public function setPreviewKey(?string $k): static { $this->previewKey = $k; return $this; }

    /** Every storage object of this attachment (original, thumbnail, PDF preview). */
    public function getAllKeys(): array
    {
        return array_values(array_filter([$this->storageKey, $this->thumbKey, $this->previewKey]));
    }

    /** Lower-case extension of the display name ("rapport.DOCX" → "docx"). */
    public function getExtension(): string
    {
        return strtolower(pathinfo($this->name, PATHINFO_EXTENSION) ?: pathinfo($this->storageKey, PATHINFO_EXTENSION));
    }

    public function isImage(): bool
    {
        return (bool) preg_match('/\.(png|jpg|gif|webp)$/', $this->storageKey);
    }
}
