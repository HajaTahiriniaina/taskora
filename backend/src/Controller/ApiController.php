<?php

namespace App\Controller;

use App\Service\Trash;
use App\Entity\User;
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
}
