<?php

namespace App\Entity;

use App\Repository\UserRepository;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Security\Core\User\PasswordAuthenticatedUserInterface;
use Symfony\Component\Security\Core\User\UserInterface;

#[ORM\Entity(repositoryClass: UserRepository::class)]
#[ORM\Table(name: '`user`')]
class User implements UserInterface, PasswordAuthenticatedUserInterface
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\Column(length: 180, unique: true)]
    private string $email = '';

    #[ORM\Column(length: 120)]
    private string $name = '';

    #[ORM\Column]
    private string $password = '';

    #[ORM\Column(length: 20)]
    private string $avatarColor = '#6366f1';

    #[ORM\Column(type: 'json')]
    private array $roles = [];

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    public function __construct()
    {
        $this->createdAt = new \DateTimeImmutable();
        $palette = ['#6366f1', '#ec4899', '#14b8a6', '#f59e0b', '#8b5cf6', '#ef4444', '#0ea5e9', '#22c55e'];
        $this->avatarColor = $palette[array_rand($palette)];
    }

    public function getId(): ?int { return $this->id; }
    public function getEmail(): string { return $this->email; }
    public function setEmail(string $email): static { $this->email = mb_strtolower(trim($email)); return $this; }
    public function getName(): string { return $this->name; }
    public function setName(string $name): static { $this->name = trim($name); return $this; }
    public function getPassword(): string { return $this->password; }
    public function setPassword(string $password): static { $this->password = $password; return $this; }
    public function getAvatarColor(): string { return $this->avatarColor; }
    public function setAvatarColor(string $c): static { $this->avatarColor = $c; return $this; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }

    public function getUserIdentifier(): string { return $this->email; }
    public function getRoles(): array { return array_values(array_unique([...$this->roles, 'ROLE_USER'])); }
    public function eraseCredentials(): void {}
}
