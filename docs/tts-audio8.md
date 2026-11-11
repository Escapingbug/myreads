# Audio8 正式听书引擎

0.3.15 将 Android 正文朗读与语音提示统一切换至 Audio8 Realistic Air 0.6B 的 LiteRT 移动端导出。Bert-VITS2、MNN 与 MOSS ONNX 均不再进入正式 APK 的推理路径。历史实现留作主机测试资料。

模型仓库：[litert-community/Audio8-TTS-Realistic-0.6b](https://huggingface.co/litert-community/Audio8-TTS-Realistic-0.6b)，固定提交 `dcf477032785f2dda4af08c89a0de8509e511c6f`。模型 Apache-2.0，Android 示例与 Python 主机循环按上游 MIT 许可保留声明。Android Kotlin 实现改编自该固定提交的示例，使用 LiteRT 2.2.0 CompiledModel。

下载 slow/fast INT8、自回归输出使用的 T128/T192 FP16 解码器及分词器，总计 1,161,764,800 bytes。另有 APK 内的参考音色编码及描述 2,679 bytes；完整目录 1,161,767,479 bytes。下载按固定大小及 SHA-256 校验，支持断点续传、Hugging Face 与国内镜像。编码器仅用于制作参考音色，正式 App 无需下载。CPU 首次加载还会生成约 365 MB 的 XNNPACK 权重缓存，建议留出至少 2 GB 可用空间。

参考音色使用此前试听中的合成中文语音：Qwen3-TTS 0.6B 移动端发布方的 `demo_speaker` 样例，经 Audio8 编码为 10×99 的 codes；不是人类录音或用户克隆。源样例 SHA-256 记录于 APK 内 `voice-meta.json`。正文和提示共用 `audio8-zh`，旧音色选项自动迁移。模型目录与语音缓存使用独立版本，旧模型不会被误认为可用的新模型；书架、阅读进度及倍速偏好保留。

每次输入最多 49 个模型 token。规划保留句内逗号；超长输入优先按标点切分，之后用真实分词器复核。保留原始 44.1 kHz 单声道音频，不拉伸时间、不插入额外静音、不裁掉模型气口。每块固定种子 42；Kotlin 与 Python 的随机数实现不同，因此主机样例与 Android 输出不承诺逐帧相同。生成达到 512 帧上限时不提交半句音频，由播放服务缩小文本重试。

CPU 自回归采用 4 线程及共享 XNNPACK 缓存；音频解码仅在实际 GPU 输出与 APK 内 CPU 参考输出检查通过后使用 GPU，否则退回 CPU，并按系统构建/模型/运行时记住失败结果。先创建 GPU 缓存目录，再构建图。合成由全局锁串行，提示可抢占正文；暂停、停止与抢占在生成帧、快模型步骤、解码窗口之间检查，失败输出会删除。已有队列、后台服务、章节准备与音频缓存继续使用。

发布验证使用前端测试、Android JVM 测试、主机真实权重中文/中英生成、APK 内生产类与双 ABI 原生库检查、16 KB ELF/ZIP 对齐、签名及公开下载校验。未运行模拟器。主机实际权重测试运行 Python Interpreter；它不能替代 Android CompiledModel 真机测试，也不能证明所有手机实时生成、持续听书功耗、发热或后台稳定性。此前独立测试包没有完成实体手机测量，用户选择直接正式发布。
