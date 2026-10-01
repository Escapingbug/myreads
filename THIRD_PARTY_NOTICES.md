# 第三方组件

## MOSS-TTS-Nano

纸间的 Android ONNX 推理代码改编自 OpenMOSS 官方示例，参考版本 `8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb`。改动包括 SentencePiece JNI 接入、取消/资源清理、音频缓存和播放器集成。

项目来源：<https://github.com/OpenMOSS/MOSS-TTS-Nano>

许可证：Apache License 2.0，全文见 [licenses/MOSS-TTS-Nano-APACHE-2.0.txt](licenses/MOSS-TTS-Nano-APACHE-2.0.txt)。模型权重不随应用分发，按 App 中的固定版本下载。

## SentencePiece

Google SentencePiece 0.2.1 以源代码形式用于原生分词。原始许可证、作者信息及源代码中的版权声明保留在 `android/app/src/main/cpp/vendor/sentencepiece/`。JNI 接口与应用构建配置由纸间补充。

## 运行时依赖

ONNX Runtime（MIT）、AndroidX Media3（Apache-2.0）、Vue / Vite / Capacitor（MIT）。其他依赖及其版本记录在 `package-lock.json` 和 Android Gradle 配置中，各自版权与许可证仍属于原作者。
