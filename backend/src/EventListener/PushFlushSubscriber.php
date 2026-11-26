<?php

namespace App\EventListener;

use App\Service\PushSender;
use Symfony\Component\Console\ConsoleEvents;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpKernel\KernelEvents;

/** Sends the browser notifications collected during a request, after the response was sent. */
class PushFlushSubscriber implements EventSubscriberInterface
{
    public function __construct(private PushSender $push) {}

    public static function getSubscribedEvents(): array
    {
        return [KernelEvents::TERMINATE => 'flush', ConsoleEvents::TERMINATE => 'flush'];
    }

    public function flush(): void
    {
        $this->push->flush();
    }
}
