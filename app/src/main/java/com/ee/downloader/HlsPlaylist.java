package com.ee.downloader;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative HLS parser: intentionally supports only plain TS-based VOD. */
public final class HlsPlaylist {
    public static final class Variant {
        public final String url, label;
        public Variant(String url, String label) { this.url = url; this.label = label; }
    }
    public static final class Media {
        public final List<String> segments;
        public final String error;
        private Media(List<String> segments, String error) { this.segments=segments; this.error=error; }
        public boolean supported() { return error == null; }
    }
    private static final Pattern RESOLUTION = Pattern.compile("(?:^|,)RESOLUTION=\\d+x(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
    private HlsPlaylist() {}

    public static boolean isHls(String url) {
        try { return URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"); }
        catch (Exception e) { return false; }
    }
    private static String resolve(String base, String reference) {
        URI target = URI.create(base).resolve(reference.trim());
        if (!"https".equalsIgnoreCase(target.getScheme()) && !"http".equalsIgnoreCase(target.getScheme()))
            throw new IllegalArgumentException("Unsupported URL scheme");
        if (target.getHost() == null) throw new IllegalArgumentException("Missing host");
        return target.toString();
    }
    public static List<Variant> variants(String base, String content) {
        List<Variant> out = new ArrayList<>();
        if (content == null || content.length() > 2_000_000) return out;
        String[] lines = content.replace("\r", "").split("\n");
        boolean pending = false;
        String attributes = "";
        for (String original : lines) {
            String line=original.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = true; attributes=line.substring("#EXT-X-STREAM-INF:".length()); continue;
            }
            if (pending && !line.isEmpty() && !line.startsWith("#")) {
                try {
                    Matcher r=RESOLUTION.matcher(attributes), bw=BANDWIDTH.matcher(attributes);
                    String label = r.find() ? r.group(1)+"p" : bw.find() ? Math.round(Long.parseLong(bw.group(1))/1000f)+" kbps" : "تلقائي";
                    out.add(new Variant(resolve(base,line),label));
                } catch (Exception ignored) { }
                pending=false;
                if (out.size() >= 30) break;
            }
        }
        return out;
    }
    public static Media media(String base, String content) {
        if (content==null || !content.trim().startsWith("#EXTM3U")) return fail("رابط قائمة تشغيل غير صالح");
        if (content.length() > 2_000_000) return fail("قائمة تشغيل كبيرة جدًا");
        if (content.contains("#EXT-X-STREAM-INF")) return fail("اختر جودة من القائمة الرئيسية أولًا");
        if (!content.contains("#EXT-X-ENDLIST")) return fail("البث المباشر غير مدعوم حاليًا");
        String upper=content.toUpperCase(Locale.ROOT);
        if (upper.contains("#EXT-X-KEY:") || upper.contains("#EXT-X-SESSION-KEY:")) return fail("قائمة تشغيل مشفّرة غير مدعومة");
        if (upper.contains("#EXT-X-MAP:") || upper.contains("#EXT-X-BYTERANGE:") || upper.contains("#EXT-X-DISCONTINUITY")) return fail("نوع مقاطع HLS هذا غير مدعوم");
        if (upper.contains("#EXT-X-MEDIA:") || upper.contains("#EXT-X-I-FRAME-STREAM-INF")) return fail("مسارات الصوت أو الفيديو المنفصلة غير مدعومة");
        List<String> segments=new ArrayList<>();
        for (String original: content.replace("\r", "").split("\n")) {
            String s=original.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            try {
                String u=resolve(base,s);
                String path=URI.create(u).getPath().toLowerCase(Locale.ROOT);
                if (!path.endsWith(".ts")) return fail("يمكن حفظ مقاطع .ts فقط بهذا المحرك");
                segments.add(u);
                if (segments.size()>5000) return fail("عدد المقاطع يتجاوز الحد الآمن");
            } catch (Exception ex) { return fail("رابط مقطع غير صالح"); }
        }
        if (segments.isEmpty()) return fail("لم توجد مقاطع للتحميل");
        return new Media(segments,null);
    }
    private static Media fail(String error){ return new Media(new ArrayList<>(),error); }
}
