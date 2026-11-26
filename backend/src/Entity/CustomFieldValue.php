<?php

namespace App\Entity;

use Doctrine\ORM\Mapping as ORM;

#[ORM\Entity]
#[ORM\UniqueConstraint(columns: ['card_id', 'field_id'])]
class CustomFieldValue
{
    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne(inversedBy: 'customValues')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Card $card;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private CustomField $field;

    #[ORM\Column(type: 'json', nullable: true)]
    private mixed $value = null;

    public function __construct(Card $card, CustomField $field)
    {
        $this->card = $card;
        $this->field = $field;
    }

    public function getId(): ?int { return $this->id; }
    public function getCard(): Card { return $this->card; }
    public function getField(): CustomField { return $this->field; }
    public function getValue(): mixed { return $this->value; }
    public function setValue(mixed $v): static { $this->value = $v; return $this; }
}
