<?php

namespace App\Entity;

use App\Repository\BoardRepository;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity(repositoryClass: BoardRepository::class)]
class Board
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\Column(length: 160)]
    private string $title = '';


    /** Trash: set when deleted, purged for good after Trash::RETENTION_DAYS. */
    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $deletedAt = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $deletedBy = null;
    #[ORM\Column(type: 'text', nullable: true)]
    private ?string $description = null;

    #[ORM\Column(length: 40)]
    private string $background = 'aurora';

    /** Last card number handed out (incremented atomically, see CardRepository::nextNumber). */
    #[ORM\Column(options: ['default' => 0])]
    private int $cardSeq = 0;

    #[ORM\Column(options: ['default' => true])]
    private bool $showCardNumbers = true;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $owner;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    /** @var Collection<int, BoardMember> */
    #[ORM\OneToMany(targetEntity: BoardMember::class, mappedBy: 'board', cascade: ['persist', 'remove'], orphanRemoval: true)]
    private Collection $members;

    /** @var Collection<int, BoardList> */
    #[ORM\OneToMany(targetEntity: BoardList::class, mappedBy: 'board', fetch: 'EXTRA_LAZY')]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $lists;

    /** @var Collection<int, Label> */
    #[ORM\OneToMany(targetEntity: Label::class, mappedBy: 'board')]
    private Collection $labels;

    /** @var Collection<int, CustomField> */
    #[ORM\OneToMany(targetEntity: CustomField::class, mappedBy: 'board')]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $customFields;

    public function __construct(User $owner, string $title)
    {
        $this->owner = $owner;
        $this->title = $title;
        $this->createdAt = new \DateTimeImmutable();
        $this->members = new ArrayCollection();
        $this->lists = new ArrayCollection();
        $this->labels = new ArrayCollection();
        $this->customFields = new ArrayCollection();
    }

    public function getId(): ?int { return $this->id; }
    public function getTitle(): string { return $this->title; }
    public function setTitle(string $t): static { $this->title = $t; return $this; }
    public function getDescription(): ?string { return $this->description; }
    public function setDescription(?string $d): static { $this->description = $d; return $this; }
    public function getBackground(): string { return $this->background; }
    public function setBackground(string $b): static { $this->background = $b; return $this; }
    public function getOwner(): User { return $this->owner; }
    public function getCardSeq(): int { return $this->cardSeq; }
    public function setCardSeq(int $s): static { $this->cardSeq = $s; return $this; }
    public function isShowCardNumbers(): bool { return $this->showCardNumbers; }
    public function setShowCardNumbers(bool $s): static { $this->showCardNumbers = $s; return $this; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
    /** @return Collection<int, BoardMember> */
    public function getMembers(): Collection { return $this->members; }
    public function addMember(BoardMember $m): static { if (!$this->members->contains($m)) { $this->members->add($m); } return $this; }
    public function removeMember(BoardMember $m): static { $this->members->removeElement($m); return $this; }
    /** @return Collection<int, BoardList> */
    public function getLists(): Collection { return $this->lists; }
    public function getDeletedAt(): ?\DateTimeImmutable { return $this->deletedAt; }
    public function getDeletedBy(): ?User { return $this->deletedBy; }
    public function isDeleted(): bool { return null !== $this->deletedAt; }
    public function trash(?User $by): static { $this->deletedAt = new \DateTimeImmutable(); $this->deletedBy = $by; return $this; }
    public function untrash(): static { $this->deletedAt = null; $this->deletedBy = null; return $this; }

    /** @return BoardList[] lists shown on the board (not archived), in order */
    public function getOpenLists(): array
    {
        return array_values(array_filter($this->lists->toArray(), fn (BoardList $l) => !$l->isArchived() && !$l->isDeleted()));
    }
    /** @return Collection<int, Label> */
    public function getLabels(): Collection { return $this->labels; }
    /** @return Collection<int, CustomField> */
    public function getCustomFields(): Collection { return $this->customFields; }

    public function getMembership(User $user): ?BoardMember
    {
        foreach ($this->members as $m) {
            if ($m->getUser()->getId() === $user->getId()) {
                return $m;
            }
        }
        return null;
    }

    /** @return User[] */
    public function getMemberUsers(): array
    {
        return array_map(fn (BoardMember $m) => $m->getUser(), $this->members->toArray());
    }
}
