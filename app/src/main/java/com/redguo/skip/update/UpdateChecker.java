package com.redguo.skip.update;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网更新检查。完全在本地发起一次 HTTPS GET,解析返回文本里的版本号与下载链接。
 * <p>
 * 为什么不用 OkHttp / Retrofit:
 * - 不联网运行是产品要求,绝大多数用户永远用不到这个类;
 * - 多引一个 HTTP 库就是多一份攻击面,违背"最小可用"原则;
 * - JDK 自带的 HttpURLConnection 一次 GET 完全够用。
 *
 * 期望的远端文本格式(由你自己托管,任意 CDN / Gist 都行):
 * <pre>
 *   version=1.2.0
 *   url=https://your.cdn/redguo-skip-1.2.0.apk
 *   notes=修复 XX Bug
 * </pre>
 */
public final class UpdateChecker {

    /** 默认指向一个示例地址,用户应当替换成自己托管的版本文件。 */
    private static final String DEFAULT_VERSION_URL =
            "https://raw.githubusercontent.com/redguo-skip/updates/main/version.txt";

    private static final Pattern P_VERSION = Pattern.compile("(?im)^\\s*version\\s*=\\s*(\\S+)");
    private static final Pattern P_URL     = Pattern.compile("(?im)^\\s*url\\s*=\\s*(\\S+)");
    private static final Pattern P_NOTES   = Pattern.compile("(?im)^\\s*notes\\s*=\\s*(.+)");

    public static final class Result {
        public final boolean online;
        @Nullable public final String remoteVersion;
        @Nullable public final String downloadUrl;
        @Nullable public final String notes;
        @Nullable public final String error;

        private Result(boolean online, @Nullable String v, @Nullable String u,
                       @Nullable String n, @Nullable String e) {
            this.online = online;
            this.remoteVersion = v;
            this.downloadUrl = u;
            this.notes = n;
            this.error = e;
        }

        public static Result offline() { return new Result(false, null, null, null, null); }
        public static Result error(@NonNull String e) { return new Result(true, null, null, null, e); }
        public static Result ok(@Nullable String v, @Nullable String u, @Nullable String n) {
            return new Result(true, v, u, n, null);
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    /**
     * 异步发起一次检查。注意:即使网络异常,Result.online 仍可能为 true(只是 error 有值),
     * 只有"当前根本没网"时才返回 Result.offline()。
     */
    public static void checkAsync(Context ctx, Callback cb) {
        new Thread(() -> {
            if (!hasNetwork(ctx)) {
                cb.onResult(Result.offline());
                return;
            }
            cb.onResult(doHttpCheck());
        }, "update-check").start();
    }

    private static boolean hasNetwork(Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static Result doHttpCheck() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DEFAULT_VERSION_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RedGuoSkip/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return Result.error("HTTP " + code);
            }
            String body = readAll(conn.getInputStream());
            String v = match(P_VERSION, body);
            String u = match(P_URL, body);
            String n = match(P_NOTES, body);
            return Result.ok(v, u, n);
        } catch (IOException e) {
            return Result.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String match(Pattern p, String body) {
        if (body == null) return null;
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }
}
