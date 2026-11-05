package com.ee.downloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Preserves WebView's native, domain-scoped sessions. Only origins (never cookie values
 * or passwords) are written to this application's SharedPreferences.
 * Site-specific cleanup is best-effort: WebView cannot enumerate every cookie path,
 * domain attribute, IndexedDB record, or server-side session.
 */
final class SiteSessions {
    private static final String PREFS = "site_session_metadata";
    private static final String KEY_ORIGINS = "visited_origins";
    private static final String KEY_THIRD_PARTY = "third_party_cookies";
    private final SharedPreferences prefs;
    private final WebView web;

    SiteSessions(Context context, WebView web) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.web = web;
    }

    void configure() {
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        manager.setAcceptThirdPartyCookies(web, thirdPartyEnabled());
        // Never expose website cookies to USB debugging in production builds.
        WebView.setWebContentsDebuggingEnabled(false);
    }

    boolean thirdPartyEnabled() { return prefs.getBoolean(KEY_THIRD_PARTY, false); }

    void setThirdPartyEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_THIRD_PARTY, enabled).apply();
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, enabled);
    }

    void remember(String url) {
        String origin = SessionPolicy.origin(url);
        if (origin.isEmpty()) return;
        Set<String> stored = new LinkedHashSet<>(prefs.getStringSet(KEY_ORIGINS, Collections.emptySet()));
        if (stored.add(origin)) {
            // Preserve only site origins, never query strings, passwords, tokens or cookie data.
            prefs.edit().putStringSet(KEY_ORIGINS, stored).apply();
        }
    }

    List<String> visitedOrigins() {
        List<String> origins = new ArrayList<>();
        for (String site : prefs.getStringSet(KEY_ORIGINS, Collections.emptySet())) {
            if (site.equals(SessionPolicy.origin(site))) origins.add(site);
        }
        Collections.sort(origins);
        return origins;
    }

    boolean hasVisibleCookies(String origin) {
        if (SessionPolicy.origin(origin).isEmpty()) return false;
        String header = CookieManager.getInstance().getCookie(origin + "/");
        return header != null && !header.isEmpty();
    }

    void persist() {
        // Forces WebView-managed cookies to disk, without exporting their values.
        CookieManager.getInstance().flush();
    }

    void clearSite(String origin, String currentPage, Runnable completion) {
        if (!origin.equals(SessionPolicy.origin(origin))) { completion.run(); return; }
        boolean active = SessionPolicy.sameOrigin(origin, currentPage);
        if (active) {
            // Executes only on the current site's own origin: no cross-origin storage access.
            String clearJs = "(function(){try{localStorage.clear()}catch(e){};" +
                    "try{sessionStorage.clear()}catch(e){};return true;})()";
            web.evaluateJavascript(clearJs, result -> {
                web.loadUrl("about:blank");
                expireVisibleSiteCookies(origin, currentPage, completion);
            });
        } else {
            expireVisibleSiteCookies(origin, null, completion);
        }
    }

    private void expireVisibleSiteCookies(String origin, String activePage, Runnable completion) {
        CookieManager manager = CookieManager.getInstance();
        // This API is per-origin; never use removeAllCookies / removeSessionCookies here.
        WebStorage.getInstance().deleteOrigin(origin);
        Set<String> names = new LinkedHashSet<>(SessionPolicy.cookieNames(manager.getCookie(origin + "/")));
        List<String> paths = Collections.singletonList("/");
        if (activePage != null && SessionPolicy.sameOrigin(origin, activePage)) {
            names.addAll(SessionPolicy.cookieNames(manager.getCookie(activePage)));
            paths = SessionPolicy.cookiePaths(activePage);
        }
        if (names.isEmpty()) {
            manager.flush();
            completion.run();
            return;
        }
        String host;
        try { host = URI.create(origin).getHost(); }
        catch (Exception ex) { manager.flush(); completion.run(); return; }
        // Build all deletions first so callbacks can determine the final completion.
        List<String[]> operations = new ArrayList<>();
        for (String name : names) {
            for (String path : paths) {
                // Cookie name prefixes require matching Secure/HttpOnly/Path restrictions.
                boolean hostPrefixed = name.startsWith("__Host-");
                if (hostPrefixed && !"/".equals(path)) continue;
                String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=" + path;
                if (origin.startsWith("https://")) expired += "; Secure";
                if (name.startsWith("__Http-") || name.startsWith("__Host-Http-")) expired += "; HttpOnly";
                operations.add(new String[]{origin + path, expired});
                // __Host- cookies explicitly forbid a Domain attribute.
                if (!hostPrefixed) operations.add(new String[]{origin + path, expired + "; Domain=" + host});
            }
        }
        if (operations.isEmpty()) {
            manager.flush();
            completion.run();
            return;
        }
        AtomicInteger left = new AtomicInteger(operations.size());
        Runnable oneDone = () -> {
            if (left.decrementAndGet() == 0) {
                manager.flush();
                completion.run();
            }
        };
        for (String[] operation : operations) {
            try {
                manager.setCookie(operation[0], operation[1], success -> oneDone.run());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                oneDone.run();
            }
        }
    }
}
