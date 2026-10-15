package io.myreads.app.update;

import java.util.List;

/** Bounded retries: try the other source before retrying the failed one. */
public final class UpdateSources {
    private UpdateSources() {}
    public interface Attempt { void run(String url) throws Exception; }
    public interface Check { void run() throws InterruptedException; }
    public static void download(List<String> urls, Attempt attempt, Check check) throws Exception {
        if (urls.isEmpty() || urls.size() > 2) throw new IllegalArgumentException("更新下载来源无效");
        for (int index = 0; index < urls.size() * 2; index++) {
            check.run();
            try { attempt.run(urls.get(index % urls.size())); return; }
            catch (Exception error) {
                check.run();
                if (index + 1 == urls.size() * 2) throw error;
            }
        }
    }
}
