<?php

namespace App\Entity;

use App\Repository\ActivityRepository;
use Doctrine\ORM\Mapping as ORM;

/**
 * One entry of a card's history ("Alice moved this card from To do to Doing").
 * `data` holds a snapshot of the names involved, so the history stays readable even if
 * lists, labels or members are renamed or deleted later.
 */
#[ORM\Entity(repositoryClass: ActivityRepository::class)]
#[ORM\Index(name: 'idx_activity_card_date', columns: ['card_id', 'created_at'])]
class Activity
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $actor;

    /** Imported actor name (e.g. Trello member without a Taskora account). */
    #[ORM\Column(length: 120, nullable: true)]
    private ?string $externalActor = null;

    #[ORM\Column(length: 40)]
    private string $type;

    #[ORM\Column(type: 'json')]
    private array $data;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    public function __construct(Card $card, ?User $actor, string $type, array $data = [], ?\DateTimeImmutable $at = null)
    {
        $this->card = $card;
        $this->actor = $actor;
        $this->type = $type;
        $this->data = $data;
        $this->createdAt = $at ?? new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getCard(): Card { return $this->card; }
    public function getActor(): ?User { return $this->actor; }
    public function getExternalActor(): ?string { return $this->externalActor; }
    public function setExternalActor(?string $n): static { $this->externalActor = $n ? mb_substr($n, 0, 120) : null; return $this; }
    public function getType(): string { return $this->type; }
    public function getData(): array { return $this->data; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
}
