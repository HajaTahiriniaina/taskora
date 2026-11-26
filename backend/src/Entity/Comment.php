<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
class Comment
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'comments')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $author;

    #[ORM\Column(type: 'text')]
    private string $content;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    /** Original author name for imported comments (e.g. from Trello) without a Taskora account. */
    #[ORM\Column(length: 120, nullable: true)]
    private ?string $externalAuthor = null;

    public function __construct(Card $card, User $author, string $content)
    {
        $this->card = $card;
        $this->author = $author;
        $this->content = $content;
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): ?int { return $this->id; }
    public function getCard(): Card { return $this->card; }
    public function getAuthor(): User { return $this->author; }
    public function getContent(): string { return $this->content; }
    public function setContent(string $c): static { $this->content = $c; return $this; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
    public function setCreatedAt(\DateTimeImmutable $d): static { $this->createdAt = $d; return $this; }
    public function getExternalAuthor(): ?string { return $this->externalAuthor; }
    public function setExternalAuthor(?string $n): static { $this->externalAuthor = $n ? mb_substr($n, 0, 120) : null; return $this; }
}
