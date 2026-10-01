package io.myreads.app.tts;

import java.io.*;
import java.net.HttpURLConnection;

public final class ResumableTransfer {
    public interface Check { void run() throws InterruptedException; }
    public interface Progress { void update(); }
    public static void write(HttpURLConnection connection, File partial, long expectedSize, Check check, Progress progress) throws Exception {
        long offset = partial.length();
        int code = connection.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("下载服务返回 HTTP " + code + "，可更换下载来源后重试");
        if (code == 206 && !String.valueOf(connection.getHeaderField("Content-Range")).startsWith("bytes " + offset + "-"))
            throw new IOException("下载服务返回了错误的文件分段");
        if (code == 200) offset = 0;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
            byte[] buffer = new byte[256 * 1024]; int count;
            long received = offset;
            while ((count = input.read(buffer)) != -1) {
                check.run(); received += count;
                if (received > expectedSize) throw new IOException("文件大小与清单不符");
                output.write(buffer, 0, count); progress.update();
            }
            output.getFD().sync();
        }
        if (partial.length() != expectedSize) throw new IOException("连接提前结束，可继续下载");
    }
}
