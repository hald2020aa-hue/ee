package com.ee.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EE Downloader MVP.
 * Detects public/direct HTML5 media links from WebView traffic and DOM.
 * It intentionally does not bypass logins, DRM, CAPTCHA or site restrictions.
 */
public final class MainActivity extends Activity {
    private static final String PREF = "ee_downloads";
    private static final String KEY_IDS = "download_ids";
    private static final int MAX_CANDIDATES = 120;
    private static final Pattern SHARED_LINK = Pattern.compile("https?://[^\\s<>\"']+");
    private final LinkedHashMap<String, String> media = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (downloadsPane != null && downloadsPane.getVisibility() == View.VISIBLE) refreshDownloads();
            handler.postDelayed(this, 2500);
        }
    };

    private EditText address;
    private WebView web;
    private ProgressBar loading;
    private Button browserTab, downloadsTab, mediaButton;
    private View browserPane, downloadsPane;
    private ListView downloadsList;
    private DownloadRowAdapter downloadAdapter;
    private DownloadManager downloadManager;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        address = findViewById(R.id.addressInput);
        web = findViewById(R.id.webView);
        loading = findViewById(R.id.loadingBar);
        mediaButton = findViewById(R.id.mediaButton);
        browserTab = findViewById(R.id.browserTab);
        downloadsTab = findViewById(R.id.downloadsTab);
        browserPane = findViewById(R.id.browserPane);
        downloadsPane = findViewById(R.id.downloadsPane);
        downloadsList = findViewById(R.id.downloadsList);
        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        downloadAdapter = new DownloadRowAdapter(this);
        downloadsList.setAdapter(downloadAdapter);
        downloadsList.setOnItemClickListener((p, v, position, id) -> openDownload(downloadAdapter.getItem(position)));
        downloadsList.setOnItemLongClickListener((p, v, position, id) -> {
            DownloadRowAdapter.Entry entry = downloadAdapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle("إزالة التنزيل")
                    .setMessage("هل تريد حذف الملف وسجل التنزيل؟")
                    .setPositiveButton("إزالة", (d, which) -> deleteDownload(entry.id))
                    .setNegativeButton("إلغاء", null)
                    .show();
            return true;
        });

        configureWebView();
        findViewById(R.id.goButton).setOnClickListener(v -> navigate(address.getText().toString()));
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(address.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.backButton).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.forwardButton).setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        findViewById(R.id.reloadButton).setOnClickListener(v -> web.reload());
        mediaButton.setOnClickListener(v -> showMediaChoices());
        browserTab.setOnClickListener(v -> showTab(true));
        downloadsTab.setOnClickListener(v -> showTab(false));
        handleSharedIntent(getIntent());
        if (web.getUrl() == null) web.loadUrl("https://www.google.com");
        handler.postDelayed(tick, 1500);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String disposition,
                                                 String mimetype, long contentLength) {
                if (MediaScanner.isHttpUrl(url)) {
                    addMedia(url, "تنزيل من الصفحة");
                    runOnUiThread(() -> confirmDownload(url));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                loading.setProgress(newProgress);
                loading.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (MediaScanner.isHttpUrl(url)) address.setText(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (MediaScanner.isHttpUrl(url)) address.setText(url);
                inspectDocument();
                handler.postDelayed(() -> inspectDocument(), 1800);
                handler.postDelayed(() -> inspectDocument(), 4200);
            }

            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String link = request.getUrl().toString();
                if (MediaScanner.looksLikeMedia(link)) addMedia(link, "رابط من الشبكة");
                return super.shouldInterceptRequest(view, request);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !MediaScanner.isHttpUrl(request.getUrl().toString());
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الصفحة. تحقق من الاتصال والرابط.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void navigate(String raw) {
        String link = raw == null ? "" : raw.trim();
        if (!link.contains("://") && !link.isEmpty()) link = "https://" + link;
        if (!MediaScanner.isHttpUrl(link)) {
            toast("أدخل رابط موقع يبدأ بـ HTTPS أو HTTP");
            return;
        }
        showTab(true);
        if (MediaScanner.looksLikeMedia(link)) {
            addMedia(link, "رابط مباشر");
            final String direct = link;
            new AlertDialog.Builder(this)
                    .setTitle("رابط وسائط")
                    .setMessage("تم اكتشاف رابط وسائط مباشر. هل تريد تنزيله؟")
                    .setPositiveButton("تنزيل", (d, w) -> confirmDownload(direct))
                    .setNegativeButton("فتح في المتصفح", (d, w) -> web.loadUrl(direct))
                    .show();
        } else web.loadUrl(link);
        address.clearFocus();
    }

    private void inspectDocument() {
        if (web == null) return;
        String script = "(function(){var a=[];" +
                "document.querySelectorAll('video,audio,source').forEach(function(e){" +
                "var u=e.currentSrc||e.src||e.getAttribute('data-src');if(u)a.push(u);});" +
                "document.querySelectorAll('a[href]').forEach(function(e){" +
                "var u=e.href;if(/\\.(mp4|webm|mp3|m4a|m3u8|mpd)(\\?|#|$)/i.test(u))a.push(u);});" +
                "return Array.from(new Set(a)).slice(0,80);})()";
        web.evaluateJavascript(script, result -> {
            try {
                JSONArray arr = new JSONArray(result);
                for (int i = 0; i < arr.length(); i++) addMedia(arr.optString(i), "عنصر في الصفحة");
            } catch (Exception ignored) { }
        });
    }

    private void addMedia(String url, String origin) {
        if (!MediaScanner.isHttpUrl(url)) return;
        synchronized (media) {
            if (!media.containsKey(url)) {
                if (media.size() >= MAX_CANDIDATES) {
                    String oldest = media.keySet().iterator().next();
                    media.remove(oldest);
                }
                media.put(url, origin);
            }
        }
        runOnUiThread(() -> mediaButton.setText("الوسائط المكتشفة (" + media.size() + ")"));
    }

    private void showMediaChoices() {
        inspectDocument();
        final List<String> links;
        synchronized (media) { links = new ArrayList<>(media.keySet()); }
        if (links.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("لم تُكتشف وسائط بعد")
                    .setMessage("شغّل الفيديو في الصفحة ثم اضغط اكتشاف. بعض المواقع تستخدم روابط مشفّرة أو حماية DRM ولا يمكن استخراجها بهذا الإصدار.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        Collections.reverse(links);
        String[] labels = new String[links.size()];
        for (int i = 0; i < links.size(); i++) {
            String link = links.get(i);
            String host;
            try { host = Uri.parse(link).getHost(); } catch (Exception ex) { host = "ملف"; }
            labels[i] = MediaScanner.mediaType(link) + "  |  " + MediaScanner.quality(link)
                    + "\n" + (host == null ? "ملف" : host) + " • " + shorten(link, 72);
        }
        new AlertDialog.Builder(this)
                .setTitle("اختر الوسائط والجودة المتاحة")
                .setItems(labels, (dialog, which) -> confirmDownload(links.get(which)))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void confirmDownload(String link) {
        if (!MediaScanner.isHttpUrl(link)) return;
        if (MediaScanner.isStreamManifest(link)) {
            new AlertDialog.Builder(this)
                    .setTitle("بث HLS / DASH")
                    .setMessage("تم اكتشاف قائمة تشغيل، لكن تنزيل HLS وDASH ودمج الأجزاء غير مدعوم في هذه النسخة. التنزيل يعمل حاليًا لملفات الوسائط المباشرة مثل MP4 وMP3 وM4A وWebM.")
                    .setPositiveButton("فهمت", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("بدء تنزيل الملف")
                .setMessage("الملف: " + MediaScanner.safeFilename(link)
                        + "\nالجودة: " + MediaScanner.quality(link)
                        + "\n\nسيُحفظ في مجلد Downloads.")
                .setPositiveButton("تنزيل", (d, which) -> enqueueDownload(link))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void enqueueDownload(String link) {
        try {
            String fileName = MediaScanner.safeFilename(link);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(link));
            request.setTitle(fileName);
            request.setDescription("EE Downloader");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MediaScanner.extension(link));
            if (mime != null) request.setMimeType(mime);
            String userAgent = web.getSettings().getUserAgentString();
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(link);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            String referer = web.getUrl();
            if (MediaScanner.isHttpUrl(referer)) request.addRequestHeader("Referer", referer);
            long id = downloadManager.enqueue(request);
            Set<String> ids = new LinkedHashSet<>(getPreferences(MODE_PRIVATE).getStringSet(KEY_IDS, Collections.emptySet()));
            ids.add(Long.toString(id));
            getPreferences(MODE_PRIVATE).edit().putStringSet(KEY_IDS, ids).apply();
            toast("بدأ التنزيل. افتح تبويب التنزيلات للمتابعة.");
            showTab(false);
        } catch (Exception ex) {
            toast("تعذر بدء التنزيل: " + ex.getClass().getSimpleName());
        }
    }

    private void showTab(boolean browserVisible) {
        browserPane.setVisibility(browserVisible ? View.VISIBLE : View.GONE);
        downloadsPane.setVisibility(browserVisible ? View.GONE : View.VISIBLE);
        browserTab.setBackgroundResource(browserVisible ? R.drawable.primary_button : R.drawable.secondary_button);
        downloadsTab.setBackgroundResource(browserVisible ? R.drawable.secondary_button : R.drawable.primary_button);
        browserTab.setTextColor(browserVisible ? 0xFF0B1020 : 0xFFF8FAFF);
        downloadsTab.setTextColor(browserVisible ? 0xFFF8FAFF : 0xFF0B1020);
        if (!browserVisible) refreshDownloads();
    }

    private void refreshDownloads() {
        Set<String> stored = getPreferences(MODE_PRIVATE).getStringSet(KEY_IDS, Collections.emptySet());
        if (stored.isEmpty()) { downloadAdapter.replace(Collections.emptyList()); return; }
        List<Long> numeric = new ArrayList<>();
        for (String s : stored) {
            try { numeric.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
        }
        if (numeric.isEmpty()) return;
        long[] ids = new long[numeric.size()];
        for (int i = 0; i < numeric.size(); i++) ids[i] = numeric.get(i);
        List<DownloadRowAdapter.Entry> entries = new ArrayList<>();
        try (Cursor c = downloadManager.query(new DownloadManager.Query().setFilterById(ids))) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
                int nameCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
                int statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                int totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                while (c.moveToNext()) {
                    long total = c.getLong(totalCol);
                    long done = c.getLong(doneCol);
                    int percent = total > 0 ? (int) Math.min(100L, (done * 100L) / total) : 0;
                    entries.add(new DownloadRowAdapter.Entry(c.getLong(idCol), c.getString(nameCol), c.getInt(statusCol), percent));
                }
            }
        } catch (Exception ex) { toast("تعذر تحديث قائمة التنزيلات"); }
        entries.sort((a, b) -> Long.compare(b.id, a.id));
        downloadAdapter.replace(entries);
    }

    private void openDownload(DownloadRowAdapter.Entry item) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            toast(item.status == DownloadManager.STATUS_FAILED ? "فشل تنزيل هذا الملف" : "التنزيل لم يكتمل بعد");
            return;
        }
        Uri uri = downloadManager.getUriForDownloadedFile(item.id);
        if (uri == null) { toast("تعذر العثور على الملف"); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "فتح الملف باستخدام"));
        } catch (Exception ex) { toast("لم يُعثر على تطبيق لفتح الملف"); }
    }

    private void deleteDownload(long id) {
        downloadManager.remove(id);
        Set<String> ids = new LinkedHashSet<>(getPreferences(MODE_PRIVATE).getStringSet(KEY_IDS, Collections.emptySet()));
        ids.remove(Long.toString(id));
        getPreferences(MODE_PRIVATE).edit().putStringSet(KEY_IDS, ids).apply();
        refreshDownloads();
    }

    private void handleSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher matcher = SHARED_LINK.matcher(text);
        if (matcher.find()) {
            String link = matcher.group();
            address.setText(link);
            navigate(link);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    private String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (downloadsPane.getVisibility() == View.VISIBLE) showTab(true);
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
