<?php

namespace App\Controller;

use App\Service\TrelloFileTransfer;
use App\Service\TrelloImporter;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Routing\Attribute\Route;

class ImportController extends ApiController
{
    /**
     * Checks a Trello API key / token (import dialog) and answers with the account name.
     * The token is only used for this request and never stored.
     */
    #[Route('/api/import/trello/check', methods: ['POST'])]
    public function checkTrello(Request $request, TrelloFileTransfer $transfer): JsonResponse
    {
        $data = $this->body($request);
        $key = trim((string) ($data['key'] ?? ''));
        $token = trim((string) ($data['token'] ?? ''));
        TrelloFileTransfer::assertCredentials($key, $token);
        return $this->json($transfer->whoAmI($key, $token));
    }

    /** POST /api/import/trello (multipart, field "file"): creates a new board from a Trello JSON export. */
    #[Route('/api/import/trello', methods: ['POST'])]
    public function trello(Request $request, TrelloImporter $importer): JsonResponse
    {
        $file = $request->files->get('file');
        if (!$file || !$file->isValid()) {
            throw new BadRequestHttpException('Fichier manquant ou trop volumineux (20 Mo max).');
        }
        if ($file->getSize() > 20 * 1024 * 1024) {
            throw new BadRequestHttpException('Fichier trop volumineux (20 Mo max).');
        }
        $data = json_decode((string) file_get_contents($file->getPathname()), true);
        if (!is_array($data)) {
            throw new BadRequestHttpException('JSON invalide.');
        }
        $result = $importer->import($data, $this->me());
        return $this->json(['boardId' => $result['board']->getId(), 'report' => $result['report']], 201);
    }
}
