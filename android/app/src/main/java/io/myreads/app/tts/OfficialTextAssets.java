package io.myreads.app.tts;

import android.content.Context;
import java.io.*;
import org.json.*;

/** Install the bundled, fingerprinted WeText graphs without another model download. */
final class OfficialTextAssets {
    static File install(Context context) throws Exception {
        JSONObject manifest;
        try (InputStream in = context.getAssets().open("tts/normalizer/manifest.json")) {
            manifest = new JSONObject(LocalTtsFiles.text(in));
        }
        File directory = new File(context.getFilesDir(), "tts-normalizer/" + manifest.getString("version"));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备文本规则");
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject spec = files.getJSONObject(i); String name = spec.getString("name");
            if (!name.matches("(?:zh|en)_tn_(?:tagger|verbalizer)\\.fst")) throw new IOException("文本规则清单无效");
            File file = new File(directory, name); String hash = spec.getString("sha256"); long size = spec.getLong("size");
            if (file.isFile() && file.length() == size && ModelRepository.hash(file).equals(hash)) continue;
            File partial = new File(directory, name + ".part");
            try {
                try (InputStream in = context.getAssets().open("tts/normalizer/" + name); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (partial.length() != size || !ModelRepository.hash(partial).equals(hash)) throw new IOException("文本规则校验失败");
                if (file.exists() && !file.delete() || !partial.renameTo(file)) throw new IOException("无法保存文本规则");
            } finally { partial.delete(); }
        }
        return directory;
    }
}
