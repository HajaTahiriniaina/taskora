<?php

namespace App\Repository;

use App\Entity\Board;
use App\Entity\TrelloImportFile;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/** @extends ServiceEntityRepository<TrelloImportFile> */
class TrelloImportFileRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, TrelloImportFile::class); }

    /** @return array{pending:int, done:int, failed:int, total:int, pendingBytes:int} */
    public function stats(Board $board): array
    {
        $rows = $this->getEntityManager()->getConnection()->fetchAllAssociative(
            'SELECT status, COUNT(*) n, SUM(bytes) b FROM trello_import_file WHERE board_id = ? GROUP BY status', [$board->getId()]);
        $out = ['pending' => 0, 'done' => 0, 'failed' => 0, 'total' => 0, 'pendingBytes' => 0];
        foreach ($rows as $r) {
            $out[$r['status']] = (int) $r['n'];
            $out['total'] += (int) $r['n'];
            if (TrelloImportFile::PENDING === $r['status']) {
                $out['pendingBytes'] = (int) $r['b'];
            }
        }
        return $out;
    }

    /** Files still to copy (pending or failed): shown as a banner on the board. */
    public function remaining(Board $board): int
    {
        return (int) $this->getEntityManager()->getConnection()->fetchOne(
            "SELECT COUNT(*) FROM trello_import_file WHERE board_id = ? AND status <> 'done'", [$board->getId()]);
    }

    /** @return TrelloImportFile[] */
    public function nextPending(Board $board, int $limit): array
    {
        return $this->findBy(['board' => $board, 'status' => TrelloImportFile::PENDING], ['id' => 'ASC'], $limit);
    }

    /** @return TrelloImportFile[] */
    public function failures(Board $board): array
    {
        return $this->findBy(['board' => $board, 'status' => TrelloImportFile::FAILED], ['id' => 'ASC'], 100);
    }
}
