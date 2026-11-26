/*
 * Taskora service worker: browser notifications (Web Push) only — no offline cache.
 * Receives the messages sent by the API (PushSender) and shows them as banners, even when no
 * Taskora tab is open. A click opens the card in an existing tab (or a new one).
 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

/** Only paths of this site are opened (never another origin). */
function safePath(url) {
  return typeof url === 'string' && url.startsWith('/') && !url.startsWith('//') ? url : '/boards';
}

self.addEventListener('push', event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { body: event.data ? event.data.text() : '' }; }
  event.waitUntil((async () => {
    // Open tabs refresh their notification list right away
    const tabs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    tabs.forEach(tab => tab.postMessage({ type: 'taskora-push', payload: data }));
    await self.registration.showNotification(String(data.title || 'Taskora'), {
      body: String(data.body || ''),
      tag: data.tag || undefined,
      renotify: !!data.tag,
      icon: '/icon-192.png',
      badge: '/badge-96.png',
      lang: 'fr',
      data: { url: safePath(data.url) },
    });
  })());
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const path = safePath(event.notification.data && event.notification.data.url);
  event.waitUntil((async () => {
    const tabs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const tab = tabs.find(t => new URL(t.url).origin === self.location.origin);
    if (tab) {
      await tab.focus();
      tab.postMessage({ type: 'taskora-open', url: path }); // Angular navigates without reloading
      return;
    }
    await self.clients.openWindow(path);
  })());
});
