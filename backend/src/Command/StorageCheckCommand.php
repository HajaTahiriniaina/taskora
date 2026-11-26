<?php

namespace App\Command;

use App\Storage\FileStorage;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Style\SymfonyStyle;

/** Verifies the storage configuration end to end: write, read back, delete a test object. */
#[AsCommand(name: 'app:storage:check', description: 'Vérifie la configuration du stockage des fichiers (S3)')]
class StorageCheckCommand
{
    public function __construct(private FileStorage $storage) {}

    public function __invoke(SymfonyStyle $io): int
    {
        $io->writeln('Stockage : <info>'.$this->storage->describe().'</info>');
        $key = '0000/00/'.bin2hex(random_bytes(16)).'.png';
        $tmp = tempnam(sys_get_temp_dir(), 'tk');
        file_put_contents($tmp, base64_decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII='));
        try {
            $this->storage->put($key, $tmp, 'image/png');
            $io->writeln('✔ écriture');
            if (!$this->storage->exists($key)) {
                throw new \RuntimeException('objet introuvable après écriture');
            }
            if (($this->storage->info($key)['size'] ?? 0) <= 0) {
                throw new \RuntimeException('taille de l’objet illisible');
            }
            $io->writeln('✔ lecture');
            $target = $this->storage->respond($key, 'check.png', true, 'image/png')->headers->get('Location');
            if ($target) {
                $io->writeln('✔ URL de livraison : '.preg_replace('/(X-Amz-Signature=)[^&]+/', '$1…', $target));
            }
            $this->storage->delete($key);
            $io->writeln('✔ suppression');
        } catch (\Throwable $e) {
            $io->error('Échec : '.$e->getMessage());
            return 1;
        } finally {
            @unlink($tmp);
        }
        $io->success('Le stockage fonctionne.');
        return 0;
    }
}
