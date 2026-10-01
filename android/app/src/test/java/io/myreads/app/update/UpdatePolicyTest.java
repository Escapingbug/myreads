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
}
