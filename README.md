# 纸间 · myreads

Android 小说阅读器，支持动态书源、搜索、章节下载、离线阅读和本地听书。

[下载最新 APK](https://github.com/escapingbug/myreads/releases/latest) · [版本说明](docs/releases/0.3.8.md)

## 使用

安装 Release 中的 `zijian-*.apk`，支持 Android 7.0 及以上、arm64-v8a / x86_64。

- 在「搜索」选择书源，搜索书名并下载章节；「书架」打开已保存小说离线阅读。
- 在「书源」导入 ZIP / JSON / HTTPS 书源包。书源包含脚本，请选择可信来源。
- 「听书」页点击下载 MOSS-TTS-Nano ONNX 模型（约 686 MiB），下载后可离线听书。模型不包含在 APK 中。阅读器的耳机按钮可从当前段落开始，支持六种声音、倍速及后台播放。
- 「设置 → 语音无障碍模式」由协助者首次配置。模型必须下载、校验并实际加载成功后才能开启，下次启动会重新加载。不开启 TalkBack，移动手指听按钮说明，抬手选中，任意位置双击执行；底部固定返回和帮助。首页提供继续上次、最近读听、书架和找新书，书架及搜索结果一次选择一本，可听简介、选章节、换段和调速。
- 无障碍操作提示与正文共用本地模型，重复提示使用缓存，首次生成可能需要等待。语音搜索使用手机的语音识别服务，请由协助者先配置麦克风权限，识别服务可能需要联网；支持识别内容确认、换书源和最近搜索。未配置识别服务时仍可浏览书架、历史及未听过的书。最近读听独立保存，删除本地书籍后仍保留，重新下载优先准备上次读听的章节。
- 按完整对白和句段规划朗读，由模型处理内部标点节奏。默认连贯朗读参考前面完整语组的文字与语音，在有限窗口内接续；可在「跨句衔接」切换独立朗读，停止后重新开始生效。默认边生成边播放，首个语组完成即可开始，后台继续生成；生成较慢时也不再自动等待整章。可手动选择先准备本章；暂停同时挂起生成，缓存可直接回放。起播与队列耗尽后重新起播有约半秒静音保护。本地合成仍比播放普通音频更耗电。
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
