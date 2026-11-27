<?php

namespace App\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;

/**
 * Reçu d'un événement interne déjà traité (idempotence par eventId de POST /internal/notifications,
 * puis /internal/storage/release en T5) ; écrit dans la même transaction que l'effet
 * (board-migration.md §2.1). Table créée par une migration écrite à la main (R1, R10).
 */
#[ORM\Entity]
#[ORM\Table(name: 'internal_event_receipt')]
class InternalEventReceipt
{
    #[ORM\Id]
    #[ORM\Column(name: 'event_id', type: Types::STRING, length: 36, options: ['fixed' => true])]
    private string $eventId;

    #[ORM\Column(type: Types::STRING, length: 64)]
    private string $type;

    #[ORM\Column(name: 'received_at', type: Types::DATETIME_IMMUTABLE)]
    private \DateTimeImmutable $receivedAt;

    public function __construct(string $eventId, string $type)
    {
        $this->eventId = $eventId;
        $this->type = $type;
        $this->receivedAt = new \DateTimeImmutable();
    }

    public function getEventId(): string { return $this->eventId; }
    public function getType(): string { return $this->type; }
    public function getReceivedAt(): \DateTimeImmutable { return $this->receivedAt; }
}
