package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DiagnosticRedactorTest {
    private static final String REFRESH = "1//0gAbCdEfGhIjKlMnOpQrStUvWxYz-0123456789";
    private static final String ACCESS = "ya29.a0AfH6SMBxYz0123456789abcdefGHIJKL";
    private static final String CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT";
    private static final String USER_IP = "81.33.12.4";

    @Test
    public void oauthRefreshBody_losesTokenAndSecret() {
        String line = "D/QueryStringRequestBodyConverter: refresh_token=" + REFRESH
                + "&client_id=861556708454.apps.googleusercontent.com&client_secret=" + CLIENT_SECRET
                + "&grant_type=refresh_token";
        String out = DiagnosticRedactor.redact(line);
        assertFalse(out, out.contains(REFRESH));
        assertFalse(out, out.contains(CLIENT_SECRET));
        assertTrue(out, out.contains("grant_type=refresh_token"));
    }

    @Test
    public void oauthJsonBody_losesTokens() {
        String line = "{\"refresh_token\":\"" + REFRESH + "\",\"access_token\":\"" + ACCESS
                + "\",\"client_secret\":\"" + CLIENT_SECRET + "\"}";
        String out = DiagnosticRedactor.redact(line);
        assertFalse(out, out.contains(REFRESH));
        assertFalse(out, out.contains(ACCESS));
        assertFalse(out, out.contains(CLIENT_SECRET));
    }

    @Test
    public void bearerHeaderAndStoredToken_areRemoved() {
        assertFalse(DiagnosticRedactor.redact("authorization: Bearer " + ACCESS).contains("a0AfH6"));
        assertFalse(DiagnosticRedactor.redact(
                "Success. Refresh token stored successfully in registry: " + REFRESH).contains(REFRESH));
    }

    @Test
    public void googlevideoQuery_keepsMediaParamsOnly() {
        String url = "https://rr3---sn-h5q7knes.googlevideo.com/videoplayback?expire=1759000000&ei=AbCdEfGh"
                + "&ip=" + USER_IP + "&id=o-ABCdef&itag=244&source=youtube&requiressl=yes&mime=video%2Fwebm"
                + "&clen=123456&sig=AJfQdSswRQIhAKxyz&lsig=APaTxxMwRAIg&n=abcdefgh&pot=MnQxYzJiNTU1&range=0-1000";
        String out = DiagnosticRedactor.redact("E/Media3: load failed " + url + " code=403");
        assertFalse(out, out.contains(USER_IP));
        assertFalse(out, out.contains("AJfQdSsw"));
        assertFalse(out, out.contains("MnQxYzJi"));
        assertFalse(out, out.contains("AbCdEfGh"));
        assertTrue(out, out.contains("rr3---sn-h5q7knes.googlevideo.com/videoplayback?"));
        assertTrue(out, out.contains("itag=244"));
        assertTrue(out, out.contains("range=0-1000"));
        assertTrue(out, out.contains("expire=1759000000"));
        assertTrue(out, out.contains("code=403"));
    }

    @Test
    public void videoIdsInServiceUrls_areKept() {
        String out = DiagnosticRedactor.redact("https://sponsor.ajay.app/api/skipSegments?videoID=iG9CE55wbtY"
                + "&categories=%5B%22sponsor%22%5D https://www.youtube.com/api/stats/playback?docid=iG9CE55wbtY"
                + "&cpn=h8cHXvUnH0aSxmr4&ei=AbCdEfGh");
        assertTrue(out, out.contains("videoID=iG9CE55wbtY"));
        assertTrue(out, out.contains("docid=iG9CE55wbtY"));
        assertFalse(out, out.contains("h8cHXvUnH0aSxmr4"));
        assertFalse(out, out.contains("AbCdEfGh"));
    }

    @Test
    public void manifestPathParams_areBlanked() {
        String url = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1759/ei/XyZabc/ip/"
                + USER_IP + "/id/abc123/sig/AJfQdSsw/file/index.m3u8";
        String out = DiagnosticRedactor.redact(url);
        assertFalse(out, out.contains(USER_IP));
        assertFalse(out, out.contains("AJfQdSsw"));
        assertFalse(out, out.contains("XyZabc"));
        assertTrue(out, out.contains("/expire/1759/"));
        assertTrue(out, out.contains("/id/abc123/"));
    }

    @Test
    public void jsonEscapedUrl_isSanitizedToo() {
        String out = DiagnosticRedactor.redact(
                "\"url\":\"https:\\/\\/rr1---sn-x.googlevideo.com\\/videoplayback?ip=" + USER_IP + "&itag=18\"");
        assertFalse(out, out.contains(USER_IP));
        assertTrue(out, out.contains("itag=18"));
    }

    @Test
    public void poTokenAndVisitorData_areRemoved() {
        String visitor = "CgtBQ1hRcURYN0VXTSiQ7pLHBjIKCgJFUxIEGgAgNQ%3D%3D";
        String pot = "MnQxYzJiNTU1NWE2YzlkNjM2ZjM4OGU5ZTNkYzI5";
        String out = DiagnosticRedactor.redact("D/PoTokenWebView: Generated poToken: identifier=" + visitor
                + " poToken=" + pot);
        assertFalse(out, out.contains(visitor));
        assertFalse(out, out.contains(pot));
        assertFalse(DiagnosticRedactor.redact("{\"visitorData\":\"" + visitor + "\"}").contains(visitor));
    }

    @Test
    public void cookies_areRemoved() {
        String out = DiagnosticRedactor.redact(
                "Cookie: SAPISID=abc123xyz789; __Secure-3PAPISID=def456uvw012; YSC=q1w2e3r4");
        assertFalse(out, out.contains("abc123xyz789"));
        assertFalse(out, out.contains("def456uvw012"));
        assertFalse(out, out.contains("q1w2e3r4"));
    }

    @Test
    public void email_isRemoved() {
        assertEquals("account <email> signed in", DiagnosticRedactor.redact("account someone.test@gmail.com signed in"));
    }

    @Test
    public void netPathFlagLines_areUntouched() {
        String[] lines = {
                "09-27 14:59:29.419  5327  5401 D NetPath : player-http[S] rid=1 video=dQw4w9WgXcQ client=101"
                        + " cver=1.02 visitor=75eab55d0f pot=n auth=n cookie=n authUser=n contentOk=y racyOk=y sts=y"
                        + " stsDigits=5 ua=50caeaeae2 origin=youtube referer=none key=n net=wifi:101",
                "09-27 14:59:29.652  5327  5397 D NetPath : player-sig video=dQw4w9WgXcQ holders=28 n=0/0 s=0/0"
                        + " nOut=0/0,unchanged=0,size=absent sOut=0/0,unchanged=0,size=absent ms=1",
                "09-27 14:59:29.948  5327  5459 D NetPath : web-pot-session new reason=initial visitorSource=app"
                        + " visitor=75eab55d0f prevAgeMs=-1 buildMs=1867 binding=streaming:visitor,player:video",
                "\tat com.newtube.mobile.player.Media3SourceFactory.fromDashFormatInfo(Media3SourceFactory.java:930)",
        };
        for (String line : lines) {
            assertEquals(line, DiagnosticRedactor.redact(line));
        }
    }

    @Test
    public void longOpaqueBlob_isReplaced() {
        String blob = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB";
        assertEquals("payload <blob:" + blob.length() + "> end", DiagnosticRedactor.redact("payload " + blob + " end"));
    }

    // --- Cases from the cross-model review (2026-09-27) ---

    @Test
    public void spacedAuthLabels_areRedacted() {
        String out = DiagnosticRedactor.redact("Debug data: device code: AH-1Ng2abcdefXYZ, client id: 861556708454,"
                + " client secret: " + CLIENT_SECRET);
        assertFalse(out, out.contains("AH-1Ng2abcdefXYZ"));
        assertFalse(out, out.contains(CLIENT_SECRET));
    }

    @Test
    public void proxyCredentials_areStripped() {
        String http = DiagnosticRedactor.redact("Proxy: http://alice:hunter2secret@10.0.0.2:8080");
        assertFalse(http, http.contains("alice"));
        assertFalse(http, http.contains("hunter2secret"));
        assertTrue(http, http.contains("@10.0.0.2:8080"));
        String socks = DiagnosticRedactor.redact("proxy socks5://bob:pa55word99@proxy.example:1080/");
        assertFalse(socks, socks.contains("pa55word99"));
        assertFalse(socks, socks.contains("bob"));
    }

    @Test
    public void upperCaseAndEncodedUrls_areSanitized() {
        String upper = DiagnosticRedactor.redact("HTTPS://RR1---SN-X.GOOGLEVIDEO.COM/videoplayback?IP=" + USER_IP + "&itag=18");
        assertFalse(upper, upper.contains(USER_IP));
        String encoded = DiagnosticRedactor.redact("redirect url=https%3A%2F%2Frr1---sn-x.googlevideo.com%2Fvideoplayback"
                + "%3Fip%3D" + USER_IP.replace(".", "%2E") + "%26itag%3D18 done");
        assertFalse(encoded, encoded.contains(USER_IP));
        assertFalse(encoded, encoded.contains("81%2E33"));
        assertTrue(encoded, encoded.contains("itag=18"));
        assertTrue(encoded, encoded.endsWith(" done"));
    }

    @Test
    public void anyAuthorizationScheme_isRedacted() {
        String out = DiagnosticRedactor.redact("Authorization: SAPISIDHASH 1759000000_abcdef0123456789abcdef");
        assertFalse(out, out.contains("abcdef0123456789"));
        String bare = DiagnosticRedactor.redact("sending SAPISIDHASH 1759000000_abcdef0123456789abcdef now");
        assertFalse(bare, bare.contains("abcdef0123456789"));
    }

    @Test
    public void nsigAndEventIdOutsideUrls_areRedacted() {
        String out = DiagnosticRedactor.redact("decode failed n=abcdefgh ei=AbCdEfGhIj");
        assertFalse(out, out.contains("abcdefgh"));
        assertFalse(out, out.contains("AbCdEfGhIj"));
    }

    @Test
    public void accountNameJsonFields_areRedacted() {
        String out = DiagnosticRedactor.redact("{\"accountName\": \"Alice Example\", \"channelHandle\":\"@alice\","
                + "\"videoId\":\"iG9CE55wbtY\"}");
        assertFalse(out, out.contains("Alice Example"));
        assertFalse(out, out.contains("@alice"));
        assertTrue(out, out.contains("iG9CE55wbtY"));
    }

    @Test
    public void ring_dropsOldestPastLineOrCharBudget() {
        DiagnosticLog.LineRing byLines = new DiagnosticLog.LineRing(3, 1_000, 100);
        for (int i = 0; i < 5; i++) {
            byLines.add("line" + i);
        }
        List<String> kept = byLines.snapshot();
        assertEquals(3, kept.size());
        assertEquals("line2", kept.get(0));
        assertEquals("line4", kept.get(2));

        DiagnosticLog.LineRing byChars = new DiagnosticLog.LineRing(100, 10, 100);
        byChars.add("aaaaa");
        byChars.add("bbbbb");
        byChars.add("ccccc");
        assertEquals(2, byChars.size());
        assertEquals("bbbbb", byChars.snapshot().get(0));

        DiagnosticLog.LineRing truncating = new DiagnosticLog.LineRing(10, 100, 4);
        truncating.add("abcdefgh");
        assertEquals("abcd…", truncating.snapshot().get(0));
    }

    @Test
    public void timestampOf_readsThreadtimePrefix() {
        assertEquals("09-27 14:59:29.419",
                DiagnosticLog.timestampOf("09-27 14:59:29.419  5327  5401 D NetPath : x"));
        assertNull(DiagnosticLog.timestampOf("--------- beginning of main"));
        assertNull(DiagnosticLog.timestampOf("short"));
    }
}
