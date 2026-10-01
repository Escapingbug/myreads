# 纸间 · myreads

Android 小说阅读器，支持动态书源、搜索、章节下载、离线阅读和本地听书。

[下载最新 APK](https://github.com/escapingbug/myreads/releases/latest) · [版本说明](docs/releases/0.3.3.md)

## 使用

安装 Release 中的 `zijian-*.apk`，支持 Android 7.0 及以上、arm64-v8a / x86_64。

- 在「搜索」选择书源，搜索书名并下载章节；「书架」打开已保存小说离线阅读。
- 在「书源」导入 ZIP / JSON / HTTPS 书源包。书源包含脚本，请选择可信来源。
- 「听书」页点击下载 MOSS-TTS-Nano ONNX 模型（约 686 MiB），下载后可离线听书。模型不包含在 APK 中。阅读器的耳机按钮可从当前段落开始，支持六种声音、倍速及后台播放。
- 按完整对白和句段规划朗读，将过短句子合成有界语组，由模型处理内部标点节奏；所有语组使用所选声音的同一份官方参考，校准语组与段落之间的停顿。自动模式在生成较慢时先准备本章，也可选择边生成边播放；暂停同时挂起生成，缓存可直接回放。本地合成仍比播放普通音频更耗电。
- 右上角「设置 → 应用更新」手动检查更新。默认每天在打开应用时自动检查 GitHub 正式 Release；下载 APK 后按 Android 提示允许安装并确认。更新保留书架、阅读进度和模型。

0.2.x 的用户首次需要手动覆盖安装 0.3.0；后续版本可从 App 内更新。无需卸载。

## 开发

Node.js 24、JDK 21、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1。

```sh
npm ci
npm run dev
npm test
npm run android:build
```

调试 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。Mac 构建脚本会使用可用的 Homebrew JDK 21 / Android SDK；其他环境请设置 `JAVA_HOME` / `ANDROID_HOME`。原生测试：在 `android/` 执行 `./gradlew testDebugUnitTest`。

## 发布

仓库已配置 GitHub Actions：`main` / PR 执行前端与原生测试及 APK 构建；推送 `vX.Y.Z` 标签后自动构建签名 APK，生成更新清单并发布到 Release。

1. 修改 `package.json` 中的 `version` 和递增的 `androidVersionCode`，执行 `npm install --package-lock-only`。Android 版本信息从此文件读取。
2. 新建 `docs/releases/X.Y.Z.md`，写入用户可见的版本说明。
3. 提交并推送代码，执行 `git tag vX.Y.Z` 和 `git push origin vX.Y.Z`。

发布资产为 `zijian-X.Y.Z.apk`、`update.json`、`SHA256SUMS`。清单包含包名、版本、大小和 SHA-256；App 仅接受此仓库的正式版本，并在安装前验证 APK 签名与当前应用一致。

签名材料存于 GitHub Actions Secrets：`ANDROID_SIGNING_KEY`（keystore 的 Base64）、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。沿用现有安装包的签名，以保留覆盖安装能力；不要替换或提交 keystore。签名密钥应另行妥善备份，换机器后的普通 debug 构建可能使用不同签名。

本地发布构建使用上述密码/别名环境变量及 `ANDROID_KEYSTORE_PATH`，执行 `npm run android:release` 和 `npm run release:prepare -- vX.Y.Z`。`release/` 和签名文件被 Git 忽略。

## 开源组件

听书方案：[OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano)（Apache-2.0），参考官方 Android ONNX 示例；模型按固定版本下载并验证 SHA-256。原生分词使用 SentencePiece 0.2.1，其许可证和作者信息保留在 `android/app/src/main/cpp/vendor/sentencepiece/`。音频推理由 ONNX Runtime 1.23.2 提供，播放使用 AndroidX Media3。首尾语音活动检测使用 [Silero VAD](https://github.com/snakers4/silero-vad)（MIT，许可证保留于 `android/app/src/main/assets/tts/SILERO-LICENSE.txt`）；检测结果只用于建议边界，裁剪还必须通过接近零幅值的检查，保留轻声起音、句内停顿和保护余量。

其他主要依赖：Vue、Capacitor、SQLite、Vite。详细实现与已完成验证见 [HANDOFF.md](HANDOFF.md)。听书性能和音色受设备及模型影响，目前已在 Android 模拟器验证，尚未完成实体手机性能测试。
