// Service Worker for Atomic
// CACHE_VERSION is replaced at build time with a unique build ID.
const CACHE_VERSION = '%%BUILD_ID%%';
const CACHE_NAME = `atomic-${CACHE_VERSION}`;

// Patterns for large, immutable-per-build assets that benefit from Cache-first.
const ASSET_PATTERNS = [
  /composeApp\.js$/,
  /composeApp\.wasm$/,
  /\.wasm$/,
  /\.ttf$/,
  /skiko/,
];

function isAsset(url) {
  const path = new URL(url).pathname;
  return ASSET_PATTERNS.some((re) => re.test(path));
}

// Install: pre-cache nothing; assets are cached on first request.
self.addEventListener('install', () => {
  self.skipWaiting();
});

// Activate: delete caches from previous versions and claim all clients.
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((key) => key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      )
    ).then(() => self.clients.claim())
  );
});

// Fetch: Cache-first for assets, Network-first for everything else.
self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Only handle GET requests over http(s).
  if (request.method !== 'GET' || !request.url.startsWith('http')) return;

  if (isAsset(request.url)) {
    // Cache-first: serve from cache; populate cache on miss.
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cached = await cache.match(request);
        if (cached) return cached;
        const response = await fetch(request);
        if (response.ok) cache.put(request, response.clone());
        return response;
      })
    );
  } else {
    // Network-first: try network, fall back to cache (e.g. index.html offline).
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        try {
          const response = await fetch(request);
          if (response.ok) cache.put(request, response.clone());
          return response;
        } catch {
          const cached = await cache.match(request);
          if (cached) return cached;
          throw new Error('Unable to load page. Please check your connection and try again.');
        }
      })
    );
  }
});
