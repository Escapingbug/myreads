# Bert-VITS2-MNN 离线听书

生产推理改为 Bert-VITS2 2.3 的 MNN CPU 实现，中文使用蒸馏 BERT，中英混读使用该模型的零 BERT 特征路径。模型为此前试听的 `22娘_MIX`（speaker 0），22,050 Hz；UI 名称为“轻柔女声”。旧六个 MOSS 音色设置自动回到这个声音，语速和阅读位置保留。

## 固定来源与用途限制

- 推理参考和权重：[Voine/Bert-VITS2-MNN](https://github.com/Voine/Bert-VITS2-MNN/tree/a45fd76668501aca3b383a3a69a36e84e953f2a4)，代码 Apache-2.0。上游说明 22k 基础音色模型仅用于学习、非商用；这不等于 Apache-2.0 代码授权覆盖了音色训练数据和模型的商业分发。当前集成用于用户已授权的替换和验证，不能标作已经取得商业发布许可。
- MNN：3.6.2，源码压缩包 SHA-256 `3470ee82104c3c2b570ef581030e29956bb38f972c5f6cef51805ba8f41a40e5`，从源码构建 ARM64 和 x86_64，不复用来源不明的预编译库。
- cppjieba：`340de007f96d2a476fa1b2b525c2b1e4fea4b4a7`，压缩包 SHA-256 `60813ebc0065fcc3119e8086e7bdf7ef78a3b619031ed4f87d783c977565d150`。
- limonp（cppjieba 子模块）：`5c82a3f17e4e0adc6a5decfe245054b0ed533d1a`，压缩包 SHA-256 `6d5450260f6fbe6c22af7be519627b9606d0d73ef67c07f95df7f27aa43b94cd`。
- 模型、BERT、词典、音素表的逐文件来源、大小和 SHA-256 见 `android/app/src/main/assets/tts/catalog.json`，总计 87,624,721 字节（约 83.6 MiB），不随 APK 打包。普通文件使用 raw.githubusercontent.com，Git LFS 实体使用 media.githubusercontent.com，避免把 LFS 指针下载成模型。
- 当前固定源是 GitHub；没有已验证的国内模型镜像，因此不显示原 HF 镜像选择器，也不声称下载可自动切换到国内镜像。断点续传、逐文件哈希检查和前台通知沿用原实现。

## 播放与迁移

按完整句子生成，普通逗号保留在同一个请求内；超长句按 75 字预算分段，实际音素/时长超限才缩小预算重试。每段保留模型全部 PCM，不补 MOSS 专用停顿，不修剪气口或拉伸声音。只应用最多 +20dB 的整句恒定增益，并限制峰值不削波。

原播放队列、后台媒体通知、语速、暂停、续听和显式“先准备本章”继续使用。JNI 在模型图之间执行取消/暂停/无障碍抢占检查；不完整音频不进入缓存。新的缓存和计划 revision、模型目录、下载状态偏好与 MOSS 隔离，防止使用旧声音或旧的下载就绪状态。旧模型文件保留，避免切换过程中删除已有资源；删除新模型的动作仅删除当前模型目录。

MOSS 的 ONNX 引擎和编解码器只保留为主机历史回归测试，正式 APK 不再打包 ONNX Runtime。无障碍提示也使用同一新引擎，没有隐藏的旧模型回退。

## 验证边界

初次试听使用 Mac 的 Python 中文前端，App 使用 Kotlin 中文/英文前端及 JNI；不能把此前样例的主机速度作为手机速度。新的主机实际模型测试覆盖正式 Kotlin/JNI 路径、中文与混读 WAV、取消后再次生成。Android 验证使用主机单元测试、编译和 APK 静态检查，不使用模拟器或 ADB。长时间真机播放、耗电和发热仍需实体设备验证。

可通过 `-PbertModelRoot=/absolute/model/root -PbertTestOutput=/absolute/output -PttsNativePath=/absolute/native/libraries` 启用真实模型测试；没有权重时，该项测试显式跳过。外部 CMake 依赖可通过 `-PttsDepsRoot` 指向已解压的、已核对哈希的 MNN/cppjieba 源码目录，以免多个 ABI 重复下载。
