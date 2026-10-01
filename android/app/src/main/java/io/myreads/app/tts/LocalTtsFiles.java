package io.myreads.app.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class LocalTtsFiles {
    static String text(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192]; int count;
        while ((count = stream.read(bytes)) >= 0) output.write(bytes, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    static String text(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) { return text(stream); }
    }
    static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("无法替换本地文件");
        if (!temporary.renameTo(file)) throw new IOException("无法保存本地文件");
    }
}
