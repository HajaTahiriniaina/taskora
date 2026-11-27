<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\Card;
use App\Entity\User;
use App\Service\BoardAccess;
use App\Service\Trash;
use App\Service\Presenter;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

abstract class ApiController extends AbstractController
{
    public function __construct(protected EntityManagerInterface $em, protected Presenter $presenter) {}

    protected function me(): User
    {
        /** @var User $u */
        $u = $this->getUser();
        return $u;
    }

    protected function body(Request $request): array
    {
        if ('' === $request->getContent()) {
            return [];
        }
        $data = json_decode($request->getContent(), true);
        if (!is_array($data)) {
            throw new BadRequestHttpException('JSON invalide.');
        }
        return $data;
    }

    protected function requireString(array $data, string $key, int $max = 255): string
    {
        $v = trim((string) ($data[$key] ?? ''));
        if ('' === $v) {
            throw new BadRequestHttpException(sprintf('Le champ "%s" est obligatoire.', $key));
        }
        return mb_substr($v, 0, $max);
    }

    /**
     * @template T of object
     * @param class-string<T> $class
     * @return T
     */
    protected function find(string $class, int $id): object
    {
        $o = $this->em->find($class, $id) ?? throw new NotFoundHttpException('Ressource introuvable.');
        if (Trash::isTrashed($o)) { // in the trash: invisible until restored (see TrashController)
            throw new NotFoundHttpException('Cet élément est dans la corbeille.');
        }
        return $o;
    }

    /**
     * Élément d'un tableau, accès contrôlé AVANT la corbeille (U8-a, §5.3) : inexistant ou
     * non-membre → 404 identiques (« Tableau introuvable. » pour un tableau, « Ressource
     * introuvable. » pour une ressource enfant) ; membre et élément dans la corbeille → message
     * actuel (« Ce tableau est dans la corbeille. » / « Cet élément est dans la corbeille. »).
     *
     * @template T of object
     * @param class-string<T> $class
     * @return T
     */
    protected function findForMember(string $class, int $id, BoardAccess $access): object
    {
        $o = $this->em->find($class, $id);
        if ($o instanceof Board || Board::class === $class) {
            $access->member($o instanceof Board ? $o : null, $this->me());

            return $o;
        }
        if (null === $o) {
            throw new NotFoundHttpException(BoardAccess::RESOURCE_NOT_FOUND);
        }
        $access->memberOfChild(self::boardOf($o), $this->me());
        if (Trash::isTrashed($o)) {
            throw new NotFoundHttpException('Cet élément est dans la corbeille.');
        }

        return $o;
    }

    /** Tableau d'une ressource enfant (liste, carte, étiquette, champ, checklist, commentaire, pièce jointe). */
    private static function boardOf(object $o): ?Board
    {
        return match (true) {
            $o instanceof BoardList, $o instanceof Card => $o->getBoard(),
            method_exists($o, 'getBoard') => $o->getBoard(),
            method_exists($o, 'getCard') => $o->getCard()->getBoard(),
            default => null,
        };
    }
}
