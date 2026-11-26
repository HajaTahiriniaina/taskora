<?php

namespace App\Entity;

use App\Repository\PushSubscriptionRepository;
use Doctrine\ORM\Mapping as ORM;

/**
 * A device that receives notifications: a browser (Web Push) today, a mobile app (FCM) later.
 * One row per device; the endpoint (unique per browser profile) is matched through its hash.
 */
#[ORM\Entity(repositoryClass: PushSubscriptionRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_push_endpoint', columns: ['endpoint_hash'])]
class PushSubscription
{
    public const KIND_WEB = 'web';

    #[ORM\Id, ORM\GeneratedValue, ORM\Column]
    private ?int $id = null;

    #[ORM\ManyToOne]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    /** web (Web Push) — fcm will come with the mobile app */
    #[ORM\Column(length: 10)]
    private string $kind = self::KIND_WEB;

    #[ORM\Column(type: 'text')]
    private string $endpoint;

    #[ORM\Column(length: 64)]
    private string $endpointHash;

    #[ORM\Column(length: 200)]
    private string $p256dh;

    #[ORM\Column(length: 100)]
    private string $auth;

    /** Shown in the profile ("Chrome · macOS") */
    #[ORM\Column(length: 120, nullable: true)]
    private ?string $device = null;

    #[ORM\Column]
    private \DateTimeImmutable $createdAt;

    #[ORM\Column(nullable: true)]
    private ?\DateTimeImmutable $lastSuccessAt = null;

    public function __construct(User $user, string $endpoint, string $p256dh, string $auth, ?string $device)
    {
        $this->user = $user;
        $this->createdAt = new \DateTimeImmutable();
        $this->update($endpoint, $p256dh, $auth, $device);
    }

    public static function hash(string $endpoint): string
    {
        return hash('sha256', $endpoint);
    }

    public function update(string $endpoint, string $p256dh, string $auth, ?string $device): void
    {
        $this->endpoint = $endpoint;
        $this->endpointHash = self::hash($endpoint);
        $this->p256dh = $p256dh;
        $this->auth = $auth;
        $this->device = $device ? mb_substr($device, 0, 120) : null;
    }

    public function getId(): ?int { return $this->id; }
    public function getUser(): User { return $this->user; }
    public function setUser(User $u): static { $this->user = $u; return $this; }
    public function getKind(): string { return $this->kind; }
    public function getEndpoint(): string { return $this->endpoint; }
    public function getP256dh(): string { return $this->p256dh; }
    public function getAuth(): string { return $this->auth; }
    public function getDevice(): ?string { return $this->device; }
    public function getCreatedAt(): \DateTimeImmutable { return $this->createdAt; }
    public function getLastSuccessAt(): ?\DateTimeImmutable { return $this->lastSuccessAt; }
    public function touch(): void { $this->lastSuccessAt = new \DateTimeImmutable(); }
}
