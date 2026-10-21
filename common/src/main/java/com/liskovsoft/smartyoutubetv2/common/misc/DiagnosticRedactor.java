package com.liskovsoft.smartyoutubetv2.common.misc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(diagnostics): scrubs one logcat line before it leaves the device in a
 * {@link DiagnosticLog} export. Pure Java so the rules are unit-tested on the JVM.
 *
 * <p>What goes: OAuth access/refresh tokens and Bearer headers, cookies, e-mail addresses, PO
 * tokens and visitor data, and every signed-URL parameter (including {@code ip=}, the viewer's
 * public address). What stays: hosts, paths, itags, byte ranges, HTTP codes, video ids - the part
 * a report is sent for. Short values of sensitive-looking keys are kept on purpose: NetPath prints
 * flags such as {@code pot=n} and {@code key=n} that carry no secret.</p>
 *
 * <p>This is a second layer, not a license to log secrets: call sites must keep redacting at the
 * source ({@link NetPath#log}'s contract).</p>
 */
public final class DiagnosticRedactor {
    static final String REDACTED = "<redacted>";
    private static final int MAX_URL_CHARS = 600;
    /** Values this short under a sensitive key are flags (y/n, null), not secrets. */
    private static final int MIN_SECRET_VALUE_CHARS = 6;

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|socks[45]?h?|wss?|ftp)://[^\\s\"'<>\\\\]+");
    /** A URL inside another URL's query (redirect targets, {@code url=} params) is percent-encoded. */
    private static final Pattern ENCODED_URL = Pattern.compile("(?i)\\bhttps?%3A%2F%2F[^\\s\"'<>\\\\&]+");
    /** Everything after the scheme of an Authorization header, whatever the scheme (Bearer, SAPISIDHASH...). */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)\\b((?:proxy-)?authorization[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\r\\n,;}]+)");
    private static final Pattern SAPISID_HASH = Pattern.compile("(?i)\\b(SAPISID(?:1P|3P)?HASH)\\s+\\S+");
    /** Account identity fields as they appear in InnerTube/People JSON. */
    private static final Pattern ACCOUNT_JSON = Pattern.compile(
            "(?i)(\"(?:accountName|displayName|givenName|familyName|accountByline|channelHandle|userName"
                    + "|email|accountPhotoUrl)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern OAUTH_TOKEN = Pattern.compile("\\bya29\\.[A-Za-z0-9._-]+|(?<![A-Za-z0-9:])1//[A-Za-z0-9._-]{10,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]+");
    private static final Pattern SECRET_KEY_VALUE = Pattern.compile(
            "(?i)([\"']?\\b(?:access[ _-]?token|refresh[ _-]?token|id[ _-]?token|client[ _-]?secret"
                    + "|device[ _-]?code|user[ _-]?code"
                    + "|password|passwd|cookie|set-cookie|x-goog-visitor-id|visitor_?data"
                    + "|po_?token|potoken|potokenu8|pot|identifier|sig|lsig|signature|api_?key|key|ip|n|ei)\\b[\"']?"
                    + "\\s*[:=]\\s*[\"']?)([^\"'\\s,;&}\\])]+)");
    private static final Pattern COOKIE = Pattern.compile(
            "\\b(SAPISID|APISID|HSID|SSID|SID|SIDCC|__Secure-[A-Za-z0-9_-]+|LOGIN_INFO|VISITOR_INFO1_LIVE"
                    + "|VISITOR_PRIVACY_METADATA|YSC|NID|CONSENT|SOCS)=([^;\\s&\"']+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9_+/=%-]{64,}");

    /** Query parameters whose values are kept: they describe the media, not the viewer. */
    private static final Set<String> SAFE_QUERY_KEYS = new HashSet<>(Arrays.asList(
            "itag", "mime", "clen", "dur", "range", "rn", "rbuf", "expire", "lmt", "source",
            "c", "cver", "v", "t", "list", "index", "fmt", "kind", "lang", "tlang", "hl", "gl",
            "alr", "requiressl", "sq", "prettyprint", "alt",
            // Video ids and playback-stats shape (SponsorBlock, Return YouTube Dislike, /api/stats).
            "videoid", "docid", "categories", "len", "cmt", "ns", "ver"));
    /** Path-style keys on googlevideo manifest URLs ({@code .../ip/1.2.3.4/sig/...}). */
    private static final Set<String> SECRET_PATH_KEYS = new HashSet<>(Arrays.asList(
            "ip", "sig", "lsig", "signature", "pot", "n", "ei", "sid", "key", "sparams", "lsparams"));

    private DiagnosticRedactor() {
    }

    public static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        // JSON dumps escape slashes ("https:\/\/..."), which would hide their URLs from the URL rule.
        String result = replaceUrls(line.indexOf("\\/") >= 0 ? line.replace("\\/", "/") : line);
        result = replaceEncodedUrls(result);
        result = AUTH_HEADER.matcher(result).replaceAll("$1" + REDACTED);
        result = SAPISID_HASH.matcher(result).replaceAll("$1 " + REDACTED);
        result = BEARER.matcher(result).replaceAll("$1 " + REDACTED);
        result = OAUTH_TOKEN.matcher(result).replaceAll(REDACTED);
        result = JWT.matcher(result).replaceAll(REDACTED);
        result = replaceSecretValues(result);
        result = COOKIE.matcher(result).replaceAll("$1=" + REDACTED);
        result = ACCOUNT_JSON.matcher(result).replaceAll("$1" + REDACTED + "$2");
        result = EMAIL.matcher(result).replaceAll("<email>");
        result = replaceBlobs(result);
        return result;
    }

    private static String replaceUrls(String line) {
        Matcher matcher = URL.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        StringBuffer out = new StringBuffer(line.length());
        do {
            matcher.appendReplacement(out, Matcher.quoteReplacement(sanitizeUrl(matcher.group())));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceEncodedUrls(String line) {
        Matcher matcher = ENCODED_URL.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        StringBuffer out = new StringBuffer(line.length());
        do {
            String decoded;
            try {
                decoded = URLDecoder.decode(matcher.group(), "UTF-8");
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                decoded = null;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    decoded != null ? sanitizeUrl(decoded) : REDACTED));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    static String sanitizeUrl(String url) {
        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        int queryStart = url.indexOf('?');
        String base = queryStart >= 0 ? url.substring(0, queryStart) : url;
        String query = queryStart >= 0 ? url.substring(queryStart + 1) : null;

        StringBuilder out = new StringBuilder(sanitizePath(stripUserInfo(base)));
        if (query != null) {
            out.append('?');
            String[] params = query.split("&", -1);
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                String param = params[i];
                int eq = param.indexOf('=');
                if (eq < 0) {
                    out.append(param);
                    continue;
                }
                String key = param.substring(0, eq);
                out.append(key).append('=');
                out.append(SAFE_QUERY_KEYS.contains(key.toLowerCase(Locale.ROOT))
                        ? param.substring(eq + 1) : REDACTED);
            }
        }
        return out.length() <= MAX_URL_CHARS ? out.toString() : out.substring(0, MAX_URL_CHARS) + "…";
    }

    /** {@code scheme://user:password@host} (proxy settings) keeps the host, never the credentials. */
    private static String stripUserInfo(String base) {
        int authorityStart = base.indexOf("://");
        if (authorityStart < 0) {
            return base;
        }
        authorityStart += 3;
        int authorityEnd = base.indexOf('/', authorityStart);
        String authority = authorityEnd >= 0 ? base.substring(authorityStart, authorityEnd) : base.substring(authorityStart);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return base;
        }
        return base.substring(0, authorityStart) + REDACTED + authority.substring(at)
                + (authorityEnd >= 0 ? base.substring(authorityEnd) : "");
    }

    /** Blanks the value after each secret key in a key/value path ({@code /ip/1.2.3.4/}). */
    private static String sanitizePath(String base) {
        String[] segments = base.split("/", -1);
        boolean changed = false;
        // [0] "https:", [1] "", [2] host: path segments start at 3.
        for (int i = 3; i + 1 < segments.length; i++) {
            if (SECRET_PATH_KEYS.contains(segments[i].toLowerCase(Locale.ROOT)) && !segments[i + 1].isEmpty()) {
                segments[i + 1] = REDACTED;
                changed = true;
                i++;
            }
        }
        if (!changed) {
            return base;
        }
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(segments[i]);
        }
        return out.toString();
    }

    private static String replaceSecretValues(String line) {
        Matcher matcher = SECRET_KEY_VALUE.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        StringBuffer out = new StringBuffer(line.length());
        do {
            String value = matcher.group(2);
            String replacement = value.length() < MIN_SECRET_VALUE_CHARS || value.equals(REDACTED)
                    || value.startsWith("<")
                    ? matcher.group() : matcher.group(1) + REDACTED;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceBlobs(String line) {
        Matcher matcher = BLOB.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        StringBuffer out = new StringBuffer(line.length());
        do {
            matcher.appendReplacement(out, "<blob:" + matcher.group().length() + ">");
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }
}
