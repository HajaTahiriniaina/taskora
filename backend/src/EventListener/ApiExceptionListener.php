<?php

namespace App\EventListener;

use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Symfony\Component\HttpKernel\KernelEvents;

/** Every error under /api and /internal is returned as JSON: {"error": "..."} */
#[AsEventListener(event: KernelEvents::EXCEPTION, priority: -10)]
class ApiExceptionListener
{
    public function __construct(private bool $debug = false) {}

    public function __invoke(ExceptionEvent $event): void
    {
        $path = $event->getRequest()->getPathInfo();
        if (!str_starts_with($path, '/api') && !str_starts_with($path, '/internal')) {
            return;
        }
        $e = $event->getThrowable();
        $status = $e instanceof HttpExceptionInterface ? $e->getStatusCode() : 500;
        $message = $status < 500 || $this->debug ? $e->getMessage() : 'Erreur interne du serveur.';
        $event->setResponse(new JsonResponse(['error' => $message ?: 'Erreur'], $status));
    }
}
