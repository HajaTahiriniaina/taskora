<?php

namespace App\EventListener;

use App\Entity\User;
use App\Service\Presenter;
use Lexik\Bundle\JWTAuthenticationBundle\Event\AuthenticationSuccessEvent;
use Lexik\Bundle\JWTAuthenticationBundle\Events;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;

/** Adds the user profile to the login response, next to the token. */
#[AsEventListener(event: Events::AUTHENTICATION_SUCCESS)]
class JwtSuccessListener
{
    public function __construct(private Presenter $presenter) {}

    public function __invoke(AuthenticationSuccessEvent $event): void
    {
        $user = $event->getUser();
        if ($user instanceof User) {
            $event->setData([...$event->getData(), 'user' => $this->presenter->user($user)]);
        }
    }
}
