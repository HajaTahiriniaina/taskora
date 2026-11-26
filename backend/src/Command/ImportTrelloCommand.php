<?php

namespace App\Command;

use App\Repository\UserRepository;
use App\Service\TrelloImporter;
use Symfony\Component\Console\Attribute\Argument;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(name: 'app:import-trello', description: 'Importe un export JSON de tableau Trello (numéros de cartes conservés)')]
class ImportTrelloCommand
{
    public function __construct(private TrelloImporter $importer, private UserRepository $users) {}

    public function __invoke(
        SymfonyStyle $io,
        #[Argument('Chemin du fichier JSON exporté de Trello')] string $file,
        #[Argument('E-mail du compte Taskora propriétaire du tableau importé')] string $email,
    ): int {
        $owner = $this->users->findOneByEmail($email) ?? null;
        if (!$owner || !is_readable($file)) {
            $io->error(!$owner ? "Aucun compte pour $email" : "Fichier illisible : $file");
            return 1;
        }
        $result = $this->importer->import(json_decode((string) file_get_contents($file), true) ?? [], $owner);
        $io->success(sprintf('Tableau #%d « %s » importé.', $result['board']->getId(), $result['board']->getTitle()));
        $io->table(['Élément', 'Valeur'], array_map(fn ($k, $v) => [$k, is_array($v) ? implode(', ', $v) : $v], array_keys($result['report']), $result['report']));
        return 0;
    }
}
