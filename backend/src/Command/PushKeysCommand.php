<?php

namespace App\Command;

use Minishlink\WebPush\VAPID;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Style\SymfonyStyle;

/** Generates a VAPID key pair for browser notifications, to paste into backend/.env (once per server). */
#[AsCommand('app:push:keys', 'Génère les clés VAPID des notifications navigateur (à copier dans backend/.env)')]
class PushKeysCommand
{
    public function __invoke(SymfonyStyle $io): int
    {
        $k = VAPID::createVapidKeys();
        $io->writeln(['VAPID_PUBLIC_KEY='.$k['publicKey'], 'VAPID_PRIVATE_KEY='.$k['privateKey']]);
        $io->note('Copiez ces deux lignes dans backend/.env puis : docker compose up -d api. Ne les changez plus ensuite (les navigateurs abonnés devraient se réabonner).');
        return 0;
    }
}
