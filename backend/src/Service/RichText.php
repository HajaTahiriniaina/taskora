<?php

namespace App\Service;

use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HtmlSanitizer\HtmlSanitizerInterface;

/**
 * Descriptions/comments are either rich HTML (Tiptap editor) or legacy plain text / Markdown
 * (older data, Trello import). HTML is sanitized on write; text is stored as-is
 * (the frontend converts Markdown and sanitizes before rendering).
 */
class RichText
{
    public function __construct(#[Autowire(service: 'html_sanitizer.sanitizer.app.rich_text')] private HtmlSanitizerInterface $sanitizer) {}

    public function clean(?string $value): ?string
    {
        $value = trim((string) $value);
        if ('' === $value) {
            return null;
        }
        if (!self::isHtml($value)) {
            return $value;
        }
        $html = $this->sanitizeStyles($this->sanitizer->sanitize($value));
        // An editor left empty produces "<p></p>"
        return '' === trim(strip_tags($html, '<img><hr><input>')) ? null : $html;
    }

    public static function isHtml(string $value): bool
    {
        return (bool) preg_match('/^\s*<(p|h[1-6]|ul|ol|blockquote|pre|div|hr|img|span|strong|em|a|br)[\s>\/]/i', $value);
    }

    /** Only keep color / background-color / text-align declarations in style attributes. */
    private function sanitizeStyles(string $html): string
    {
        return (string) preg_replace_callback('/\sstyle="([^"]*)"/i', function ($m) {
            $kept = [];
            foreach (explode(';', html_entity_decode($m[1])) as $decl) {
                if (preg_match('/^\s*(color|background-color)\s*:\s*(#[0-9a-f]{3,8}|rgba?\([\d\s,.%]+\)|var\(--[a-z0-9-]+\)|[a-z]+)\s*$/i', $decl, $d)
                    || preg_match('/^\s*(text-align)\s*:\s*(left|right|center|justify)\s*$/i', $decl, $d)) {
                    $kept[] = strtolower($d[1]).': '.$d[2];
                }
            }
            return $kept ? ' style="'.htmlspecialchars(implode('; ', $kept)).'"' : '';
        }, $html);
    }
}
