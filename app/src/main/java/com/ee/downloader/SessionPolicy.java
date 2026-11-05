package com.ee.downloader;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** URL-only helpers. No cookie values or credentials are ever persisted here. */
public final class SessionPolicy {
    private SessionPolicy() { }

    public static String origin(String raw) {
        if (raw == null) return "";
        try {
            URI u = URI.create(raw);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return "";
            if (u.getRawUserInfo() != null) return "";
            String host = u.getHost();
            if (host == null || host.isEmpty()) return "";
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains(":" ) && !host.startsWith("[")) host = "[" + host + "]";
            int port = u.getPort();
            if (port == -1 || (port == 443 && scheme.equals("https")) || (port == 80 && scheme.equals("http"))) {
                return scheme + "://" + host;
            }
            if (port < 1 || port > 65535) return "";
            return scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    public static boolean sameOrigin(String expectedOrigin, String url) {
        return expectedOrigin != null && !expectedOrigin.isEmpty() && expectedOrigin.equals(origin(url));
    }

    /** Cookie header -> names only. Do not retain secrets, values, or copy into preferences. */
    public static Set<String> cookieNames(String header) {
        if (header == null || header.isEmpty()) return Collections.emptySet();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String name = part.substring(0, equals).trim();
            if (name.matches("[!#$%&'*+.^_`|~A-Za-z0-9-]{1,128}")) names.add(name);
            if (names.size() >= 80) break;
        }
        return names;
    }

    /** Best-effort candidate paths; Android does not expose cookie Path/Domain metadata. */
    public static List<String> cookiePaths(String pageUrl) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add("/");
        try {
            URI u = URI.create(pageUrl);
            String path = u.getPath();
            if (path != null && path.startsWith("/")) {
                String[] segments = path.split("/");
                StringBuilder current = new StringBuilder();
                for (String part : segments) {
                    if (part.isEmpty()) continue;
                    if (!part.matches("[a-zA-Z0-9_.~-]{1,64}")) break;
                    current.append('/').append(part);
                    paths.add(current.toString());
                    paths.add(current.toString() + "/");
                    if (paths.size() >= 12) break;
                }
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(paths);
    }

    /** Do not store OAuth codes, access tokens or passwords from URL query/fragment in history. */
    public static String historyUrl(String raw) {
        try {
            if (origin(raw).isEmpty()) return "";
            URI uri = URI.create(raw);
            URI safe = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
            return safe.toASCIIString();
        } catch (Exception ex) {
            return origin(raw);
        }
    }
}
