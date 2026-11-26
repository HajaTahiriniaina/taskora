<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
class Label
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'labels')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\Column(length: 60)]
    private string $name;

    #[ORM\Column(length: 20)]
    private string $color;

    public function __construct(Board $board, string $name, string $color)
    {
        $this->board = $board;
        $this->name = $name;
        $this->color = $color;
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getName(): string { return $this->name; }
    public function setName(string $n): static { $this->name = $n; return $this; }
    public function getColor(): string { return $this->color; }
    public function setColor(string $c): static { $this->color = $c; return $this; }
}
