<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\CustomField;
use App\Service\BoardAccess;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api')]
class CustomFieldController extends ApiController
{
    #[Route('/boards/{id}/custom-fields', methods: ['POST'])]
    public function create(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $board = $this->em->find(Board::class, $id);
        $access->member($board, $this->me());
        $data = $this->body($request);
        $type = $data['type'] ?? 'text';
        if (!in_array($type, CustomField::TYPES, true)) {
            throw new BadRequestHttpException('Type de champ invalide.');
        }
        $field = new CustomField($board, $this->requireString($data, 'name', 80), $type, $board->getCustomFields()->count());
        $field->setOptions($this->cleanOptions($data['options'] ?? []));
        $field->setShowOnCard((bool) ($data['showOnCard'] ?? true));
        $this->em->persist($field);
        $this->em->flush();
        return $this->json($this->presenter->customField($field), 201);
    }

    #[Route('/custom-fields/{id}', methods: ['PATCH'])]
    public function update(int $id, Request $request, BoardAccess $access): JsonResponse
    {
        $field = $this->find(CustomField::class, $id);
        $access->member($field->getBoard(), $this->me());
        $data = $this->body($request);
        if (isset($data['name'])) {
            $field->setName($this->requireString($data, 'name', 80));
        }
        if (isset($data['options'])) {
            $field->setOptions($this->cleanOptions($data['options']));
        }
        if (isset($data['showOnCard'])) {
            $field->setShowOnCard((bool) $data['showOnCard']);
        }
        $this->em->flush();
        return $this->json($this->presenter->customField($field));
    }

    #[Route('/custom-fields/{id}', methods: ['DELETE'])]
    public function delete(int $id, BoardAccess $access): JsonResponse
    {
        $field = $this->find(CustomField::class, $id);
        $access->member($field->getBoard(), $this->me());
        $this->em->remove($field);
        $this->em->flush();
        return $this->json(null, 204);
    }

    private function cleanOptions(mixed $options): array
    {
        $out = [];
        foreach ((array) $options as $o) {
            $label = trim((string) ($o['label'] ?? ''));
            if ('' === $label) {
                continue;
            }
            $out[] = ['id' => (string) ($o['id'] ?? bin2hex(random_bytes(4))), 'label' => mb_substr($label, 0, 60), 'color' => $o['color'] ?? '#94a3b8'];
        }
        return $out;
    }
}
