package com.ee.downloader;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless URL classification. It never bypasses authentication or DRM. */
public final class MediaScanner {
    private static final Pattern MEDIA = Pattern.compile("(?i)\\.(mp4|m4v|webm|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(?:$|[?#])");
    private static final Pattern QUALITY = Pattern.compile("(?i)(?:^|[/_.?=&-])(2160|1440|1080|720|480|360|240)p(?:$|[/_.?=&-])");
    private static final Pattern RESOLUTION = Pattern.compile("(?i)(?:resolution|res|quality|height)=(2160|1440|1080|720|480|360|240)(?:p|%20p)?(?:$|&)");

    private MediaScanner() { }

    public static boolean isHttpUrl(String raw) {
        if (raw == null) return false;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean looksLikeMedia(String raw) {
        return isHttpUrl(raw) && MEDIA.matcher(raw).find();
    }

    public static boolean isStreamManifest(String raw) {
        if (!isHttpUrl(raw)) return false;
        String path = URI.create(raw).getPath();
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".m3u8") || p.endsWith(".mpd");
    }

    public static String mediaType(String raw) {
        if (isStreamManifest(raw)) return "بث مباشر / قائمة تشغيل";
        String ext = extension(raw);
        switch (ext) {
            case "mp3": case "m4a": case "aac": case "ogg": case "opus": case "wav": case "flac":
                return "صوت • " + ext.toUpperCase(Locale.ROOT);
            case "mp4": case "m4v": case "webm": case "mov": case "3gp":
                return "فيديو • " + ext.toUpperCase(Locale.ROOT);
            default: return "وسائط";
        }
    }

    public static String quality(String raw) {
        if (raw == null) return "الجودة حسب المصدر";
        Matcher param = RESOLUTION.matcher(raw);
        if (param.find()) return param.group(1) + "p";
        Matcher m = QUALITY.matcher(raw);
        if (m.find()) return m.group(1) + "p";
        return "الجودة حسب المصدر";
    }

    public static String extension(String raw) {
        if (!isHttpUrl(raw)) return "";
        String path = URI.create(raw).getPath();
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{2,5}") ? ext : "";
    }

    public static String safeFilename(String url) {
        String name = "";
        if (isHttpUrl(url)) {
            String path = URI.create(url).getRawPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                name = path.substring(slash + 1);
                try { name = URLDecoder.decode(name, StandardCharsets.UTF_8.name()); }
                catch (Exception ignored) { }
            }
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.length() > 100) name = name.substring(0, 100);
        String ext = extension(url);
        if (name.length() < 3 || ext.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith("." + ext)) {
            name = "EE_" + System.currentTimeMillis() + (ext.isEmpty() ? ".mp4" : "." + ext);
        }
        return name;
    }
}
