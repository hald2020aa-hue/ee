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
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String KEY_BOOKMARKS = "site_bookmarks";
    private static final String KEY_HISTORY = "site_history";

    private static final int MAX_CANDIDATES = 120;
    private static final Pattern SHARED_LINK = Pattern.compile("https?://[^\\s<>\"']+");
    private final LinkedHashMap<String, String> media = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (downloadsPane != null && downloadsPane.getVisibility() == View.VISIBLE) {
                refreshDownloads();
                hlsStatus.setText(HlsDownloadService.status(MainActivity.this));
            }
            handler.postDelayed(this, 2500);
        }
    };

    private EditText address;
    private WebView web;
    private ProgressBar loading;
    private Button homeTab, browserTab, downloadsTab, settingsTab, mediaButton;
    private View homePane, browserPane, downloadsPane, settingsPane;
    private TextView hlsStatus;
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
        homePane = findViewById(R.id.homePane);
        settingsPane = findViewById(R.id.settingsPane);
        homeTab = findViewById(R.id.homeTab);
        settingsTab = findViewById(R.id.settingsTab);
        hlsStatus = findViewById(R.id.hlsStatus);
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
        homeTab.setOnClickListener(v -> showTab(0));
        browserTab.setOnClickListener(v -> showTab(1));
        downloadsTab.setOnClickListener(v -> showTab(2));
        settingsTab.setOnClickListener(v -> showTab(3));
        findViewById(R.id.homeSearchButton).setOnClickListener(v -> homeSearch());
        EditText homeSearchInput = findViewById(R.id.homeSearchInput);
        homeSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if(actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                homeSearch(); return true;
            }
            return false;
        });
        findViewById(R.id.openBrowserButton).setOnClickListener(v -> showTab(1));
        findViewById(R.id.openDownloadsButton).setOnClickListener(v -> showTab(2));
        findViewById(R.id.quickYoutube).setOnClickListener(v -> navigate("https://m.youtube.com"));
        findViewById(R.id.quickInstagram).setOnClickListener(v -> navigate("https://www.instagram.com"));
        findViewById(R.id.quickTikTok).setOnClickListener(v -> navigate("https://www.tiktok.com"));
        findViewById(R.id.quickFacebook).setOnClickListener(v -> navigate("https://m.facebook.com"));
        findViewById(R.id.quickVimeo).setOnClickListener(v -> navigate("https://vimeo.com"));
        findViewById(R.id.quickDailymotion).setOnClickListener(v -> navigate("https://www.dailymotion.com"));
        findViewById(R.id.bookmarkButton).setOnClickListener(v -> toggleBookmark());
        findViewById(R.id.savedSitesButton).setOnClickListener(v -> displaySites(KEY_BOOKMARKS));
        findViewById(R.id.recentSitesButton).setOnClickListener(v -> displaySites(KEY_HISTORY));
        findViewById(R.id.clearHistoryButton).setOnClickListener(v -> confirmClear(KEY_HISTORY));
        findViewById(R.id.clearBookmarksButton).setOnClickListener(v -> confirmClear(KEY_BOOKMARKS));
        handleSharedIntent(getIntent());
        if (web.getUrl() == null) web.loadUrl("https://www.google.com");
        showTab(0);
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
                synchronized (media) {media.clear();}
                mediaButton.setText("⬇  الوسائط (0)");
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (MediaScanner.isHttpUrl(url)) {
                    address.setText(url);
                    recordHistory(url);
                }
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
        if (!link.contains("://") && !link.isEmpty()) {
            link = (link.contains(" ") || (!link.contains(".") && !link.contains("/")))
                    ? "https://www.google.com/search?q=" + Uri.encode(link)
                    : "https://" + link;
        }
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
        runOnUiThread(() -> {
            int count;
            synchronized (media) { count = media.size(); }
            mediaButton.setText("⬇  الوسائط (" + count + ")");
        });
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
        if (HlsPlaylist.isHls(link)) { chooseHls(link); return; }
        if (MediaScanner.isStreamManifest(link)) {
            new AlertDialog.Builder(this).setTitle("بث DASH")
                    .setMessage("بث DASH لا يزال غير مدعوم في هذه النسخة.")
                    .setPositiveButton("حسنًا", null).show();
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

    private void showTab(boolean browserVisible) {showTab(browserVisible ? 1 : 2);}

    private void showTab(int tab) {
        homePane.setVisibility(tab==0 ? View.VISIBLE : View.GONE);
        browserPane.setVisibility(tab==1 ? View.VISIBLE : View.GONE);
        downloadsPane.setVisibility(tab==2 ? View.VISIBLE : View.GONE);
        settingsPane.setVisibility(tab==3 ? View.VISIBLE : View.GONE);
        Button[] tabs={homeTab,browserTab,downloadsTab,settingsTab};
        for(int i=0;i<tabs.length;i++) {
            tabs[i].setBackgroundResource(i==tab ? R.drawable.primary_button : R.drawable.secondary_button);
            tabs[i].setTextColor(i==tab ? 0xFF0B1211 : 0xFF9FB8AC);
        }
        if(tab==2){refreshDownloads();hlsStatus.setText(HlsDownloadService.status(this));}
    }

    private void homeSearch() {
        EditText input=findViewById(R.id.homeSearchInput);
        String target=input.getText().toString().trim();
        if(target.isEmpty()) {showTab(1);return;}
        address.setText(target);
        navigate(target);
    }

    private void toggleBookmark() {
        String url=web.getUrl();
        if(!MediaScanner.isHttpUrl(url)) {toast("افتح صفحة موقع أولًا");return;}
        Set<String> old=getPreferences(MODE_PRIVATE).getStringSet(KEY_BOOKMARKS,Collections.emptySet());
        Set<String> saved=new LinkedHashSet<>(old);
        if(saved.contains(url)) {saved.remove(url);toast("تم حذف الموقع من المحفوظات");}
        else {saved.add(url);toast("تم حفظ الموقع");}
        getPreferences(MODE_PRIVATE).edit().putStringSet(KEY_BOOKMARKS,saved).apply();
    }

    private void recordHistory(String url) {
        if(!MediaScanner.isHttpUrl(url)) return;
        Set<String> old=getPreferences(MODE_PRIVATE).getStringSet(KEY_HISTORY,Collections.emptySet());
        Set<String> recent=new LinkedHashSet<>(old);
        for(String item:old) if(item.endsWith("\t"+url)) recent.remove(item);
        recent.add(System.currentTimeMillis()+"\t"+url);
        List<String> order=new ArrayList<>(recent);
        Collections.sort(order, Collections.reverseOrder());
        recent.clear();
        for(int i=0;i<Math.min(60,order.size());i++) recent.add(order.get(i));
        getPreferences(MODE_PRIVATE).edit().putStringSet(KEY_HISTORY,recent).apply();
    }

    private void displaySites(String key) {
        Set<String> stored=getPreferences(MODE_PRIVATE).getStringSet(key,Collections.emptySet());
        if(stored.isEmpty()){toast("القائمة فارغة");return;}
        List<String> sites=new ArrayList<>(stored);
        if(KEY_HISTORY.equals(key)) Collections.sort(sites,Collections.reverseOrder());
        else Collections.sort(sites);
        String[] labels=new String[sites.size()];
        for(int i=0;i<sites.size();i++) {
            String item=sites.get(i);
            labels[i]=KEY_HISTORY.equals(key) && item.contains("\t") ? item.substring(item.indexOf('\t')+1) : item;
        }
        new AlertDialog.Builder(this)
            .setTitle(KEY_HISTORY.equals(key)?"سجل التصفح":"المواقع المحفوظة")
            .setItems(labels,(dialog,which)->navigate(labels[which]))
            .setNegativeButton("إغلاق",null).show();
    }

    private void confirmClear(String key) {
        new AlertDialog.Builder(this).setTitle("تأكيد المسح")
            .setMessage("هل تريد مسح هذه القائمة؟")
            .setPositiveButton("مسح",(d,w)->{
                getPreferences(MODE_PRIVATE).edit().remove(key).apply();toast("تم مسح القائمة");
            }).setNegativeButton("إلغاء",null).show();
    }

    private void chooseHls(String url) {
        toast("جارٍ قراءة الجودات المتاحة...");
        String agent=web.getSettings().getUserAgentString();
        String cookie=CookieManager.getInstance().getCookie(url);
        String referer=web.getUrl();
        new Thread(()->{
            try{
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
                c.setConnectTimeout(12000);c.setReadTimeout(15000);
                if(agent!=null)c.setRequestProperty("User-Agent",agent);
                if(cookie!=null)c.setRequestProperty("Cookie",cookie);
                if(MediaScanner.isHttpUrl(referer))c.setRequestProperty("Referer",referer);
                String content;
                try {
                    if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP "+c.getResponseCode());
                    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[4096];int n;
                        while((n=in.read(buffer))!=-1){
                            if(out.size()+n>2_000_000)throw new IllegalStateException("ملف قائمة كبير جدًا");
                            out.write(buffer,0,n);
                        }
                        content=out.toString("UTF-8");
                    }
                }finally {c.disconnect();}
                List<HlsPlaylist.Variant> options=HlsPlaylist.variants(url,content);
                if(!options.isEmpty()) {
                    runOnUiThread(()->{
                        String[] names=new String[options.size()];
                        for(int i=0;i<options.size();i++) names[i]=options.get(i).label;
                        new AlertDialog.Builder(this).setTitle("اختر جودة بث HLS")
                            .setItems(names,(d,which)->startHls(options.get(which).url))
                            .setNegativeButton("إلغاء",null).show();
                    });
                } else {
                    HlsPlaylist.Media m=HlsPlaylist.media(url,content);
                    runOnUiThread(()->{
                        if(m.supported()) new AlertDialog.Builder(this).setTitle("تحميل HLS")
                            .setMessage("سيتم حفظ الفيديو بصيغة TS في Downloads/EE Tube. لا يدعم هذا التنزيل الاستئناف، وقد يستغرق وقتًا طويلًا.")
                            .setPositiveButton("تنزيل",(d,w)->startHls(url))
                            .setNegativeButton("إلغاء",null).show();
                        else new AlertDialog.Builder(this).setTitle("هذه القائمة غير مدعومة")
                            .setMessage(m.error).setPositiveButton("حسنًا",null).show();
                    });
                }
            }catch(Exception ex){runOnUiThread(()->toast("تعذر تحليل بث HLS: "+ex.getMessage()));}
        }).start();
    }
    private void startHls(String url) {
        Intent i=new Intent(this,HlsDownloadService.class).setAction(HlsDownloadService.ACTION_START);
        i.putExtra(HlsDownloadService.EXTRA_URL,url);
        i.putExtra(HlsDownloadService.EXTRA_USER_AGENT,web.getSettings().getUserAgentString());
        String cookie=CookieManager.getInstance().getCookie(url);
        if(cookie!=null)i.putExtra(HlsDownloadService.EXTRA_COOKIE,cookie);
        if(MediaScanner.isHttpUrl(web.getUrl()))i.putExtra(HlsDownloadService.EXTRA_REFERER,web.getUrl());
        try{startForegroundService(i);showTab(2);toast("بدأ تنزيل HLS في الخلفية");}
        catch(Exception e){toast("تعذر بدء خدمة HLS: "+e.getMessage());}
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
        if (browserPane.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else if (homePane.getVisibility() != View.VISIBLE) showTab(0);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
