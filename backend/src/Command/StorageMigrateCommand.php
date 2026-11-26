<?php

namespace App\Command;

use App\Service\Uploads;
use App\Storage\FileStorage;
use App\Storage\LocalFileStorage;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Attribute\Option;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/** Copies images uploaded before S3 was configured (public/uploads) to the current storage. URLs do not change. */
#[AsCommand(name: 'app:storage:migrate', description: 'Copie les images locales existantes vers le stockage configuré (S3)')]
class StorageMigrateCommand
{
    public function __construct(private FileStorage $storage, #[Autowire('%kernel.project_dir%')] private string $projectDir) {}

    public function __invoke(SymfonyStyle $io, #[Option('Supprimer les copies locales après transfert')] bool $deleteLocal = false): int
    {
        if ($this->storage instanceof LocalFileStorage) {
            $io->warning('STORAGE_DRIVER=local : rien à migrer. Configurez S3 puis relancez.');
            return 1;
        }
        $dir = $this->projectDir.'/public/uploads';
        $done = $skipped = 0;
        // Old images "YYYY/MM/<hex>.<ext>" and files "YYYY/MM/<hex>/<name>"
        $paths = [...(glob($dir.'/[0-9][0-9][0-9][0-9]/[0-9][0-9]/*.*') ?: []), ...(glob($dir.'/[0-9][0-9][0-9][0-9]/[0-9][0-9]/*/*') ?: [])];
        foreach ($paths as $path) {
            $key = substr($path, strlen($dir) + 1);
            if (!is_file($path) || !preg_match('#^'.Uploads::KEY_PATTERN.'$#', $key)) {
                continue;
            }
            $mime = mime_content_type($path) ?: 'application/octet-stream';
            if ($this->storage->exists($key)) {
                ++$skipped;
            } else {
                $this->storage->put($key, $path, $mime);
                ++$done;
                $io->writeln('→ '.$key);
            }
            if ($deleteLocal) {
                unlink($path);
            }
        }
        $io->success(sprintf('%d fichier(s) copié(s) vers %s, %d déjà présent(s).%s', $done, $this->storage->describe(), $skipped,
            $deleteLocal ? ' Copies locales supprimées.' : ' Les copies locales sont conservées (--delete-local pour les supprimer).'));
        return 0;
    }
}
