# Architecture / التصميم

- **MainActivity.java**: Browser WebView, media discovery lifecycle, dialogs, download enqueueing, persistent download ID registry, tabs.
- **MediaScanner.java**: Plain-Java URL validation, media-type classification, resolution hint extraction and file name sanitation. Unit-tested.
- **DownloadRowAdapter.java**: Native Android ListView presentation of DownloadManager statuses and percent progress.
- **Android DownloadManager**: Background HTTP(S) direct-file downloads, notification, public Downloads destination. Downloads remain owned by Android even after the UI closes.
- **SharedPreferences**: IDs of downloads queued by this app. No SQL database needed for v0.1.
- **WebView request interception + DOM inspection**: Passive media URL discovery; does not modify site requests or decrypt media.

## Data flow

`Web page` → `WebView resource URLs and HTML5 source elements` → `MediaScanner` → `Media choice dialog` → `DownloadManager` → `Downloads tab`.

## Design and safety decisions

- HTTPS pages can be navigated; cleartext traffic is disabled by the app manifest.
- Arbitrary web pages can run JavaScript inside WebView, but **no JavaScript bridge** exposing native privileged methods is installed.
- WebView file access and mixed content are disabled.
- Media URLs must have an HTTP(S) scheme and host. `javascript:`, `file:` and `blob:` are excluded from native downloads.
- DownloadManager transmits destination cookies and the current page's referer to support ordinary publicly accessible streams. No cookie value is stored by app code.
- The user must confirm before any new download.
- HLS/DASH manifests are detected but rejected with an accurate explanation until an appropriate implementation exists.
- No DRM circumvention, site-specific private APIs, credential harvesting or scraping hidden protected content.

## Known gaps

- No retry/resume controls inside app (Android DownloadManager controls it where server allows).
- No extraction from `blob:` sources or JS-heavy sites without directly observable media links.
- No format conversion, HLS demux, DASH mux, subtitle downloads, bulk playlists or DRM.
- No guaranteed compatibility with any third-party website.
