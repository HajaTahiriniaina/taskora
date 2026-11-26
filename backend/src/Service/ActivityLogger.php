<?php

namespace App\Service;

use App\Entity\Activity;
use App\Entity\Card;
use App\Entity\User;
use Doctrine\ORM\EntityManagerInterface;

/**
 * Records card history. Types and their data:
 *  created {list} · renamed {from,to} · description · moved {from,to} · completed · reopened
 *  due_set {due} · due_removed · priority {to} · cover_set · cover_removed
 *  label_added {name,color} · label_removed {name,color} · member_added {name} · member_removed {name}
 *  checklist_added {item} · checklist_done {item} · checklist_undone {item} · checklist_removed {item}
 *  field_set {field,value} · field_cleared {field} · attachment {name} · attachment_removed {name} · archived · unarchived {list} · restored {list} (from the trash)
 * Persisted only (flushed by the caller's unit of work).
 */
class ActivityLogger
{
    public function __construct(private EntityManagerInterface $em) {}

    public function log(Card $card, ?User $actor, string $type, array $data = [], ?\DateTimeImmutable $at = null, ?string $externalActor = null): Activity
    {
        $activity = new Activity($card, $actor, $type, $data, $at);
        $activity->setExternalActor($externalActor);
        $this->em->persist($activity);
        return $activity;
    }
}
