<?php

namespace App\Entity;

use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity(repositoryClass: \App\Repository\CardRepository::class)]
#[ORM\Index(name: 'idx_card_list_position', columns: ['list_id', 'position'])]
#[ORM\UniqueConstraint(name: 'uniq_card_board_number', columns: ['board_id', 'number'])]
class Card
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'cards')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private BoardList $list;

    /** Denormalized board (cards never leave their board): enables per-board numbering. */
    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    /** Human card number, unique and never reused inside a board (#1, #2…). */
    #[ORM\Column]
    private int $number = 0;

    #[ORM\Column(length: 255)]
    private string $title;

    #[ORM\Column(type: 'text', nullable: true)]
    private ?string $description = null;

    #[ORM\Column]
    private int $position = 0;

    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $dueDate = null;

    #[ORM\Column]
    private bool $completed = false;

    /** Trash: set when deleted, purged for good after Trash::RETENTION_DAYS. */
    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $deletedAt = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $deletedBy = null;

    /** Archived cards leave the board (position -1) and are listed in the board's archives. */
    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $archivedAt = null;

    #[ORM\Column(length: 20, nullable: true)]
    private ?string $cover = null;

    /** Image attachment used as cover (takes precedence over the color). */
    #[ORM\ManyToOne(targetEntity: Attachment::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Attachment $coverAttachment = null;

    #[ORM\Column(length: 10)]
    private string $priority = 'none';

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $createdBy = null;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    /** @var Collection<int, User> */
    #[ORM\ManyToMany(targetEntity: User::class)]
    #[ORM\JoinTable(name: 'card_assignee')]
    private Collection $assignees;

    /** @var Collection<int, Label> */
    #[ORM\ManyToMany(targetEntity: Label::class)]
    #[ORM\JoinTable(name: 'card_label')]
    private Collection $labels;

    /** @var Collection<int, Comment> */
    #[ORM\OneToMany(targetEntity: Comment::class, mappedBy: 'card', fetch: 'EXTRA_LAZY')]
    #[ORM\OrderBy(['createdAt' => 'DESC'])]
    private Collection $comments;

    /** @var Collection<int, ChecklistItem> */
    #[ORM\OneToMany(targetEntity: ChecklistItem::class, mappedBy: 'card', fetch: 'EXTRA_LAZY')]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $checklist;

    /** @var Collection<int, Attachment> */
    #[ORM\OneToMany(targetEntity: Attachment::class, mappedBy: 'card', fetch: 'EXTRA_LAZY')]
    #[ORM\OrderBy(['createdAt' => 'DESC', 'id' => 'DESC'])]
    private Collection $attachments;

    /** @var Collection<int, CustomFieldValue> */
    #[ORM\OneToMany(targetEntity: CustomFieldValue::class, mappedBy: 'card')]
    private Collection $customValues;

    public function __construct(BoardList $list, string $title, int $position, ?User $createdBy = null, int $number = 0)
    {
        $this->list = $list;
        $this->board = $list->getBoard();
        $this->number = $number;
        $this->title = $title;
        $this->position = $position;
        $this->createdBy = $createdBy;
        $this->createdAt = new \DateTimeImmutable();
        $this->assignees = new ArrayCollection();
        $this->labels = new ArrayCollection();
        $this->comments = new ArrayCollection();
        $this->checklist = new ArrayCollection();
        $this->attachments = new ArrayCollection();
        $this->customValues = new ArrayCollection();
    }

    public function getId(): ?int { return $this->id; }
    public function getList(): BoardList { return $this->list; }
    public function setList(BoardList $l): static { $this->list = $l; return $this; }
    public function getBoard(): Board { return $this->board; }
    public function getNumber(): int { return $this->number; }
    public function setCreatedAt(\DateTimeImmutable $d): static { $this->createdAt = $d; return $this; }
    public function getTitle(): string { return $this->title; }
    public function setTitle(string $t): static { $this->title = $t; return $this; }
    public function getDescription(): ?string { return $this->description; }
    public function setDescription(?string $d): static { $this->description = $d; return $this; }
    public function getPosition(): int { return $this->position; }
    public function setPosition(int $p): static { $this->position = $p; return $this; }
    public function getDueDate(): ?\DateTimeImmutable { return $this->dueDate; }
    public function setDueDate(?\DateTimeImmutable $d): static { $this->dueDate = $d; return $this; }
    public function isCompleted(): bool { return $this->completed; }
    public function setCompleted(bool $c): static { $this->completed = $c; return $this; }
    public function getCover(): ?string { return $this->cover; }
    public function getArchivedAt(): ?\DateTimeImmutable { return $this->archivedAt; }
    public function setArchivedAt(?\DateTimeImmutable $d): static { $this->archivedAt = $d; return $this; }
    public function isArchived(): bool { return null !== $this->archivedAt; }
    public function getDeletedAt(): ?\DateTimeImmutable { return $this->deletedAt; }
    public function getDeletedBy(): ?User { return $this->deletedBy; }
    public function isDeleted(): bool { return null !== $this->deletedAt; }
    public function trash(?User $by): static { $this->deletedAt = new \DateTimeImmutable(); $this->deletedBy = $by; return $this; }
    public function untrash(): static { $this->deletedAt = null; $this->deletedBy = null; return $this; }
    public function setCover(?string $c): static { $this->cover = $c; return $this; }
    public function getCoverAttachment(): ?Attachment { return $this->coverAttachment; }
    public function setCoverAttachment(?Attachment $a): static { $this->coverAttachment = $a; return $this; }
    public function getPriority(): string { return $this->priority; }
    public function setPriority(string $p): static { $this->priority = $p; return $this; }
    public function getCreatedBy(): ?User { return $this->createdBy; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
    /** @return Collection<int, User> */
    public function getAssignees(): Collection { return $this->assignees; }
    /** @return Collection<int, Label> */
    public function getLabels(): Collection { return $this->labels; }
    /** @return Collection<int, Comment> */
    public function getComments(): Collection { return $this->comments; }
    /** @return Collection<int, ChecklistItem> */
    public function getChecklist(): Collection { return $this->checklist; }
    /** @return Collection<int, Attachment> */
    public function getAttachments(): Collection { return $this->attachments; }
    /** @return Collection<int, CustomFieldValue> */
    public function getCustomValues(): Collection { return $this->customValues; }
}
