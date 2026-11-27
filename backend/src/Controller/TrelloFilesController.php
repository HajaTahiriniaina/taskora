<?php

namespace App\Controller;

use App\Entity\Board;
use App\Entity\TrelloImportFile;
use App\Repository\TrelloImportFileRepository;
use App\Service\BoardAccess;
use App\Service\TrelloFileTransfer;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Copy of the files of an imported Trello board. The Trello key / token come with each request
 * (from the importer's browser memory) and are never stored.
 */
#[Route('/api/boards/{id}/trello-files', requirements: ['id' => '\d+'])]
class TrelloFilesController extends ApiController
{
    #[Route('', methods: ['GET'])]
    public function status(int $id, BoardAccess $access, TrelloImportFileRepository $files): JsonResponse
    {
        $board = $this->board($id, $access);
        return $this->json($this->state($board, $files));
    }

    /** Copies the next files (about 40 s of work), then answers with the progress. Called in a loop. */
    #[Route('/transfer', methods: ['POST'])]
    public function transfer(int $id, Request $request, BoardAccess $access, TrelloFileTransfer $transfer, TrelloImportFileRepository $files): JsonResponse
    {
        $board = $this->board($id, $access);
        [$key, $token] = $this->credentials($request);
        $result = $transfer->transfer($board, $key, $token);
        return $this->json([...$this->state($board, $files), ...$result]);
    }

    /** Puts failed files back in the queue. */
    #[Route('/retry', methods: ['POST'])]
    public function retry(int $id, BoardAccess $access, TrelloImportFileRepository $files): JsonResponse
    {
        $board = $this->board($id, $access);
        foreach ($files->failures($board) as $f) {
            $f->retry();
        }
        $this->em->flush();
        return $this->json($this->state($board, $files));
    }

    /** Gives up the remaining files: they are listed as Trello links on their cards. */
    #[Route('', methods: ['DELETE'])]
    public function abandon(int $id, BoardAccess $access, TrelloFileTransfer $transfer, TrelloImportFileRepository $files): JsonResponse
    {
        $board = $this->board($id, $access);
        $transfer->abandon($board);
        return $this->json($this->state($board, $files));
    }

    private function board(int $id, BoardAccess $access): Board
    {
        $board = $this->findForMember(Board::class, $id, $access);
        if (!$access->member($board, $this->me())->canManage()) {
            throw new AccessDeniedHttpException('Seuls les administrateurs du tableau peuvent transférer les pièces jointes Trello.');
        }
        return $board;
    }

    /** @return array{0: string, 1: string} */
    private function credentials(Request $request): array
    {
        $data = $this->body($request);
        $key = trim((string) ($data['key'] ?? ''));
        $token = trim((string) ($data['token'] ?? ''));
        TrelloFileTransfer::assertCredentials($key, $token);
        return [$key, $token];
    }

    private function state(Board $board, TrelloImportFileRepository $files): array
    {
        return [
            ...$files->stats($board),
            'failures' => array_map(fn (TrelloImportFile $f) => ['id' => $f->getId(), 'name' => $f->getName(), 'error' => $f->getError(), 'cardId' => $f->getCard()->getId()], $files->failures($board)),
        ];
    }
}
