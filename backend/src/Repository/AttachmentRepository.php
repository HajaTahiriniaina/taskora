<?php

namespace App\Repository;

use App\Entity\Attachment;
use App\Entity\Card;
use App\Entity\Comment;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/** @extends ServiceEntityRepository<Attachment> */
class AttachmentRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, Attachment::class); }

    /** @return Attachment[] newest first */
    public function forCard(Card $card): array
    {
        return $this->createQueryBuilder('a')
            ->leftJoin('a.uploader', 'u')->addSelect('u')
            ->where('a.card = :card')->setParameter('card', $card)
            ->orderBy('a.createdAt', 'DESC')->addOrderBy('a.id', 'DESC')
            ->getQuery()->getResult();
    }

    /** @return Attachment[] */
    public function forComment(Comment $comment): array
    {
        return $this->findBy(['comment' => $comment], ['id' => 'ASC']);
    }

    /** Name to give a downloaded object: original name, "<name>.pdf" for a PDF preview, null if unknown. */
    public function downloadName(string $key): ?string
    {
        $a = $this->createQueryBuilder('a')
            ->where('a.storageKey = :k OR a.previewKey = :k OR a.thumbKey = :k')->setParameter('k', $key)
            ->setMaxResults(1)->getQuery()->getOneOrNullResult();
        if (!$a) {
            return null;
        }
        return match ($key) {
            $a->getStorageKey() => $a->getName(),
            $a->getPreviewKey() => pathinfo($a->getName(), PATHINFO_FILENAME).'.pdf',
            default => pathinfo($a->getName(), PATHINFO_FILENAME).'.webp',
        };
    }

    /** @return array<string, string> storageKey => original name, for the given keys */
    public function namesByKey(array $keys): array
    {
        if (!$keys) {
            return [];
        }
        $rows = $this->createQueryBuilder('a')->select('a.storageKey k, a.name n')
            ->where('a.storageKey IN (:keys)')->setParameter('keys', $keys)
            ->getQuery()->getScalarResult();
        return array_column($rows, 'n', 'k');
    }
}
