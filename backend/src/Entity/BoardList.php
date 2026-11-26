<?php

namespace App\Entity;

use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
#[ORM\Table(name: 'board_list')]
class BoardList
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'lists')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\Column(length: 160)]
    private string $title;

    #[ORM\Column]
    private int $position = 0;

    /** Trash: set when deleted, purged for good after Trash::RETENTION_DAYS. */
    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $deletedAt = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $deletedBy = null;

    /** Archived lists (and their cards) leave the board and are listed in its archives. */
    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $archivedAt = null;

    #[ORM\Column(length: 20, nullable: true)]
    private ?string $color = null;

    /** @var Collection<int, Card> */
    #[ORM\OneToMany(targetEntity: Card::class, mappedBy: 'list', fetch: 'EXTRA_LAZY')]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $cards;

    public function __construct(Board $board, string $title, int $position)
    {
        $this->board = $board;
        $this->title = $title;
        $this->position = $position;
        $this->cards = new ArrayCollection();
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getTitle(): string { return $this->title; }
    public function setTitle(string $t): static { $this->title = $t; return $this; }
    public function getPosition(): int { return $this->position; }
    public function setPosition(int $p): static { $this->position = $p; return $this; }
    public function getColor(): ?string { return $this->color; }
    public function getArchivedAt(): ?\DateTimeImmutable { return $this->archivedAt; }
    public function setArchivedAt(?\DateTimeImmutable $d): static { $this->archivedAt = $d; return $this; }
    public function isArchived(): bool { return null !== $this->archivedAt; }
    public function getDeletedAt(): ?\DateTimeImmutable { return $this->deletedAt; }
    public function getDeletedBy(): ?User { return $this->deletedBy; }
    public function isDeleted(): bool { return null !== $this->deletedAt; }
    public function trash(?User $by): static { $this->deletedAt = new \DateTimeImmutable(); $this->deletedBy = $by; return $this; }
    public function untrash(): static { $this->deletedAt = null; $this->deletedBy = null; return $this; }
    public function setColor(?string $c): static { $this->color = $c; return $this; }
    /** @return Collection<int, Card> */
    public function getCards(): Collection { return $this->cards; }
}
