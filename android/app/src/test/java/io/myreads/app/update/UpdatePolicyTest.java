package io.myreads.app.update;

import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    private static final String URL = "https://github.com/Escapingbug/myreads/releases/download/v0.3.0/zijian-0.3.0.apk";
    private static final String HASH = new String(new char[64]).replace('\0', 'a');
    @Test public void acceptsOnlyOfficialNewerRelease() { UpdatePolicy.validate(URL, "0.3.0", 5, 3, 60_000_000, HASH); }
    private void rejected(String url, String version, long code, long size, String hash) {
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(url, version, code, 3, size, hash));
    }
    @Test public void rejectsDowngradesAndInvalidMetadata() {
        rejected(URL, "0.3.0", 3, 60_000_000, HASH);
        rejected(URL, "0.3.0", 2, 60_000_000, HASH);
        rejected(URL, "0.3.0-beta", 5, 60_000_000, HASH);
        rejected(URL, "0.3.0", 5, 600_000_000, HASH);
        rejected(URL, "0.3.0", 5, 0, HASH);
        rejected(URL, "0.3.0", 5, 60_000_000, "invalid");
    }
    @Test public void rejectsDifferentRepoAndAmbiguousUrls() {
        for (String url : new String[] {URL.replace("myreads", "other"), URL.replace("github.com", "github.com.evil.example"),
                URL.replace("https:", "http:"), URL + "?download=true", URL + "#x", URL.replace("github.com", "user@github.com"),
                URL.replace("v0.3.0/", "v0.4.0/"), URL.replace("github.com", "github.com:443")})
            rejected(url, "0.3.0", 5, 60_000_000, HASH);
    }
    @Test public void permitsOnlyHttpsGitHubAssetRedirects() {
        assertTrue(UpdatePolicy.allowedRedirect(URI.create("https://release-assets.githubusercontent.com/file?signature=abc")));
        assertTrue(UpdatePolicy.allowedRedirect(URI.create(URL)));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("http://release-assets.githubusercontent.com/file")));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://example.com/file")));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://user@github.com/file")));
    }
    @Test public void acceptsOnlyTheConfiguredGiteeRepository() {
        String url = "https://gitee.com/test-owner/zijian/releases/download/v0.3.0/zijian-0.3.0.apk";
        UpdatePolicy.validate(url, "0.3.0", 5, 3, 60_000_000, HASH, "test-owner/zijian");
        for (String repo : new String[] {"", "other/zijian", "test-owner/other", "bad/../zijian"})
            assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(url, "0.3.0", 5, 3, 60_000_000, HASH, repo));
        for (String changed : new String[] {url + "?x=1", url.replace("gitee.com", "gitee.com.evil.test"), url.replace("v0.3.0/", "v0.4.0/"), url.replace("https:", "http:")})
            assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate(changed, "0.3.0", 5, 3, 60_000_000, HASH, "test-owner/zijian"));
    }
    @Test public void isolatesRedirectHostsForEachProvider() {
        assertTrue(UpdatePolicy.allowedRedirect(URI.create("https://foruda.gitee.com/attach/file?token=abc"), "gitee.com"));
        assertTrue(UpdatePolicy.allowedRedirect(URI.create("https://gitee.com/release"), "gitee.com"));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://foruda.gitee.com/file"), "github.com"));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://example.com/file"), "gitee.com"));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://foruda.gitee.com.evil.test/file"), "gitee.com"));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("http://foruda.gitee.com/file"), "gitee.com"));
        assertFalse(UpdatePolicy.allowedRedirect(URI.create("https://user@foruda.gitee.com/file"), "gitee.com"));
    }
    @Test public void requiresTheEntireArtifactIdentityToReuseBytes() {
        assertTrue(UpdatePolicy.sameArtifact("0.3.0", 5, 60_000_000, HASH, "0.3.0", 5, 60_000_000, HASH));
        assertFalse(UpdatePolicy.sameArtifact("0.3.0", 5, 60_000_000, HASH, "0.3.1", 5, 60_000_000, HASH));
        assertFalse(UpdatePolicy.sameArtifact("0.3.0", 5, 60_000_000, HASH, "0.3.0", 6, 60_000_000, HASH));
        assertFalse(UpdatePolicy.sameArtifact("0.3.0", 5, 60_000_000, HASH, "0.3.0", 5, 59_000_000, HASH));
        assertFalse(UpdatePolicy.sameArtifact("0.3.0", 5, 60_000_000, HASH, "0.3.0", 5, 60_000_000, "b"));
    }
}
