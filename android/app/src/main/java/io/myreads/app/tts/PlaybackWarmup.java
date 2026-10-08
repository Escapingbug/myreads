package io.myreads.app.tts;

import java.io.*;
import java.nio.*;

/** A separate silent item opens the same PCM output before any novel speech. */
final class PlaybackWarmup {
    static int durationMs(float speed) {
        float bounded = Float.isNaN(speed) ? 1 : Math.max(0.5f, Math.min(2f, speed));
        return (int) Math.ceil(500 * bounded);
    }

    static File audio(File directory, float speed) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("播放缓存目录创建失败");
        int ms = durationMs(speed), bytes = 48000 * ms / 1000 * 2;
        File file = new File(directory, "output-start-" + ms + ".wav");
        if (file.isFile() && file.length() == bytes + 44) return file;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R','I','F','F'}).putInt(bytes + 36).put(new byte[]{'W','A','V','E'});
        header.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        header.putInt(48000).putInt(96000).putShort((short) 2).putShort((short) 16);
        header.put(new byte[]{'d','a','t','a'}).putInt(bytes);
        File partial = new File(directory, file.getName() + ".part");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
                out.write(header.array()); byte[] zero = new byte[4096];
                for (int left = bytes; left > 0; left -= Math.min(left, zero.length))
                    out.write(zero, 0, Math.min(left, zero.length));
            }
            if (!partial.renameTo(file)) throw new IOException("播放起始音频保存失败");
            return file;
        } finally { partial.delete(); }
    }
}
