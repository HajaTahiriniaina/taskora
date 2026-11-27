<?php

namespace App\Tests\Controller;

use App\Tests\Support\ApiTestCase;
use PHPUnit\Framework\Attributes\TestDox;

/**
 * U12 (B-ADR-5 §6) — un secret par appelant : /internal/notifications n'accepte que
 * INTERNAL_SECRET_BOARD_TO_SYMFONY (board-service) ; /internal/invitations/claim n'accepte que
 * INTERNAL_API_SECRET (identity-service). Les deux variables égales, ou celle qui protège un
 * endpoint vide → 401 partout où elle protège.
 */
final class InternalSecretPerCallerTest extends ApiTestCase
{
    private const DENIED = ['error' => 'Accès interne refusé.'];

    private function notifications(?string $secret): array
    {
        $headers = null === $secret ? [] : ['X-Internal-Secret' => $secret];
        [$status, $json] = $this->api('POST', '/internal/notifications', null,
            '{"eventId":"3f2c1a7e-9b4d-4e6f-8a1c-2d5e7f9a0b1c","type":"board_invite","actorId":null,"recipientIds":[999999],"message":"x"}', $headers);

        return [$status, $json];
    }

    private function claim(?string $secret): array
    {
        $headers = null === $secret ? [] : ['X-Internal-Secret' => $secret];
        [$status, $json] = $this->api('POST', '/internal/invitations/claim', null,
            '{"eventId":"0b7c1e9a-3f5d-4c2b-9a51-6f0e2d8c4a17","userId":999999,"email":"personne@exemple.test"}', $headers);

        return [$status, $json];
    }

    #[TestDox('U12 — /internal/notifications : refuse INTERNAL_API_SECRET et un secret aléatoire, accepte INTERNAL_SECRET_BOARD_TO_SYMFONY')]
    public function testNotificationsAcceptOnlyBoardSecret(): void
    {
        self::assertSame([401, self::DENIED], $this->notifications(self::IDENTITY_SECRET));
        self::assertSame([401, self::DENIED], $this->notifications('aleatoire-'.bin2hex(random_bytes(16))));
        self::assertSame([200, ['created' => 0]], $this->notifications(self::BOARD_SECRET));
    }

    #[TestDox('U12 — /internal/invitations/claim : refuse INTERNAL_SECRET_BOARD_TO_SYMFONY, accepte INTERNAL_API_SECRET')]
    public function testClaimAcceptsOnlyIdentitySecret(): void
    {
        self::assertSame([401, self::DENIED], $this->claim(self::BOARD_SECRET));
        [$status, $json] = $this->claim(self::IDENTITY_SECRET);
        self::assertSame([404, ['error' => 'Utilisateur introuvable.']], [$status, $json], 'secret accepté : contrôle suivant (utilisateur)');
    }

    #[TestDox('U12 — les deux variables égales → 401 sur les deux endpoints')]
    public function testEqualSecretsRefusedEverywhere(): void
    {
        $this->setSecrets(self::BOARD_SECRET, self::BOARD_SECRET);
        $this->boot();
        self::assertSame([401, self::DENIED], $this->notifications(self::BOARD_SECRET));
        self::assertSame([401, self::DENIED], $this->claim(self::BOARD_SECRET));
        self::assertSame(0, $this->countSql('SELECT COUNT(*) FROM notification'));
    }

    #[TestDox('U12 — INTERNAL_SECRET_BOARD_TO_SYMFONY vide → 401 sur /internal/notifications ; le claim reste servi')]
    public function testEmptyBoardSecret(): void
    {
        $this->setSecrets(self::IDENTITY_SECRET, '');
        $this->boot();
        self::assertSame([401, self::DENIED], $this->notifications(''));
        [$status] = $this->claim(self::IDENTITY_SECRET);
        self::assertSame(404, $status);
    }

    #[TestDox('U12 — INTERNAL_API_SECRET vide → 401 sur le claim ; /internal/notifications reste servi')]
    public function testEmptyIdentitySecret(): void
    {
        $this->setSecrets('', self::BOARD_SECRET);
        $this->boot();
        self::assertSame([401, self::DENIED], $this->claim(''));
        self::assertSame([200, ['created' => 0]], $this->notifications(self::BOARD_SECRET));
        self::assertSame(0, $this->countSql('SELECT COUNT(*) FROM invitation'));
    }
}
