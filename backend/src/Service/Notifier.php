<?php

namespace App\Service;

use App\Entity\Board;
use App\Entity\Card;
use App\Entity\Notification;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;

class Notifier
{
    public function __construct(private EntityManagerInterface $em, private PushSender $push) {}

    /**
     * Creates one notification per recipient (in-app + browser banner on their devices),
     * never notifying the actor themselves.
     * @param iterable<User> $recipients
     */
    public function notify(iterable $recipients, ?User $actor, string $type, string $message, ?Board $board = null, ?Card $card = null): void
    {
        $seen = [];
        foreach ($recipients as $r) {
            if (!$r || isset($seen[$r->getId()]) || ($actor && $r->getId() === $actor->getId())) {
                continue;
            }
            $seen[$r->getId()] = true;
            $this->em->persist(new Notification($r, $actor, $type, $message, $board, $card));
            // Browser banner (Web Push), sent after the response: see PushSender
            $this->push->queue($r, [
                'title' => $board?->getTitle() ?? 'Taskora',
                'body' => $message,
                'url' => $card && $board ? sprintf('/boards/%d?card=%d', $board->getId(), $card->getId()) : ($board ? '/boards/'.$board->getId() : '/boards'),
                'tag' => $card ? 'card-'.$card->getId() : $type,
                'type' => $type,
            ]);
        }
    }
}
