<?php

namespace App\Service;

use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\UnauthorizedHttpException;

/**
 * Un secret par appelant pour les endpoints /internal/** (U12, B-ADR-5 §6) : identity-service →
 * INTERNAL_API_SECRET (claim seulement) ; board-service → INTERNAL_SECRET_BOARD_TO_SYMFONY
 * (/internal/notifications…). En-tête X-Internal-Secret comparé par hash_equals. Secret attendu
 * vide, ou deux secrets égaux → toujours 401 (journal ERROR sans valeur).
 */
class InternalCaller
{
    public const DENIED = 'Accès interne refusé.';

    public function __construct(
        private LoggerInterface $logger,
        #[Autowire('%env(default::INTERNAL_API_SECRET)%')]
        private ?string $identitySecret = null,
        #[Autowire('%env(default::INTERNAL_SECRET_BOARD_TO_SYMFONY)%')]
        private ?string $boardSecret = null,
    ) {}

    /** Appelant identity-service (POST /internal/invitations/claim). */
    public function requireIdentity(Request $request): void
    {
        $this->require($request, (string) $this->identitySecret, (string) $this->boardSecret, 'INTERNAL_API_SECRET');
    }

    /** Appelant board-service (POST /internal/notifications et suivants). */
    public function requireBoard(Request $request): void
    {
        $this->require($request, (string) $this->boardSecret, (string) $this->identitySecret, 'INTERNAL_SECRET_BOARD_TO_SYMFONY');
    }

    private function require(Request $request, string $expected, string $other, string $variable): void
    {
        if ('' === $expected) {
            $this->logger->error(sprintf('Appel interne refusé : %s est vide ou absent.', $variable));
            throw new UnauthorizedHttpException('Internal', self::DENIED);
        }
        if ($expected === $other) {
            $this->logger->error('Appel interne refusé : INTERNAL_API_SECRET et INTERNAL_SECRET_BOARD_TO_SYMFONY sont égaux.');
            throw new UnauthorizedHttpException('Internal', self::DENIED);
        }
        $given = $request->headers->get('X-Internal-Secret');
        if (!is_string($given) || !hash_equals($expected, $given)) {
            throw new UnauthorizedHttpException('Internal', self::DENIED);
        }
    }
}
