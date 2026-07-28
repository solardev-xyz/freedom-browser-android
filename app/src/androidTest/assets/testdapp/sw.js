// Offline-first service worker for the SW acceptance test: caches the
// shell on install, serves cache-first afterwards.
const CACHE = 'testdapp-v1';
const SHELL = ['/', '/style.css', '/app.js', '/sub/pixel.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (e) => {
  e.respondWith(
    caches.match(e.request, { ignoreSearch: true }).then(
      (hit) => hit || fetch(e.request),
    ),
  );
});
