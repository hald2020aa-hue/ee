package com.ee.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionPolicyTest {
    @Test public void storesOnlyOriginsWithoutSecrets() {
        assertEquals("https://www.facebook.com", SessionPolicy.origin("https://www.facebook.com/login?access_token=secret#x"));
        assertEquals("https://www.facebook.com", SessionPolicy.origin("HTTPS://WWW.FACEBOOK.COM:443/"));
        assertEquals("http://example.org:8080", SessionPolicy.origin("http://example.org:8080/path"));
        assertEquals("", SessionPolicy.origin("javascript:alert(1)"));
        assertEquals("", SessionPolicy.origin("https://user:password@example.org"));
        assertEquals("", SessionPolicy.origin("file:///data/user/0/secret"));
    }

    @Test public void neverMixesDifferentWebsiteOrigins() {
        assertTrue(SessionPolicy.sameOrigin("https://www.facebook.com", "https://www.facebook.com/profile"));
        assertFalse(SessionPolicy.sameOrigin("https://www.facebook.com", "https://www.tiktok.com/profile"));
        assertFalse(SessionPolicy.sameOrigin("https://www.facebook.com", "http://www.facebook.com/profile"));
    }

    @Test public void parsesCookieNamesWithoutStoringValues() {
        assertTrue(SessionPolicy.cookieNames("a=secret; sessionid=1234; csrftoken=xyz").contains("sessionid"));
        assertFalse(SessionPolicy.cookieNames("a=secret").contains("secret"));
        assertFalse(SessionPolicy.cookieNames("bad name=x; fine=1").contains("bad name"));
    }

    @Test public void stripsSensitiveUrlValuesFromBrowsingHistory() {
        assertEquals("https://example.com/video", SessionPolicy.historyUrl("https://example.com/video?code=abc#access_token=secret"));
    }
}
