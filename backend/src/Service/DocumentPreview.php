<?php

namespace App\Service;

use App\Entity\Attachment;
use GuzzleHttp\Client;
use Symfony\Component\HttpKernel\Exception\HttpException;

/**
 * PDF rendering of Word / PowerPoint / Excel and LibreOffice documents, for the in-page viewer.
 * Converted once by Gotenberg (LibreOffice in its own container, on an internal network
 * without Internet access), then stored next to the original (Attachment::previewKey).
 */
class DocumentPreview
{
    public const OFFICE = [
        'doc', 'docx', 'docm', 'dot', 'dotx', 'odt', 'ott', 'rtf', 'wpd', 'pages',
        'ppt', 'pptx', 'pptm', 'pps', 'ppsx', 'pot', 'potx', 'odp', 'otp', 'key',
        'xls', 'xlsx', 'xlsm', 'xlsb', 'ods', 'ots', 'numbers',
    ];

    public function __construct(private Uploads $uploads, private string $gotenbergUrl) {}

    public static function supports(Attachment $a): bool
    {
        return in_array($a->getExtension(), self::OFFICE, true);
    }

    /** @return string storage key of the PDF (made on first call) */
    public function pdfKey(Attachment $attachment): string
    {
        if ($attachment->getPreviewKey()) {
            return $attachment->getPreviewKey();
        }
        if (!self::supports($attachment)) {
            throw new HttpException(422, 'Aucun aperçu PDF pour ce type de fichier.');
        }
        $storage = $this->uploads->storage();
        $dir = sys_get_temp_dir().'/tk-'.bin2hex(random_bytes(6));
        mkdir($dir);
        // LibreOffice picks the import filter from the extension
        $src = $dir.'/document.'.$attachment->getExtension();
        $pdf = $dir.'/preview.pdf';
        try {
            $storage->download($attachment->getStorageKey(), $src);
            try {
                (new Client(['timeout' => 240, 'connect_timeout' => 5]))->post(rtrim($this->gotenbergUrl, '/').'/forms/libreoffice/convert', [
                    'multipart' => [['name' => 'files', 'contents' => fopen($src, 'rb'), 'filename' => basename($src)]],
                    'sink' => $pdf,
                ]);
            } catch (\Throwable $e) {
                throw new HttpException(502, 'La conversion du document a échoué. Téléchargez-le pour l’ouvrir.', $e);
            }
            $key = sprintf('%s/%s/apercu.pdf', date('Y/m'), bin2hex(random_bytes(16)));
            $storage->put($key, $pdf, 'application/pdf');
            $attachment->setPreviewKey($key);
            return $key;
        } finally {
            @unlink($src);
            @unlink($pdf);
            @rmdir($dir);
        }
    }
}
