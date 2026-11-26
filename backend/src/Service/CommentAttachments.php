<?php

namespace App\Service;

use App\Entity\Attachment;
use App\Entity\Comment;
use App\Repository\AttachmentRepository;
use Doctrine\ORM\EntityManagerInterface;

/**
 * Files posted in a comment (images and links to "/uploads/…") become attachments of the card,
 * linked to the comment. A file already attached somewhere is not attached twice.
 */
class CommentAttachments
{
    public function __construct(
        private EntityManagerInterface $em,
        private AttachmentRepository $repo,
        private Uploads $uploads,
        private ActivityLogger $activity,
    ) {}

    /** @return Attachment[] created attachments (persisted, not flushed) */
    public function collect(Comment $comment): array
    {
        $found = []; // key => display name
        $doc = \Dom\HTMLDocument::createFromString('<!doctype html><html><body>'.$comment->getContent().'</body></html>', LIBXML_NOERROR);
        foreach ($doc->querySelectorAll('a[href], img[src]') as $el) {
            $isImg = 'IMG' === $el->tagName;
            $key = Uploads::keyFromUrl((string) $el->getAttribute($isImg ? 'src' : 'href'));
            if (!$key || isset($found[$key])) {
                continue;
            }
            $label = trim($isImg ? (string) $el->getAttribute('alt') : $el->textContent);
            $label = trim(preg_replace('/^\x{1F4CE}\s*/u', '', $label)); // "📎 report.pdf" → "report.pdf"
            $found[$key] = $label ?: basename($key);
        }
        if (!$found) {
            return [];
        }
        $known = $this->repo->namesByKey(array_keys($found));
        $created = [];
        foreach (array_slice($found, 0, 50, true) as $key => $name) {
            if (isset($known[$key]) || !($info = $this->uploads->storage()->info($key))) {
                continue;
            }
            $a = new Attachment($comment->getCard(), $comment->getAuthor(), $name, $key, $info['type'], $info['size'], $comment);
            $this->em->persist($a);
            $this->activity->log($comment->getCard(), $comment->getAuthor(), 'attachment', ['name' => $a->getName()]);
            $created[] = $a;
        }
        return $created;
    }
}
