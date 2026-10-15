package io.myreads.app.update;

import java.net.URI;
import java.util.Locale;

/** Initial URLs must belong to our configured repositories; only CDN redirects may leave them. */
public final class UpdatePolicy {
    private UpdatePolicy() {}
    public static void validate(String url, String version, long code, long installed, long size, String hash) {
        validate(url, version, code, installed, size, hash, "");
    }
    public static void validate(String url, String version, long code, long installed, long size, String hash, String giteeRepository) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || code <= installed || code > Integer.MAX_VALUE)
            throw new IllegalArgumentException("更新版本无效或不高于当前版本");
        if (size < 1024 * 1024 || size > 300L * 1024 * 1024 || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("更新文件大小或校验值无效");
        URI uri = URI.create(url);
        String repository = "github.com".equalsIgnoreCase(uri.getHost()) ? "escapingbug/myreads"
            : "gitee.com".equalsIgnoreCase(uri.getHost()) ? giteeRepository : "";
        String path = "/" + repository + "/releases/download/v" + version + "/zijian-" + version + ".apk";
        if (!repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+") || !"https".equals(uri.getScheme()) || uri.getPort() != -1
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !path.toLowerCase(Locale.ROOT).equals(uri.getRawPath().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("更新文件必须来自纸间配置的正式发布仓库");
    }
    public static boolean allowedRedirect(URI uri) { return allowedRedirect(uri, "github.com"); }
    public static boolean allowedRedirect(URI uri, String sourceHost) {
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null || host == null) return false;
        if ("github.com".equalsIgnoreCase(sourceHost)) return host.equalsIgnoreCase("github.com")
            || host.equalsIgnoreCase("release-assets.githubusercontent.com") || host.equalsIgnoreCase("objects.githubusercontent.com");
        return "gitee.com".equalsIgnoreCase(sourceHost) && (host.equalsIgnoreCase("gitee.com") || host.equalsIgnoreCase("foruda.gitee.com"));
    }
    public static String source(String url) { return "gitee.com".equalsIgnoreCase(URI.create(url).getHost()) ? "gitee" : "github"; }
    public static boolean sameArtifact(String versionA, long codeA, long sizeA, String hashA, String versionB, long codeB, long sizeB, String hashB) {
        return versionA.equals(versionB) && codeA == codeB && sizeA == sizeB && hashA.equals(hashB);
    }
}
