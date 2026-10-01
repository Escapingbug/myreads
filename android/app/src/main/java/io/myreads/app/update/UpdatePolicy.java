package io.myreads.app.update;

import java.net.URI;
import java.util.Locale;

/** Shared validation for an update request, before any file or network access. */
public final class UpdatePolicy {
    private UpdatePolicy() {}
    public static void validate(String url, String version, long code, long installed, long size, String hash) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || code <= installed || code > Integer.MAX_VALUE)
            throw new IllegalArgumentException("更新版本无效或不高于当前版本");
        if (size < 1024 * 1024 || size > 300L * 1024 * 1024 || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("更新文件大小或校验值无效");
        URI uri = URI.create(url);
        String path = "/escapingbug/myreads/releases/download/v" + version + "/zijian-" + version + ".apk";
        if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !path.equals(uri.getRawPath().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("更新文件必须来自纸间的 GitHub Release");
    }
    public static boolean allowedRedirect(URI uri) {
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1 && host != null
            && (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                || host.equalsIgnoreCase("objects.githubusercontent.com"));
    }
}
