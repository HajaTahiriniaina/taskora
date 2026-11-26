<?php

namespace App\Repository;

use App\Entity\Activity;
use App\Entity\Card;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/** @extends ServiceEntityRepository<Activity> */
class ActivityRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry) { parent::__construct($registry, Activity::class); }

    /**
     * Newest first, keyset pagination on (createdAt, id).
     * @return Activity[]
     */
    public function page(Card $card, ?Activity $before, int $limit): array
    {
        $qb = $this->createQueryBuilder('a')
            ->leftJoin('a.actor', 'u')->addSelect('u')
            ->where('a.card = :card')->setParameter('card', $card)
            ->orderBy('a.createdAt', 'DESC')->addOrderBy('a.id', 'DESC')
            ->setMaxResults($limit);
        if ($before) {
            $qb->andWhere('a.createdAt < :d OR (a.createdAt = :d AND a.id < :id)')
               ->setParameter('d', $before->getCreatedAt())->setParameter('id', $before->getId());
        }
        return $qb->getQuery()->getResult();
    }
}
