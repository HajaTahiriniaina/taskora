<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
class ChecklistItem
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'checklist')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\Column(length: 255)]
    private string $content;

    #[ORM\Column]
    private bool $done = false;

    #[ORM\Column]
    private int $position = 0;

    public function __construct(Card $card, string $content, int $position)
    {
        $this->card = $card;
        $this->content = $content;
        $this->position = $position;
    }

    public function getId(): ?int { return $this->id; }
    public function getCard(): Card { return $this->card; }
    public function getContent(): string { return $this->content; }
    public function setContent(string $c): static { $this->content = $c; return $this; }
    public function isDone(): bool { return $this->done; }
    public function setDone(bool $d): static { $this->done = $d; return $this; }
    public function getPosition(): int { return $this->position; }
}
