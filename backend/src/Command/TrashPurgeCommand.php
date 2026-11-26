<?php

namespace App\Command;

use App\Service\Trash;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Attribute\Option;
use Symfony\Component\Console\Style\SymfonyStyle;

/** Deletes for good the boards, lists and cards in the trash for more than 30 days (run every 6 h by the api container). */
#[AsCommand('app:trash:purge', 'Vide la corbeille des éléments supprimés depuis plus de 30 jours (fichiers S3 compris)')]
class TrashPurgeCommand
{
    public function __construct(private Trash $trash) {}

    public function __invoke(SymfonyStyle $io, #[Option('Ancienneté minimale en jours')] int $days = Trash::RETENTION_DAYS): int
    {
        $r = $this->trash->purgeExpired(max(0, $days));
        $io->success(sprintf('Corbeille purgée : %d tableau(x), %d liste(s), %d carte(s) supprimés définitivement.', $r['boards'], $r['lists'], $r['cards']));
        return 0;
    }
}
