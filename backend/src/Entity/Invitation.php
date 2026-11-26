<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

/** Pending invitation for an e-mail that has no account yet. Consumed at sign-up. */
#[ORM\Entity]
#[ORM\UniqueConstraint(columns: ['board_id', 'email'])]
class Invitation
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\Column(length: 180)]
    private string $email;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?User $invitedBy;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    public function __construct(Board $board, string $email, ?User $invitedBy)
    {
        $this->board = $board;
        $this->email = mb_strtolower(trim($email));
        $this->invitedBy = $invitedBy;
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getEmail(): string { return $this->email; }
    public function getInvitedBy(): ?User { return $this->invitedBy; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
}
