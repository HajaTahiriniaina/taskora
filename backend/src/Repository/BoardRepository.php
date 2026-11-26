<?php

namespace App\Repository;

use App\Entity\Board;
use App\Entity\User;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/** @extends ServiceEntityRepository<Board> */
class BoardRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, Board::class); }

    /** @return Board[] */
    public function findForUser(User $user): array
    {
        return $this->createQueryBuilder('b')
            ->innerJoin('b.members', 'm')
            ->andWhere('m.user = :u')->setParameter('u', $user)
            ->andWhere('b.deletedAt IS NULL')
            ->orderBy('b.createdAt', 'DESC')
            ->getQuery()->getResult();
    }

    /** @return Board[] boards of the user's trash (only owners can delete / restore a board) */
    public function trashedOwnedBy(User $user): array
    {
        return $this->createQueryBuilder('b')
            ->innerJoin('b.members', 'm')
            ->andWhere('m.user = :u AND m.role = :owner')->setParameter('u', $user)->setParameter('owner', 'owner')
            ->andWhere('b.deletedAt IS NOT NULL')
            ->orderBy('b.deletedAt', 'DESC')
            ->getQuery()->getResult();
    }
}
