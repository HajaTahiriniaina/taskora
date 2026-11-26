<?php

namespace App\Service;

use App\Entity\Attachment;
use App\Entity\Board;
use App\Entity\Card;
use App\Entity\Comment;
use App\Entity\TrelloImportFile;
use App\Repository\TrelloImportFileRepository;
use Doctrine\ORM\EntityManagerInterface;
use GuzzleHttp\Client;
use Psr\Http\Message\ResponseInterface;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * Copies the files of an imported Trello board into Taskora (Amazon S3).
 *
 * Trello only serves attachments to authenticated requests: the importer gives an API key and a
 * token, used for the duration of the request (Authorization header) and never stored or logged.
 * Work is done in short slices (budget of a few dozen seconds per call) so the browser can show
 * progress and the transfer resumes where it stopped.
 * Only "https://trello.com/1/cards/<id>/attachments/<id>/download/…" URLs are fetched (no SSRF
 * through a crafted export).
 */
class TrelloFileTransfer
{
    private const FILE_URL = '#^https://(?:api\.)?trello\.com/1/cards/[a-f0-9]{24}/attachments/[a-f0-9]{24}/download/[^\s]+$#';

    public function __construct(
        private EntityManagerInterface $em,
        private Uploads $uploads,
        private TrelloImportFileRepository $files,
    ) {}

    public static function isTrelloFileUrl(string $url): bool
    {
        return (bool) preg_match(self::FILE_URL, $url);
    }

    /** @return array{fullName: string, username: string} the Trello account the key / token belong to */
    public function whoAmI(string $key, string $token): array
    {
        $response = $this->client($key, $token)->get('https://api.trello.com/1/members/me', ['query' => ['fields' => 'fullName,username']]);
        $this->assertAuthorized($response);
        if (200 !== $response->getStatusCode()) {
            throw new HttpException(502, sprintf('Trello ne répond pas correctement (HTTP %d). Réessayez plus tard.', $response->getStatusCode()));
        }
        $me = json_decode((string) $response->getBody(), true) ?: [];
        return ['fullName' => (string) ($me['fullName'] ?? ''), 'username' => (string) ($me['username'] ?? '')];
    }

    /**
     * Copies pending files until the time budget is spent.
     * @return array{transferred: list<array{name:string, cardId:int}>, errors: list<array{name:string, error:string}>, retryAfter: ?int}
     */
    public function transfer(Board $board, string $key, string $token, int $budgetSeconds = 40): array
    {
        $client = $this->client($key, $token);
        $deadline = microtime(true) + $budgetSeconds;
        $out = ['transferred' => [], 'errors' => [], 'retryAfter' => null];

        while (microtime(true) < $deadline && ($file = $this->files->nextPending($board, 1)[0] ?? null)) {
            if ($file->getBytes() > Uploads::MAX) {
                $this->fail($file, sprintf('Plus de %d Mo', Uploads::MAX >> 20), $out);
                continue;
            }
            $tmp = tempnam(sys_get_temp_dir(), 'trello');
            try {
                $response = $client->get($file->getUrl(), [
                    'sink' => $tmp,
                    'on_headers' => function (ResponseInterface $r) {
                        if ((int) $r->getHeaderLine('Content-Length') > Uploads::MAX) {
                            throw new \RuntimeException(sprintf('Plus de %d Mo', Uploads::MAX >> 20));
                        }
                    },
                ]);
                $this->assertAuthorized($response); // wrong / expired token: stop everything, nothing marked failed
                $status = $response->getStatusCode();
                if (429 === $status) {
                    $out['retryAfter'] = max(1, min(60, (int) ($response->getHeaderLine('Retry-After') ?: 10)));
                    break;
                }
                if (200 !== $status) {
                    $this->fail($file, 404 === $status ? 'Introuvable sur Trello (supprimé ?)' : sprintf('Refusé par Trello (HTTP %d)', $status), $out);
                    continue;
                }
                $stored = $this->uploads->storeLocal($tmp, $file->getName());
                $attachment = $this->attach($file, $stored);
                $out['transferred'][] = ['name' => $attachment->getName(), 'cardId' => $file->getCard()->getId()];
            } catch (HttpException $e) {
                throw $e;
            } catch (\Throwable $e) {
                // Guzzle messages contain the URL, never the Authorization header
                $message = $e instanceof \GuzzleHttp\Exception\RequestException ? 'Téléchargement impossible (réseau)' : $e->getMessage();
                $this->fail($file, $message, $out);
            } finally {
                @unlink($tmp);
            }
        }
        return $out;
    }

    /** Pending + failed files that will not be copied: listed as Trello links on their card. */
    public function abandon(Board $board): int
    {
        $remaining = $this->em->createQuery('SELECT f FROM App\Entity\TrelloImportFile f WHERE f.board = :b AND f.status <> :done')
            ->setParameter('b', $board)->setParameter('done', TrelloImportFile::DONE)->getResult();
        $byCard = [];
        foreach ($remaining as $f) {
            $byCard[$f->getCard()->getId()][] = $f;
        }
        foreach ($byCard as $list) {
            $card = $list[0]->getCard();
            $links = array_map(fn (TrelloImportFile $f) => sprintf('- [%s](%s)', $f->getName(), $f->getUrl()), $list);
            $desc = trim((string) $card->getDescription());
            $card->setDescription($desc.($desc ? "\n\n---\n" : '')."Pièces jointes restées sur Trello :\n".implode("\n", $links));
            foreach ($list as $f) {
                $this->em->remove($f);
            }
        }
        $this->em->flush();
        return count($remaining);
    }

    public static function assertCredentials(string $key, string $token): void
    {
        if (!preg_match('/^[a-f0-9]{32}$/', $key) || !preg_match('/^[A-Za-z0-9]{32,256}$/', $token)) {
            throw new BadRequestHttpException('Clé ou jeton Trello mal formé : copiez-les à nouveau depuis Trello.');
        }
    }

    private function attach(TrelloImportFile $file, array $stored): Attachment
    {
        $card = $file->getCard();
        $attachment = new Attachment($card, $file->getUploader(), $file->getName(), $stored['key'], $stored['mime'], $stored['size']);
        $attachment->setCreatedAt($file->getDate());
        if ($stored['image']) {
            $attachment->setThumbKey($this->uploads->thumbnail($stored['path'], $stored['mime']));
        }
        $this->em->persist($attachment);
        if ($file->isCover() && $stored['image'] && !$card->getCoverAttachment()) {
            $card->setCoverAttachment($attachment);
            $card->setCover(null);
        }
        $file->done($attachment);
        $this->rewriteLinks($file, '/uploads/'.$stored['key']);
        $this->em->flush();
        return $attachment;
    }

    /** Descriptions and comments of the board pointing to the Trello file now point to the copy. */
    private function rewriteLinks(TrelloImportFile $file, string $newUrl): void
    {
        $id = $file->getTrelloId();
        $pattern = '#https?://(?:api\.)?trello\.com/1/cards/[a-f0-9]{24}/attachments/'.preg_quote($id, '#').'/(?:download|previews)/[^\s)\]"\'<>]*#';
        $like = '%'.$id.'%';
        $cards = $this->em->createQuery('SELECT c FROM App\Entity\Card c WHERE c.board = :b AND c.description LIKE :id')
            ->setParameter('b', $file->getBoard())->setParameter('id', $like)->getResult();
        foreach ($cards as $c) {
            /** @var Card $c */
            $c->setDescription(preg_replace($pattern, $newUrl, (string) $c->getDescription()));
        }
        $comments = $this->em->createQuery('SELECT m FROM App\Entity\Comment m JOIN m.card c WHERE c.board = :b AND m.content LIKE :id')
            ->setParameter('b', $file->getBoard())->setParameter('id', $like)->getResult();
        foreach ($comments as $m) {
            /** @var Comment $m */
            $m->setContent((string) preg_replace($pattern, $newUrl, $m->getContent()));
        }
    }

    private function fail(TrelloImportFile $file, string $error, array &$out): void
    {
        $file->fail($error);
        $this->em->flush();
        $out['errors'][] = ['name' => $file->getName(), 'error' => $error];
    }

    private function assertAuthorized(ResponseInterface $response): void
    {
        if (401 === $response->getStatusCode()) {
            // 422 rather than 401: a 401 would log the user out of Taskora
            throw new HttpException(422, 'Trello a refusé la clé ou le jeton (expiré ou révoqué ?). Générez un nouveau jeton.');
        }
    }

    private function client(string $key, string $token): Client
    {
        self::assertCredentials($key, $token);
        return new Client([
            'timeout' => 120,
            'connect_timeout' => 10,
            'http_errors' => false,
            // Trello may redirect downloads to its storage; Guzzle drops Authorization on another host
            'allow_redirects' => ['max' => 3, 'protocols' => ['https'], 'strict' => true],
            'headers' => [
                'Authorization' => sprintf('OAuth oauth_consumer_key="%s", oauth_token="%s"', $key, $token),
                'User-Agent' => 'Taskora-Import/1.0',
            ],
        ]);
    }
}
