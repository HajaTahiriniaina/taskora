<?php

namespace App\Entity;

use App\Repository\NotificationRepository;
use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity(repositoryClass: NotificationRepository::class)]
#[ORM\Index(columns: ['recipient_id', 'is_read'])]
class Notification
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $recipient;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $actor;

    #[ORM\Column(length: 40)]
    private string $type;

    #[ORM\Column(length: 500)]
    private string $message;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    private ?Board $board;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    private ?Card $card;

    #[ORM\Column(name: 'is_read')]
    private bool $read = false;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    public function __construct(User $recipient, ?User $actor, string $type, string $message, ?Board $board = null, ?Card $card = null)
    {
        $this->recipient = $recipient;
        $this->actor = $actor;
        $this->type = $type;
        $this->message = $message;
        $this->board = $board;
        $this->card = $card;
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getRecipient(): User { return $this->recipient; }
    public function getActor(): ?User { return $this->actor; }
    public function getType(): string { return $this->type; }
    public function getMessage(): string { return $this->message; }
    public function getBoard(): ?Board { return $this->board; }
    public function getCard(): ?Card { return $this->card; }
    public function isRead(): bool { return $this->read; }
    public function setRead(bool $r): static { $this->read = $r; return $this; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
}
