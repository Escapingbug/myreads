# 纸间 · myreads

Android 小说阅读器，支持动态书源、搜索、章节下载、离线阅读和本地听书。

[国内下载 APK](https://gitee.com/anciety/myreads/releases/latest) · [GitHub 备用下载](https://github.com/escapingbug/myreads/releases/latest) · [版本说明](docs/releases/0.3.14.md)

## 使用

安装 Release 中的 `zijian-*.apk`，支持 Android 7.0 及以上、arm64-v8a / x86_64。

- 在「搜索」选择书源，搜索书名并下载章节；「书架」打开已保存小说离线阅读。
- 在「书源」导入 ZIP / JSON / HTTPS 书源包。书源包含脚本，请选择可信来源。
- 「听书」页点击下载 MOSS-TTS-Nano ONNX 模型（约 686 MiB），下载后可离线听书。大型听书模型单独下载，小型 VAD 文件随 APK 提供。阅读器的耳机按钮可从当前段落开始，支持六种声音、倍速及后台播放。
- 「设置 → 语音无障碍模式」由协助者首次配置，先下载并加载本地语音模型。进入后自动接着上次的书听，没有记录时介绍推荐书。喜欢点“就听这本”，不喜欢点“换一本”，应用自动准备章节后开播。播放页提供暂停/继续、换一本和更多功能，不需要先学会搜索或操作书架。右上角始终可退出，底部有返回和帮助。
- 书架、搜索、读听历史、章节和语速放在“更多功能”。搜书支持输入框与可修改的语音输入，结果显示书名/作者与翻页。语音输入需要手机的语音识别服务和麦克风权限，不是使用推荐的必要条件。提示与正文共用本地模型，首次生成可能等待，重复提示使用缓存。
- 听书采用固定版本的官方 ONNX 生成流程，由模型处理内部标点节奏，每块使用固定音色参考。普通与无障碍听书使用同一流程。默认边生成边播放，首块完成即可开始，后台继续生成；也可手动选择先准备本章。暂停同时挂起生成，缓存可直接回放。重新开始时从上次朗读块开头继续。起播与队列耗尽后重新起播有约半秒静音保护。本地合成仍比播放普通音频更耗电。
- 右上角「设置 → 应用更新」手动检查更新。默认每天在打开应用时自动检查 Gitee 和 GitHub 正式版本；同版本优先 Gitee，下载失败自动切换同一安装包的备用来源，也可手动固定来源。下载 APK 后按 Android 提示允许安装并确认。更新保留书架、阅读进度和模型。

0.2.x 的用户首次需要手动覆盖安装 0.3.0；后续版本可从 App 内更新。无需卸载。

## 0.3.14 听书兼容性修复

修复0.3.13开始听书时出现“UNICODE_CHARACTER_CLASS flag not supported”并停止的问题。保留官方生成流程和已有模型，无需清理书架或重新下载模型。

GitHub 与 Gitee 均已正式发布。可[直接下载 Gitee 0.3.14 APK](https://gitee.com/anciety/myreads/releases/download/v0.3.14/zijian-0.3.14.apk)，或在应用更新中选择“自动”／“Gitee”检查更新。

## 0.3.13 官方听书流程

针对句内语速不一致、异常停顿和重复朗读，将正文生成迁移到实际试听效果更好的完整官方 ONNX 流程。更新会重新生成语音缓存，已下载模型、书架和阅读进度保留；不再提供“跨句衔接”开关。具体变化见[版本说明](docs/releases/0.3.14.md)。

## 0.3.12 推荐与简单听书

无障碍模式进入后自动继续上次听书；没有记录时介绍推荐书。喜欢点“就听这本”，不喜欢点“换一本”，自动准备章节后播放。搜索、书架、章节和语速收进“更多功能”。普通书架也增加推荐入口，书单与简介可缓存。使用方式和验证边界见[推荐与简单听书](docs/recommendations.md)。

## 开发

Node.js 24、JDK 21、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1。

0.3.13 使用官方文本规则、75 token 分块、固定音色独立生成和整章连续采样，移除了“跨句衔接”设置，并保存恢复播放所需的分块与采样状态。迁移依据及验证记录见 [HANDOFF.md](HANDOFF.md)。

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

国内渠道为 [anciety/myreads](https://gitee.com/anciety/myreads)，同一签名 APK 自动同步到 Gitee；配置和同步流程见 [国内下载与双渠道更新](docs/distribution.md)。0.3.10 及更旧版本首次升级到 0.3.11 时，可使用原 GitHub 更新入口或从 Gitee 手动覆盖安装。

签名材料存于 GitHub Actions Secrets：`ANDROID_SIGNING_KEY`（keystore 的 Base64）、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。沿用现有安装包的签名，以保留覆盖安装能力；不要替换或提交 keystore。签名密钥应另行妥善备份，换机器后的普通 debug 构建可能使用不同签名。

本地发布构建使用上述密码/别名环境变量及 `ANDROID_KEYSTORE_PATH`，执行 `npm run android:release` 和 `npm run release:prepare -- vX.Y.Z`。`release/` 和签名文件被 Git 忽略。

## 开源组件

听书方案：[OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano)（Apache-2.0）；模型按固定版本下载并验证 SHA-256。原生分词使用 SentencePiece 0.2.1，其许可证和作者信息保留在 `android/app/src/main/cpp/vendor/sentencepiece/`。0.3.13 使用 WeTextProcessing 1.2.0 文本规则与固定源码的 OpenFst 核心（均 Apache-2.0），相关许可证随 APK 提供。音频推理由 ONNX Runtime 1.23.2 提供，播放使用 AndroidX Media3。0.3.12 使用的 [Silero VAD](https://github.com/snakers4/silero-vad)（MIT）及许可证仍保留；0.3.13 保留官方生成的完整 PCM，不再执行 VAD 裁剪。

其他主要依赖：Vue、Capacitor、SQLite、Vite。详细实现与已完成验证见 [HANDOFF.md](HANDOFF.md)。本轮通过主机真实模型对照、单元测试和 APK 静态检查验证；听书性能、后台连续播放与端到端听感仍待实体手机验证。
