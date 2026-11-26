<?php

namespace App\Service;

use App\Entity\PushSubscription;
use App\Entity\User;
use App\Repository\PushSubscriptionRepository;
use Doctrine\ORM\EntityManagerInterface;
use GuzzleHttp\Client;
use Minishlink\WebPush\Subscription;
use Minishlink\WebPush\WebPush;
use Psr\Log\LoggerInterface;

/**
 * Browser notifications (Web Push, VAPID). Messages are collected during the request and sent
 * once the response is gone (kernel.terminate, see PushFlushSubscriber): the user never waits
 * for the push services. Devices the push service reports as gone (404 / 410) are removed.
 *
 * The endpoints are given by browsers: only the known push services are called (no SSRF).
 */
class PushSender
{
    /** Push services of Chrome / Edge / Opera (FCM), Firefox, Safari (Apple), Windows (WNS). */
    public const ALLOWED_HOSTS = '/^(fcm\.googleapis\.com|android\.googleapis\.com|updates\.push\.services\.mozilla\.com|push\.services\.mozilla\.com|[a-z0-9.-]*\.push\.apple\.com|[a-z0-9-]+\.notify\.windows\.com)$/';

    /** @var array<int, array{user: int, payload: array}> */
    private array $queue = [];

    public function __construct(
        private EntityManagerInterface $em,
        private PushSubscriptionRepository $subscriptions,
        private LoggerInterface $logger,
        private string $publicKey,
        private string $privateKey,
        private string $subject,
    ) {}

    public function isConfigured(): bool
    {
        return '' !== $this->publicKey && '' !== $this->privateKey;
    }

    public function publicKey(): string
    {
        return $this->publicKey;
    }

    public static function isAllowedEndpoint(string $endpoint): bool
    {
        $parts = parse_url($endpoint);
        return 'https' === ($parts['scheme'] ?? null) && empty($parts['port']) && empty($parts['user'])
            && preg_match(self::ALLOWED_HOSTS, strtolower($parts['host'] ?? ''));
    }

    /**
     * @param array{title: string, body: string, url?: string, tag?: string} $payload
     */
    public function queue(User $user, array $payload): void
    {
        if ($this->isConfigured() && $user->getId()) {
            $this->queue[] = ['user' => $user->getId(), 'payload' => $payload];
        }
    }

    /** Sends what was queued. @return int number of messages delivered to push services */
    public function flush(): int
    {
        if (!$this->queue) {
            return 0;
        }
        $queue = $this->queue;
        $this->queue = [];
        $byUser = [];
        foreach ($this->subscriptions->forUsers(array_values(array_unique(array_column($queue, 'user')))) as $s) {
            $byUser[$s->getUser()->getId()][] = $s;
        }
        $webPush = $this->client();
        $sent = [];
        foreach ($queue as $item) {
            foreach ($byUser[$item['user']] ?? [] as $s) {
                $webPush->queueNotification(
                    new Subscription($s->getEndpoint(), $s->getP256dh(), $s->getAuth(), 'aes128gcm'),
                    json_encode($item['payload'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
                );
                $sent[$s->getEndpoint()] = $s;
            }
        }
        return $this->report($webPush, $sent);
    }

    /** Sends right away to one device (test button). */
    public function sendNow(PushSubscription $s, array $payload): bool
    {
        $webPush = $this->client();
        $webPush->queueNotification(new Subscription($s->getEndpoint(), $s->getP256dh(), $s->getAuth(), 'aes128gcm'), json_encode($payload, JSON_UNESCAPED_UNICODE));
        return 1 === $this->report($webPush, [$s->getEndpoint() => $s]);
    }

    /** @param array<string, PushSubscription> $byEndpoint */
    private function report(WebPush $webPush, array $byEndpoint): int
    {
        $ok = 0;
        try {
            foreach ($webPush->flush() as $report) {
                $s = $byEndpoint[$report->getEndpoint()] ?? null;
                if ($report->isSuccess()) {
                    ++$ok;
                    $s?->touch();
                } elseif ($report->isSubscriptionExpired() && $s) {
                    $this->em->remove($s); // unsubscribed or reset browser
                } else {
                    $this->logger->warning('Web Push refusé : '.$report->getReason());
                }
            }
            $this->em->flush();
        } catch (\Throwable $e) {
            $this->logger->error('Web Push impossible : '.$e->getMessage());
        }
        return $ok;
    }

    private function client(): WebPush
    {
        return new WebPush(
            ['VAPID' => ['subject' => $this->subject ?: 'mailto:admin@example.com', 'publicKey' => $this->publicKey, 'privateKey' => $this->privateKey]],
            ['TTL' => 86400, 'urgency' => 'normal', 'batchSize' => 200],
            new Client(['timeout' => 8, 'connect_timeout' => 4, 'allow_redirects' => false]),
        );
    }
}
