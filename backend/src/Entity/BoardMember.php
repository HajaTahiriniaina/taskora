<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
#[ORM\UniqueConstraint(columns: ['board_id', 'user_id'])]
class BoardMember
{
    public const ROLE_OWNER = 'owner';
    public const ROLE_ADMIN = 'admin';
    public const ROLE_MEMBER = 'member';

    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'members')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 20)]
    private string $role;

    #[ORM\Column]
    private bool $favorite = false;

    #[ORM\Column]
    private \DateTimeImmutable $joinedAt;

    public function __construct(Board $board, User $user, string $role = self::ROLE_MEMBER)
    {
        $this->board = $board;
        $this->user = $user;
        $this->role = $role;
        $this->joinedAt = new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getUser(): User { return $this->user; }
    public function getRole(): string { return $this->role; }
    public function setRole(string $r): static { $this->role = $r; return $this; }
    public function isFavorite(): bool { return $this->favorite; }
    public function setFavorite(bool $f): static { $this->favorite = $f; return $this; }
    public function getJoinedAt(): \DateTimeImmutable { return $this->joinedAt; }
    public function canManage(): bool { return in_array($this->role, [self::ROLE_OWNER, self::ROLE_ADMIN], true); }
}
