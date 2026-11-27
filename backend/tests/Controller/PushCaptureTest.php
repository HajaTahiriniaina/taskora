<?php

namespace App\Tests\Controller;

use App\Service\PushSender;
use App\Tests\Support\ApiTestCase;
use PHPUnit\Framework\Attributes\TestDox;

/**
 * D7 — preuve que la capture des pushs d'ApiTestCase fonctionne sur une route existante
 * (POST /api/boards/{id}/members → Notifier → PushSender::queue) : la file est lue sur
 * kernel.terminate avant PushFlushSubscriber, qui la vide ensuite.
 */
final class PushCaptureTest extends ApiTestCase
{
    #[TestDox('D7 — ajout d\'un membre : push board_invite capté avant le vidage de la file')]
    public function testCapturesPushOfExistingRoute(): void
    {
        $alice = $this->user('alice@exemple.test', 'Alice Martin');
        $dora = $this->user('dora@exemple.test', 'Dora');
        $board = $this->board($alice, 'Lancement produit');
        $this->em->flush();
        $push = static::getContainer()->get(PushSender::class);
        self::assertTrue($push->isConfigured(), 'VAPID de test posé par ApiTestCase');

        [$status] = $this->api('POST', '/api/boards/'.$board->getId().'/members', $alice, json_encode(['email' => 'dora@exemple.test']));
        self::assertSame(201, $status);

        self::assertCount(1, $this->pushed);
        self::assertSame($dora->getId(), $this->pushed[0]['user']);
        self::assertSame('Lancement produit', $this->pushed[0]['payload']['title'] ?? null);
        self::assertSame('Alice Martin vous a ajouté au tableau « Lancement produit »', $this->pushed[0]['payload']['body'] ?? null);
        self::assertSame('/boards/'.$board->getId(), $this->pushed[0]['payload']['url'] ?? null);
        self::assertSame('board_invite', $this->pushed[0]['payload']['tag'] ?? null);
        // Après la requête, PushFlushSubscriber a vidé la file : une lecture a posteriori ne voit rien.
        self::assertSame([], (new \ReflectionProperty(PushSender::class, 'queue'))->getValue($push));
    }

    #[TestDox('D7 — requête sans notification : rien de capté')]
    public function testNothingCapturedWithoutNotification(): void
    {
        $alice = $this->user('alice@exemple.test', 'Alice Martin');
        $this->em->flush();
        [$status] = $this->api('GET', '/api/boards', $alice);
        self::assertSame(200, $status);
        self::assertSame([], $this->pushed);
    }
}
