<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
class CustomField
{
    public const TYPES = ['text', 'number', 'date', 'checkbox', 'select', 'url'];

    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'customFields')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Board $board;

    #[ORM\Column(length: 80)]
    private string $name;

    #[ORM\Column(length: 20)]
    private string $type;

    /** Options for "select" fields: [{id, label, color}] */
    #[ORM\Column(type: 'json')]
    private array $options = [];

    #[ORM\Column]
    private bool $showOnCard = true;

    #[ORM\Column]
    private int $position = 0;

    public function __construct(Board $board, string $name, string $type, int $position)
    {
        $this->board = $board;
        $this->name = $name;
        $this->type = $type;
        $this->position = $position;
    }

    public function getId(): ?int { return $this->id; }
    public function getBoard(): Board { return $this->board; }
    public function getName(): string { return $this->name; }
    public function setName(string $n): static { $this->name = $n; return $this; }
    public function getType(): string { return $this->type; }
    public function getOptions(): array { return $this->options; }
    public function setOptions(array $o): static { $this->options = array_values($o); return $this; }
    public function isShowOnCard(): bool { return $this->showOnCard; }
    public function setShowOnCard(bool $s): static { $this->showOnCard = $s; return $this; }
    public function getPosition(): int { return $this->position; }
}
