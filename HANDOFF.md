# 纸间 Android 小说阅读器：项目交接

更新时间：2026-10-08

## 用户目标与范围

第一批：Android 小说阅读器，动态书源、搜索、整书下载、本地离线阅读。主要路径为「书架 → 搜索书源 → 书籍详情 → 下载章节到本机 → 本地阅读」。阅读器只读取已保存的章节，不通过实时网页加载正文。已下载内容不依赖书源继续安装。

第二批：接入 MOSS-TTS-Nano 中文本地听书。用户明确要求模型不带在 App 中，必须在 App 内点击下载；只打包 ONNX Runtime、SentencePiece 和下载元数据。仍不支持 iOS。

第三批：用户指定 `github.com/escapingbug/myreads` 作为远程仓库与发布渠道，接入 GitHub Release 自动检查、应用内 APK 下载和覆盖安装，以及标签触发的自动发布。

## 当前完成状态

阅读、听书、更新和语音无障碍模式已经实现，0.3.7 已由 GitHub Actions 签名公开发布，有界跨句续接及解码优化已完成本机验证。默认听书在首个语组完成后开始播放，旧自动设置迁移为边生成边播放。无障碍模式由他人协助首次配置，日常不依赖 TalkBack，进入前必须实际加载现有 MOSS 模型。0.3.6 默认使用完整语组文字与语音 token 的有界续接，可在听书页切换独立朗读；句首保护和确定结束判定保留。尚未在实体手机量化长篇听感、无障碍操作延迟或耗电。详见下文。

- `src/App.vue`、`src/style.css`：移动端书架、继续阅读、搜索及分页、详情和完整目录预览、下载管理、ZIP/JSON/HTTPS 书源导入确认、启停与卸载、错误/空态、删除确认、原生返回键和应用生命周期。
- `src/components/Reader.vue`：本地单章上下滚动、上下章、目录跳章、字号、行距、纸色/明亮/夜读、自动保存章节/段落/段内位置。未下载章节禁用。
- `src/services/library.ts`：完整目录分页收集、去重及重复游标检测、串行下载队列、失败重试、暂停继续、后台暂停、重启后保留暂停状态、下载时保存书源快照。
- `src/services/storage.ts`：Android SQLite 元数据与 `Directory.Data` 持久章节文件；先写临时文件并重命名，再提交下载记录。浏览器使用 IndexedDB。元数据写入已串行化，初始化可重试。
- `src/services/runtime.ts`、`source.worker.ts`：可终止的 Worker 运行书源，启动/执行超时、返回格式校验、网络串行与 350ms 节流、取消请求。
- `src/services/html.ts`：将 HTML 片段包在 body 中，修正 linkedom 对目录展开片段的不同处理，避免丢失中间章节。
- `src/services/http.ts`、`dev-proxy.ts`：Android 原生 HTTP；开发期本机 HTTPS 代理，校验域名、DNS 公网 IP、固定已校验地址、请求/响应大小及超时。已修复 Node 26 的 DNS `lookup` 全量返回格式。
- `src/services/packages.ts`、`import.ts`：书源包格式、域名与大小验证；ZIP/JSON 解码，HTTPS 下载包，安装前显示名称、版本和声明域名。
- `android/`：Capacitor Android 工程、纸间书本图标及启动主题、版本 0.3.7 / versionCode 12，原生插件已同步。版本从 `package.json` 读取。
- `scripts/build-android.mjs`：当前 Mac 使用已安装的 JDK 21 / SDK；若 shell 配置的是旧 JDK，会切换到 Homebrew JDK 21。

发布 APK：`release/zijian-0.3.7.apk`，同时生成 `update.json` 与 `SHA256SUMS`。公开下载入口：<https://github.com/escapingbug/myreads/releases/latest>。旧版调试 APK 与本机截图/音频记录在 `artifacts/`，均不提交 Git。

## 已完成的验证

- `npm test`：8 个测试文件、44 项测试通过；Android 原生单元测试 59 项通过。覆盖解码状态复用、起播静音格式、播放准备方式迁移、无障碍模型加载、提示暂停与恢复、触摸确认、搜索取消和历史保留，以及已有更新、听书、书源、下载和持久化回归。
- `npm run build`：Vue / TypeScript 检查和 Vite 生产构建通过。
- `npm run android:build`：JDK 21 / API 36 构建成功。
- 浏览器 390 × 844：演示书源搜索、详情六章目录、整书下载、打开本地正文、切章、滚动；重载后仍有书籍与记录，第二章约 48% 位置恢复正确；ZIP 书源导入确认与更新成功。
- 浏览器真实搜索「斗破苍穹」返回 15 条结果。开发代理一度遇到 HTTP 302，重试成功，仍不自动跟随重定向。
- Android `emulator-5554`（API 35）：普通 App 启动与 SQLite 初始化成功。专用验证构建使用同一份数据层和 Reader，完成 Worker 搜索、下载暂停/继续、六章每章 24 段内容写入并读取、卸载书源后读取本地章节。
- Android 真实网络：全本书源搜到《斗破苍穹》，取得 1,621 章目录、第一章 57 段正文，成功下载前三章验证样本。**没有验证整本 1,621 章全部下载完成。** 三章样本随后通过正常删除逻辑清理，避免占用同一书籍 ID。
- Android 离线重启：临时关闭模拟器 Wi-Fi 与移动数据，结束进程再启动；SQLite 恢复完整六章及第二章第 7 段位置，Reader 渲染 24 段本地正文。测试结束后恢复网络。
- `tests/native-verify.ts` 为独立验证入口，只有 `vite build --mode native-verify` 才启用；最终普通构建不包含此入口。

## 听书实现（0.2.0）

- 使用方法：「听书」页点击下载模型，再打开已下载小说，在阅读器右上角耳机按钮选择「从这里开始听书」。六种官方预设声音、0.5–2 倍速、暂停/继续/停止、段落高亮、章节连续播放、书架小播放器与听读进度同步。
- 模型共 11 个文件、684.2 MiB，排除不用于合成的音频编码器。目录为 `getNoBackupFilesDir()/tts/moss-nano-onnx-v1/`。官网下载与用户选择的 HF 镜像，固定版本 URL、逐文件 SHA-256 校验、断点续传、暂停重试、空间检查、下载通知、手动删除。
- `android/app/src/main/assets/tts/catalog.json` 只含模型文件 URL、大小和校验值；APK 不含 `.onnx`、`.data` 或 `.model`。模型下载后可离线合成已保存章节。
- `android/app/src/main/java/io/myreads/app/tts/`：下载插件/服务、模型仓库、Media3 播放服务、文字规范化/约 48 字按完整句子切分（最多额外保留一个组合语气标点）、ONNX 推理。推理移植自 OpenMOSS 官方 Android 示例（提交 `8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb`），使用 ONNX Runtime 1.23.2。音频按段生成，最多预缓冲三段，WAV 缓存上限 128 MiB。仅加载模型/生成音频时持有有超时的 CPU 唤醒锁，结束或取消立即释放；初始化失败及取消时清理原生资源/临时音频。
- `android/app/src/main/cpp/`：官方 SentencePiece v0.2.1 的 JNI 文本分词，支持实际任意章节文字及 Unicode；无需预先生成文本 token。构建 arm64-v8a 和 x86_64。
- `src/services/tts.ts`、`ListeningSettings.vue`、`Reader.vue`、`App.vue` 为用户交互与原生状态桥接；初始化只查询状态，绝不自动下载。
- 原生单元测试 15 项通过，覆盖数字/日期规范化、Unicode 切分、HTTP Range 恢复、服务端忽略 Range、截断和取消保留进度、错误大小阻止提交。
- 模拟器已从正常 App 点击下载，完整下载并校验通过全部 684.2 MiB 文件。已验证任意本地中文分词、48 kHz 单声道 WAV 合成（样本 3.12 秒、有非零音频信号）、自动推进段落、阅读器高亮、App 暂停/继续、Wi-Fi/移动数据同时关闭后的后台继续合成与播放、系统媒体键暂停/继续。另外验证了俊豪/小雨两种声音、熄屏合成时的 CPU 唤醒锁。实测示例：3.92 秒音频合成耗时 9.93 秒，7.36 秒音频耗时 18.46 秒。测试未进行主观试听。模拟器中合成速度不稳定，部分段落之间需等待，不能保证所有设备实时连续播放。
- 模型体积不代表运行内存。首次模型加载与合成可能需要等待；没有完成实体手机性能、电量和声音主观质量评估。调试包支持的 CPU ABI 为 arm64-v8a / x86_64。

官方参考：<https://github.com/OpenMOSS/MOSS-TTS-Nano>、<https://github.com/OpenMOSS/MOSS-TTS-Nano/tree/main/examples/android_onnx_runtime>、<https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX>。

## 标点朗读修复（0.2.1）

用户试听官方脚本与 App 样本后确认声音质感接近，主要反馈是标点读法奇怪。旧实现会把 `……` 经 NFKC 转成六个英文点，按每个句末切分后也可能将右引号放到下一段音频开头。

- 只清理 TTS 输入，小说原文保持原样。去掉引号/书名号/括号等排版符号，保留其内容和英文缩写撇号；省略号和破折号转换为停顿标点，合并重复标点，恢复中文问号/感叹号，避免标点独立生成音频。
- 将相邻短句合并到约 48 字的语音片段，在完整句子或分句处切分；组合 `？！` 在边界处最多额外保留一个标点，不能拆到下一段。时间中的冒号先转为「点/分/秒」，保留小数、日期、金额和电话号码规范化。
- 音频缓存键增加 `punctuation-v2` 处理版本；无需删除模型或重新下载，已生成旧音频不会被继续使用。
- 新增 6 项回归测试，覆盖对话、重复/异体标点、仅符号输入、英文缩写/域名、长段落无丢字和组合标点边界；原生 15 项、前端 13 项通过。
- `artifacts/punctuation-before.wav` / `punctuation-after.wav` 是相同 Nano 模型、俊豪、同一输入、官方 ONNX CPU runtime 生成的旧/新文字规则对照，合成使用完整音频解码。原文及分段、参数在 `punctuation-comparison.json`。0.2.1 已覆盖安装到 API35 模拟器，沿用已下载模型，完成小雨声音、第二章第 17 段引号对话的规范化合成与播放，无原生异常。没有做主观效果保证，仍需要用户试听确认其实际书籍问题。
- 官方原始示例 `moss-official-junhao.wav`（12.16 秒，立体声）、同句短样本 `moss-official-junhao-short.wav`、生成记录 `moss-official-sample.json` 也保存在 `artifacts/`。

## GitHub 更新与发布（0.3.0）

- 远程：`git@github.com:escapingbug/myreads.git`，主分支 `main`。用户已明确授权代码推送与 Release 发布。
- `src/services/updates.ts`、`AppUpdate.vue`：App 启动/返回前台时检查 GitHub 最新正式 Release，每天最多一次；失败自动检查间隔至少一小时，手动检查不受限。默认只检查，点击下载后才获取 APK。缓存发布信息供离线展示；更新提示通往右上角「设置」，自动检查可关闭。
- `android/.../update/`：原生前台下载服务、进度通知、暂停/重试/断点续传、存储空间检查；下载前验证仓库 URL，重定向仅允许 GitHub 资产域名。安装前重新检查大小、SHA-256、包名、版本与现有签名一致，使用 FileProvider 打开系统安装页面。首次需在 Android 设置允许纸间安装应用，再返回点击安装。已安装更新后自动清理旧 APK。
- `package.json` 是版本名称/Android versionCode 的唯一来源。`scripts/prepare-release.mjs` 生成 APK、清单和校验文件；发布清单必须与 GitHub 资产一致。0.3.0 继续沿用先前已安装调试 APK 的证书，发布构建本身不可调试；现有用户无需卸载，保留书架和模型。密钥和密码仅在本机及 GitHub Actions Secrets 中，严禁提交。
- `.github/workflows/android.yml`：main / PR 构建并测试，推送 `vX.Y.Z` 标签则构建签名 release APK，在资产全部上传后公开 Release，避免 App 读到不完整发布。版本标签必须匹配 package.json；发布说明放在 `docs/releases/X.Y.Z.md`。
- 原生单元测试 19 项通过，新增更新版本、仓库地址/重定向和大小/哈希元数据边界测试。
- 0.2.x 尚无更新功能，首次需手动覆盖安装 0.3.0，后续使用 App 内更新。
- 实际发布：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.0>；标签流水线 <https://github.com/Escapingbug/myreads/actions/runs/36799771941> 全部成功。线上 APK 大小 58,801,808 字节（56.1 MiB），SHA-256 `c858e4bb33dfb14227cab4ea3e4a361d6f0677490bd1709fcf0c5f7b0ad5e1ac`。本机 `release/` 已同步这份 GitHub 构建产物，而非本机构建的另一份 ZIP。
- 模拟器 API35：用含更新功能的临时 0.2.2 / code4 构建加载原有数据，从公开 Release API 获取 0.3.0 / code5；App 原生下载约 37 MiB 后点击暂停，保留部分文件，继续并退到后台完成。安装包与线上 SHA-256 一致，原生包名/版本/签名验证通过，正常进入 Android 安装来源权限页和软件包安装程序，点击系统「更新」后已安装 0.3.0（无 DEBUGGABLE 标志）。重新打开书架保留《山间来信》及第二章阅读记录，听书模型仍显示已下载可离线使用，未重新下载模型。自动检查关闭后重启仍保持关闭，测试后已恢复开启。
- 升级后再次手动检查显示「已经是最新版本」，不再提示下载同一版本。相关本机截图与发布清单保存在 `artifacts/update-*.png`、`artifacts/github-release/`、`artifacts/published-release.json`、`artifacts/update-verification.json`，不上传 Git。

## 听书断句与耗电调整（0.3.1）

用户确认标点读法与机械感已改善，继续反馈断句、重音偶尔奇怪及耗电较快。

- 原切分以 48 个 Unicode 码点为硬上限，可能拆开一句话或英语单词。现在以 48 字为软目标，优先在完整句子/分句处结束，允许延伸到 72 字，组合 `？！` 最多再保留一个标点；无标点的极长文本仍需要有界切分。使用 `BreakIterator` 避免通常的英语单词被切开。达到 375 帧上限的音频不播放，按更小片段自动重试。
- 模型、预设声音、官方采样图与随机种子保持原样。重音由模型预测，完整语义上下文不等于保证重音正确；尚未收到用户具体原文例句，不能声称已解决所有模型韵律问题。
- `SynthesisGate` 为每次听书独立管理暂停/取消。App 和系统媒体暂停都会停止新片段预生成，并在当前推理步骤结束后挂起正在生成的片段，释放合成唤醒锁；继续时保留生成中的 token/KV 状态，停止时取消并释放模型。系统媒体 STOP 也转到服务停止逻辑，避免仅停止播放器后继续合成。
- ONNX 四个会话关闭 intra/inter-op 线程空转，保留两个计算线程。暂停/缓冲等待使用通知唤醒，不再每 500ms 轮询；播放心跳只在实际播放时调度，进度写盘间隔由每秒改为五秒，状态/段落变化立即保存。
- 缓存命中时直接播放，首次遇到缺失音频才初始化模型。沿用 `punctuation-v2` 缓存版本，因为采样与规范化未改变；新切分后的不同文本自然产生新缓存键，无需让未改变的片段全部重做，也无需重新下载模型。
- 新增 7 项原生测试：完整长句/分句、英语边界、无丢字的缩短重试、暂停阻塞/继续、取消唤醒且不继续旧任务、线程中断；共 26 项原生、23 项前端通过，普通 APK 构建通过。
- 模拟器实测：确认旧版系统媒体 PAUSED 状态后仍在合成，10 秒 CPU 样本约 182–210%（100% 为一个核心）；新实现暂停后 0–2%，无新音频生成，合成唤醒锁释放，继续后恢复同一段生成并进入 PLAYING。测试暂停在一次较长 prefill 中，约 2.9 秒后挂起。系统媒体 STOP 已验证取消推理并释放模型与唤醒锁。这里只能说明后台/CPU 行为，**未测量实体手机电量下降百分比**，实时本地合成本身仍需要持续计算。
- 相同小雨声音/seed 1234/官方 ONNX CPU runtime 的对照：`artifacts/prosody-before.wav` 与 `prosody-after.wav`，原文及参数在 `prosody-comparison.json`。57 字复句以前拆成 30/27 字两次合成，现在整句一次合成，11.52 秒音频；全部样本未触及 375 帧上限。未做主观效果保证。相关 CPU/唤醒锁记录在 `power-*.txt` / `power-verification.json`，不提交 Git。
- 停止后重新从已生成的段落听书，实测约 0.93 秒进入 PLAYING，记录 `Reusing cached audio`，未生成新音频；随后停止，系统无唤醒锁。记录在 `cache-replay-verification.json`。较长片段首次生成仍需要等待，未保证所有手机能实时连续合成。
- 已发布 <https://github.com/Escapingbug/myreads/releases/tag/v0.3.1>；标签流水线 <https://github.com/Escapingbug/myreads/actions/runs/36805074813> 成功，49 项测试通过。线上 APK 为 58,801,928 字节，SHA-256 `661f86a58ca084a9e22816a0b36699e8b1de358baebdefac4e67da465ab27de6`，清单与 GitHub 资产一致，证书沿用 0.3.0。`release/` 已同步实际 GitHub APK/清单/校验文件。模型仍不在 APK 中。
- 实际线上非调试 APK 已覆盖安装到 API35 模拟器，版本 0.3.1 / code6，无 DEBUGGABLE 标记；原书架及第三章阅读记录保留，模型仍显示已下载可离线使用。未重新下载模型。截图在 `release-0.3.1-*.png`。

## 长篇朗读接入（0.3.2，2026-10-01）

- `NarrationPlanner` 从原文保留完整句、疑问/省略号、段落、标题及对白标记，再应用已有标点/数字归一化。短对白与常见叙述尾句一起生成；实际 SentencePiece token 上限 75、估计时长上限 22s，超长句优先在分句或单词边界拆分。375 帧上限仍仅触发缩小重试，不播放截断输出。
- `NarrationPrompt` 按官方 continuation 模板在 user 中放“上一句完整文本 + 当前文本”，在 assistant 的 audio_start 后放上一句生成的音频 token（slot 9）。每次仅保留上一完整单元，段落/章节/跳转重新使用所选预设音色；不会让全书上下文无限增长，也不重新编码已有 WAV。前缀只作上下文，输出只含当前单元的音频。
- `CodecStreamDecoder` 使用官方 decode_step 图及元数据中的有界 transformer/attention 状态；连续生成复用状态，缓存命中转生成时仅预热上一单元一次。`SpeechEdges` 使用固定版本 Silero VAD 16k op15 模型，48k 音频经 3:1 降采样、512 + 64 上下文窗口检测首尾，阈值 0.25。`NarrationTiming` 仅校准外部静音，保留句内 PCM、96ms 两侧保护余量；检测不确定时保留整段音频。句段类型对应区间，不足时补零、过长时只移除检测边缘；保护余量优先，实际停顿可超过目标上限。尚未做真人听感评分或 ASR 完整性评测。
- `NarrationBuffer` 以有效生成耗时/音频时长测量 RTF，缓存命中及用户暂停时间不计入；保存在设备偏好中。自动模式 RTF × 倍速 ≥ 0.9 时准备整章并一次提交队列；边生成模式准备约 10 秒可播放声音后起播，按 30–90 秒目标缓冲。章节间仍可能等待，不承诺慢设备实时。准备进度不推进实际阅读位置。用户可以选择 auto / stream / chapter，下次开始生效。
- 缓存版本 `narration-context-v1`：WAV 与 `.codes` sidecar 一起存储；键包含正文、结构、音色、续读模式及前文文字/token/尾停顿摘要。旧 WAV 不会被当成新算法缓存，模型不失效。缓存回放不加载 ONNX；128 MiB 淘汰同时清理 sidecar，正在播放及未提交的整章文件保留，超长待播章节可暂时超过预算。空间不足会明确报错。
- 准备完成后释放模型；暂停保留推理状态并释放合成唤醒锁。准备阶段通过 MediaSession 的 ForwardingPlayer 暴露 BUFFERING，使首个 WAV 入队前也能用系统媒体/锁屏暂停。按用户播放意图暂停 producer，不通过内部暂停播放器阻止起播，避免互相等待。
- 下载目录和模型 id 不变。清单仅增加 decode_step（351,400 bytes，共享已有数据）和 Silero VAD（1,289,603 bytes）；合计新增 1,641,003 bytes，约 1.6 MiB。模型共 719,055,289 bytes，685.7 MiB，依然由用户点击下载，APK 无模型。Silero 固定提交 `1e261b036686cd0017d500ee96acd1c4ba572a9d`，SHA-256 `7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49`，MIT 许可证在 assets/tts 中。模拟器“继续下载”后旧 11 文件大小/mtime 全部不变，仅下载两文件并校验。
- 验证记录在 `artifacts/longform-implementation/`（不提交）：40 项原生测试、26 项前端测试通过；实际 App 5 单元含对白样例 12.468s，续读前缀 31/31 帧，生成未到上限，整章准备后一次入队并播完。生成暂停 144s 后继续，当前单元记录有效生成 6.444s，暂停等待没有计入 RTF；唤醒锁为 0。准备第一段前以及推理中的系统媒体 pause/play 已通过。实际 Android WAV 为 `android-longform.wav`，其文本/帧数/边缘参数见 `android-sample.json`。缓存 5 单元回放未打开 ONNX；模拟器暂停后的第二次 CPU 采样为 0.0%。关闭 Wi-Fi/移动网络后连续两章播放通过，下一章准备没有提前推进阅读位置；首章结束后的等待再入队也保留完整标题和单元顺序（`waiting-chapters-states.json`）。Android lintDebug 通过，Android 7.0 兼容路径使用 Arrays.asList 而非 List.of。尚未在实体手机量化自然度或耗电。

- 已公开发布 <https://github.com/Escapingbug/myreads/releases/tag/v0.3.2>；标签流水线 <https://github.com/Escapingbug/myreads/actions/runs/36848338209> 成功，66 项测试通过。源码标签提交 `a613879`。实际 APK 为 58,820,003 bytes，SHA-256 `7d3144e248b4b2296496ece3d170b01e74452563ce142622fdc186c4f553178f`；线上 assets / update.json / SHA256SUMS 一致，`release/` 已同步实际资产。证书 SHA-256 仍为 `a0abb5c432d5ada068efe059a63c1ec4adef40754a915559e0520538124c1112`，APK 无模型文件、无 DEBUGGABLE 标记。
- 实际线上 0.3.2 / code7 APK 已覆盖安装至 API35 模拟器。书架仍有山间来信、第三章阅读记录；原生听书位置已还原至测试前的 chapter2 / paragraph18。测试用两个临时章节目录与元数据已清理，实际模型和阅读数据保留；Wi-Fi / 移动数据恢复。准备模式设置截图 `final-options.png`，公开 APK 覆盖后截图 `release-shelf.png` / `release-model.png`。强制“边生成边播放”缓存回放也完整播完，未打开 ONNX。

## 长篇朗读算法调研与原型（2026-10-01，原型阶段记录）

用户最新反馈：单句重音基本可接受，主要问题是句段合起来的节奏，时而两句连得太快、时而在不该停的位置断开；明确要求调研模型长篇阅读算法。不要把 0.3.1 的字数切分调整描述为已解决这个问题。

- 调研时（0.3.1）的服务将片段作为独立的 voice_clone 请求，每片重新构建文本/音频提示及 KV；只有固定预设音色，没有上一片文字和已生成语音。Media3 直接播放独立 WAV，没有统一处理片尾/片首原有静音，也没有保留句号、段落、对白等边界信息。播放器按三个片段预生成，尚未按可播放音频秒数/实测生成速度缓冲。此前模拟器生成慢于播放；需要区分音频本身的停顿与等下一片音频的等待，不能把所有空白都归因于切分。
- [Nano 官方 ONNX runtime](https://github.com/OpenMOSS/MOSS-TTS-Nano/blob/8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb/onnx_tts_runtime.py) 按实际文本 token 分句、分句过长再拆分，默认预算 75 token；固定参考音频仍逐片独立合成。插入 0.24/0.40 秒静音的分支按空白分隔单词数判断，普通中文通常走 0.40 秒分支，不能视为小说语义停顿模型。官方 Nano Reader 的浏览器实现使用相同方法。
- **Nano 模型本身支持 continuation，ONNX 包装接口只暴露 voice_clone。** [官方 infer.py](https://github.com/OpenMOSS/MOSS-TTS-Nano/blob/8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb/infer.py) / `moss_tts_nano_runtime.py` 提供 `prompt_text + prompt_audio + target_text`。官方 HF `modeling_moss_tts_nano.py::build_inference_input_ids` 的做法是：用户文本包含前文加目标文本，上一段音频 token 放在 assistant 前缀，slot 使用 `audio_assistant_slot_token_id`。这与把上一段当作 user 里的音色参考不同。解码包含前缀音频以保留 codec 上下文，再移除前缀样本，只播放新增语音。不能给 Nano 随意加入大模型的 `[pause X.Ys]` 指令。
- `artifacts/longform-research/run_comparison.py` 已将上述 continuation 输入方式移植到**现有** ONNX prefill/decode 图，直接复用上一片生成的音频 token，未使用音频编码器或下载新模型。提示 token 与官方 `prompting.py` helper 核对一致，前缀解码样本长度和最终 WAV 格式/时长检查通过。续接时限定上一片完整文字/音频，在每段开始使用固定音色作为锚点；这是可运行原型，尚未验证真实手机速度、音色长期漂移、漏读/重复和主观自然度。
- 同一份三段小说式测试文本（含长句和短对白）、小雨/seed 1234、Python ONNX CPU 4 线程：`longform-current.wav` 60.88 秒/7 次合成；`longform-official.wav` 66.96 秒/5 次；`longform-paragraph.wav` 62.96 秒/3 次；`longform-continuation.wav` 66.48 秒/7 次。全部未达到 375 帧上限。参数、全文、边界和限制在 `artifacts/longform-research/comparison.json`。Python 和 Android 随机数不同，样本不是 App 实录。为隔离切分，官方策略样本也每片重置 seed（官方完整请求通常只重置一次）。整段样本略超 75 token 默认预算，仍属于实验；未做 ASR/人工评分，不能声称内容完整或效果已改善。
- 当前策略样本片间静音的简单 RMS 估计约 50–280ms，两处段落边界约 70/110ms；说明纯 WAV 拼接没有按结构安排停顿。RMS 阈值只能诊断，不应直接用于正式裁剪轻声/清辅音。续接原型也生成了较长边界静音，不能只接入 continuation 就宣布停顿问题解决。
- 推荐下一步：从原文保留段落、完整句、对白结构；以完整句/语组及实际 token、估计语音时长规划有界合成单元；接入经过验证的 Nano 前文文字/语音续接；识别已有边界静音后按结构校准总停顿；按可播放秒数和生成速度缓冲，提供先生成章节后播放的路径。正文只朗读一次，前缀不重复播放。续接缓存键必须包含前文文字和音频 token 的摘要，不能继续仅按当前文本缓存。跳转/失败/章节边界需要明确上下文重置及音色锚定策略。
- 耗电约束：朴素前缀重解码会增加计算，原型不能证明省电。后续可验证复用有界 codec 状态，避免每片重复解码前缀；官方 `moss_audio_tokenizer_decode_step.onnx` 本地大小 351,400 字节，共享现有 codec 数据，但当前 App 下载清单不包含这个图，若采用需要固定版本/哈希和兼容验证。不要为长篇阅读让上下文/KV 随整本书无限增长。
- 研究依据：[ContextSpeech](https://arxiv.org/abs/2307.00782) 在中文有声书场景利用历史文本/语音状态与段落语义建模；[时长感知停顿预测](https://arxiv.org/abs/2302.13652) 说明应同时考虑停顿位置与时长。这些是模型训练方案，不能把未经微调的 BERT 接到 Nano 上就视作拥有该能力。[MOSS-TTS v1.5](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-v1.5) 有前缀续接和显式停顿但为 8B，不适合直接替代当前手机方案；[Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) 有 0.6B/1.7B 版本及长语音研究，公开长篇评估主要为 1.7B，不能据此保证 0.6B Android 实时或电量表现。优先验证现有 Nano 的续接方案，保留用户的 App 内下载、手机离线要求。

本轮没有修改 App 实现、版本号或发布 APK；研究产物在本机 `artifacts/longform-research/`，不提交大音频和模型文件。


## 0.3.3 对白与段落音色修正（2026-10-01）

用户实听反馈：带引号内容断续、段首字不清楚、段落之间声线变化。本轮重新验证 0.3.2 的实现，以下结论取代先前续读原型的生产方案：

- 0.3.2 将引号内各句拆成很短请求，并在每段首句切换为 voice_clone、后续句切换为上一小句音频的 continuation。短前缀和递归生成没有得到长听验证，容易产生衔接和声音不稳定。新的规划器用引号栈保留中英文及嵌套对白；在 75 个实际文本 token / 估计 22 秒上限内合并不足约 6 秒的短句，保留内部标点；长对白优先在句末分开，再选择分句或词边界。异常未闭合引号也保持有界，不丢原文。
- 更关键的问题是结束标记的随机采样。官方 fixed ONNX 图为 `assistant_random_u <= P(continue)`；Android 每请求重置 seed 1234，同一随机序列曾让两个不同文本都在 58 帧提前结束。第 58 帧的随机输入为 0.9993228；同文本改用确定结束判定后由 58 帧延至 175 / 105 帧，旧 58 帧的全部音频 token 逐项相同，确认该对照由结束判定造成。改为 `u=0.5`，即选择概率更高的 continue/end；仍保留原音频 token 的随机采样及随机数序列，375 帧封顶 / 缩小重试继续生效。这降低随机早停，不能保证模型永远没有漏词或发音错误。
- 曾实验固定的完整语音前缀，避免逐句递归漂移；Python 样例可运行，但实际 Android ASR 出现一次“快进来吧”的重复。因此最终生产方案统一使用所选声音的同一份**官方 voice_clone 参考**，不在段首切换模式，不传递上一单元声音，不播放校准句，也无需额外准备或下载声音参考。模型在完整语组内部负责多句节奏；语组之间仍由结构停顿校准。应明确这是移除不稳定的续读路径，而不是声称获得了跨整章语义模型。
- 编解码对比：同一批旧 Android 音频 token，step 与 full ONNX 解码的平均绝对差约 3.5e-8 至 6.8e-8，未发现 codec 损坏。当前独立生成的语组均从 codec 初始状态解码，保留零状态张量、及时释放结果，不把历史音频状态混入新语组。ONNX 2 CPU 线程、关闭 spinning、暂停检查点和生产结束释放模型继续保留。
- VAD 只建议首尾边界，裁剪还需确认样本幅值小于 1/32768；有可保留到 16-bit WAV 的轻声或尾音时扩展边界及保护余量。余量由 96ms 增至 160ms，首次输出保留 160ms 启动时间。已添加检测晚于轻声起音、提前结束于轻声尾音的回归，保留区采样逐点一致。不能仅凭这些测试认定用户设备上的首字听感已完全解决。
- 缓存 revision 为 `narration-fixed-voice-v2`，包含模型、声音、当前文本及边界、前一片尾部时长；不再依赖前一片声音 token。旧生成缓存不会被新算法误用，正文与模型不删除。下载清单仍为原 13 文件 719,055,289 bytes；0.3.2 用户无需补充下载。
- 本机回归：26 项 Vitest + 43 项 Java JUnit 共 69 项通过；生产前端构建、Android assembleDebug / lintDebug 通过。测试涵盖完整/嵌套/ASCII/未闭合引号、短句内部标点、长对白句末切分、码点/文本顺序、保守裁剪、首次输出、缓存声音及边界、缓冲和暂停。
- 模型对照与原文保存在 `artifacts/narration-fix/`。六段小说式样例，Java Random 与 Android 音频采样顺序一致；比较旧切分、固定前缀、固定官方参考及确定结束标记。三种声音 Xiaoyu / Junhao / Yuewen 各六段的最终策略样例未触发帧上限；使用 faster-whisper small 本机识别作内容诊断，有同音字、标点和少量词语识别误差，不是人工听感评分，不把 ASR 误字当成已确认的 TTS 错读。ASR 及其模型仅在本机 artifacts 下，不打包或下载到 App。
- 实际 Android 最终样例 `android-final-dialogue.wav` 为 48kHz / mono / 16-bit，包含标题及三段原文，共 22.614 秒；四单元 23 / 50 / 88 / 99 帧，约 6.8 / 12.7 / 20.3 / 23.9 秒有效生成。ASR 中这组段首内容及完整末句可识别，未再次出现固定前缀实验里的那处短语重复。整章准备后播放完成，准备时不提前推进段落；缓存回放不打开 ONNX，系统媒体暂停 / 继续可用。对四单元完整解码原始样本逐片核对，最终 WAV 完整保留原始 PCM，只添加 160 / 642 / 490 / 522ms 的起始静音；跨平台量化最大差 1 LSB。实体手机、用户具体小说及更长文本仍需要试听验证。

- 0.3.3 已公开发布：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.3>；标签提交 `13338186cc2ee5a78baf1b719fa2c960ccdb1f5a`，流水线 <https://github.com/Escapingbug/myreads/actions/runs/36857367143> 成功，69 项测试通过。实际线上 APK 58,820,007 bytes，SHA-256 `4d6ac1713d5666c6188691654771e0de92d0db89415ed73fafc35adfb32cfe01`；发布资产、update.json、SHA256SUMS 一致，`release/` 已下载实际线上资产。证书 SHA-256 与 0.3.2 相同（`a0abb5c432d5ada068efe059a63c1ec4adef40754a915559e0520538124c1112`），APK 不含模型权重，无 DEBUGGABLE 标记。实际线上 APK 已覆盖安装到 API35 模拟器，书架仍有山间来信及第三章阅读记录；已查看听书页截图，模型仍显示“已下载，可离线使用”。测试前原生 chapter2 / paragraph18 位置已还原，临时测试章节已清理。截图 `artifacts/narration-fix/release-shelf.png` / `release-model.png`。

## 运行方法

```sh
npm install
npm run dev
npm test
npm run android:build
```

输出位置：`android/app/build/outputs/apk/debug/app-debug.apk`。

安装到模拟器或已授权的 Android 手机：

```sh
/opt/homebrew/share/android-commandlinetools/platform-tools/adb install -r artifacts/zijian-0.2.1-debug.apk
```

模拟器保留了一本完整的《山间来信》演示书，普通 APK 不会自动运行验证代码。新安装默认书架为空，内置「全本小说」「纸间演示」两个书源。可选演示书源，搜索「山间来信」「夜行列车」或「小院四季」验证完整流程。

书源包生成：`npm run sources:pack`。ZIP 根目录含 `manifest.json` 与 `source.js`；JSON 包含 `manifest` 与 `script`。接口为 `search`、`getBook`、`getChapters`、`getChapter`，结构见 `src/types.ts`。内置源文件在 `public/sources/`。

原生验证构建（会创建演示测试数据，正常 APK 不执行）：

```sh
npm run sources:pack
npx vite build --mode native-verify
npx cap sync android
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

完成验证后用 `npm run android:build` 恢复普通 APK。

## 全本网站调研与实现约束

公开 HTML 路径：

- 搜索：`/index.php?c=book&a=search&keywords=关键词`
- 详情：`/n/{book-slug}/`
- 目录：`/n/{book-slug}/list.html`
- 章节：`/n/{book-slug}/{numeric-id}.html`，正文 `#content`
- 完整目录按站点「展开完整列表」的 JSONP 动作加载，带目录页 Referer，使用页面提供的参数规则。

实测《斗破苍穹》1,621 个不同链接，章节 ID 存在缺口，必须按目录链接下载，不猜连续数字。下载完成只表示目录中的内容保存成功，不保证原网站本身无缺章。

仅处理公开页面，不实现代理服务，不绕过登录、付费或访问限制。

## 环境与后续注意点

- 工作区 `/Users/maclaw/Documents/myreads`，Git 远程为用户指定的 `escapingbug/myreads`，主分支 `main`。
- SDK `/opt/homebrew/share/android-commandlinetools`；平台 34/35/36、Build tools 35/36；模拟器 `thrust-api35`。
- NDK 28.2.13676358；CMake 3.22.1，原生 C++ 分词编译依赖。
- JDK 21 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；Node 26，Capacitor 8，Vue 3，Vite 8。
- `CapacitorHttp.enabled: false` 关闭的是 fetch/XHR 自动替换，直接 `CapacitorHttp.request` 在原生测试中已验证可用。
- Worker 可终止且没有 DOM/原生桥接访问，但**不是独立安全沙箱**。书源是可执行代码，只安装可信来源；UI 已明确提示与显示声明域名。未来接入不可信公共书源市场前应做进一步隔离与安全审查。
- 原生返回键和后台暂停已接入；返回键在复杂面板/弹窗组合上的人工测试、实体手机的文件选择器、长篇全量下载及异常断电恢复仍可继续检查。
- 小说章节下载离开应用会暂停，返回后由用户继续。听书模型下载使用原生前台服务，可在后台继续；听书使用 Media3 MediaSessionService。
- 浏览器保存位置依赖浏览器存储。正式 Android 版使用 app 私有 SQLite/持久文件。
- `npm audit` 当前 3 个 moderate，均为开发期 `@capacitor/cli → xcode → uuid` 链条；不在 Android App 运行时。尚未做依赖降级或强制升级。
- 当前 `lucide-vue-next` 可编译运行，但包被标记 deprecated；后续可评估迁移，当前未为此扩大改动。
- 签名发布配置与版本策略已经接入。当前正式 APK 沿用早期安装包的证书以支持覆盖升级；密钥保存在本机与 GitHub Actions Secrets，不能提交到仓库。

## 语音无障碍模式（0.3.4，2026-10-08）

- 用户确认首次配置由他人协助，日常不依赖 TalkBack；开启无障碍模式必须真正加载现有 MOSS 模型。入口为「设置 → 语音无障碍模式」，可先配置麦克风权限。模型缺失、校验未完成或加载失败时保留普通界面；记住开关，下次启动先加载再进入模式，不自动下载模型。
- `AccessibleMode.vue` / `accessibility-input.ts`：首页继续上次、最近读听、书架、找新书；书籍、历史和搜索结果统一一次一本，简介、目录、换段及语速，大按钮、固定返回和帮助、明确退出确认。触摸/移动探索与执行分开；两次确认点都落在其他区域也保留原选择，页面切换取消待确认触摸。320×568、320×640、390×844 布局已检查，最小触控高度 56px，返回始终可见。
- `accessibility.ts` / `ZijianAccessibilityPlugin`：提示全部由本地 MOSS 模型合成，按音色和文本缓存（64 MiB），新提示替换旧提示。录音先等待提示结束，提示音表示识别就绪；识别结果读回后确认搜索、支持最近搜索和切换书源。权限弹窗在普通设置页由协助者处理，模式内缺少授权时返回语音错误提示。Android `SpeechRecognizer` 是独立识别服务，模型本身不承担语音识别，可能联网。当前发现新书支持语音搜索和未听过的本地书，没有新增在线分类/榜单书源协议。
- `SpeechModelRuntime`：正文和提示共用一套 ONNX 会话及分词器，公平锁串行推理；模式开启期间持有模型，禁止删除模型。操作提示优先，在检查点让出正文合成，未提交的语组随后重试；普通模式暂停仍保留推理状态。新增优先级唤醒，防止先前暂停的合成持有模型而阻塞开启模式。播报前暂停正文，恢复前核对书籍和播放意图，媒体键暂停、停止、音频焦点丢失及耳机拔出会阻止自动恢复。播放意图用 JSON `optLong` 读回，避免 Capacitor `getLong` 拒绝小整数导致恢复失败。
- 驻留模型关闭 ONNX CPU arena / memory pattern，避免不同提示形状的临时分配持续占据高水位；模拟器观察到的进程驻留内存由长时间运行中的 6GB 级降至后续检查约 1.1–1.4GB，尚不能代表实体手机的长时间占用。模型对“下一本”这种裸短词曾长时间不结束，触摸按钮统一朗读完整的“这是…按钮。”；首次该句约 10 秒完成，缓存后约 2.3 秒（包含播放本身）。
- `library.ts`：历史独立持久化，初始化从已有进度迁移，删除本地书籍保留历史；重新下载按章节 ID 恢复进度并优先下载保存位置的章节，文件索引仍使用原目录位置。浏览列表使用快照，后台进度更新不会打乱正在浏览的顺序。
- 验证：42 项前端测试、45 项原生测试通过，Vue/TypeScript/Vite 和 Android debug 构建通过，Android lint 无错误。API35 模拟器覆盖安装后保留书架、模型和进度，确认加载模型、生成及缓存提示、真实触摸选中/异处确认、书架浏览、重启恢复；原生完整提示播完后恢复为 playing，提示中主动暂停后恢复请求返回 false 并保持 paused。语音输入确认、迟到搜索和提示等待期间取消下载有组件测试；尚未验证真人中文语音识别成功率，也未在实体手机测量延迟/内存/耗电。模拟器首次加载约 6 秒，首次首页提示合成约 14 秒，不能保证提示立即响应，重复提示命中缓存。
- 调试验证包：`artifacts/accessibility/zijian-accessibility-debug.apk`（功能开发时为 0.3.3/code8）。用户已明确要求直接发布供测试，发布版本递增为 0.3.4/code9，说明见 `docs/releases/0.3.4.md`；按已有标签流水线构建签名 APK 并发布更新清单。
- 已公开发布：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.4>。标签提交 `f43acc172279ae70a984de88854add2ea79838ec`；流水线 <https://github.com/Escapingbug/myreads/actions/runs/37747339444> 全部成功，87 项测试通过。线上 APK 为 58,844,039 bytes，SHA-256 `32aadb546c46ca2e3079b9bbfbe28dd1f910c2bc89692ff3836d9e015a3dadc5`；GitHub 资产摘要、update.json、SHA256SUMS 一致，`release/` 已同步实际线上资产。签名证书仍为 `a0abb5c432d5ada068efe059a63c1ec4adef40754a915559e0520538124c1112`；APK 不含模型权重，无 DEBUGGABLE 标记，应用使用的版本化 GitHub API 已返回 0.3.4 为最新正式版本。
- 实际线上 APK 已覆盖安装至 API35 模拟器，安装版本 0.3.4/code9。升级前后 SQLite 书籍记录逐项一致，《山间来信》六章及 chapter2/paragraph18 阅读位置保留；13 个模型文件共 719,055,289 bytes 和 `.ready` 标记完整保留。正常书架界面已检查。验证材料在 `artifacts/accessibility/`（发布清单、签名/摘要核对、数据库快照、模型文件列表及 `release-shelf.png`），不提交。

## 0.3.5 首播等待调整（2026-10-08）

- 用户实测整章准备超过 10 分钟，明确接受句段衔接处必要的缓冲，优先开始播放。取消旧自动策略根据 `ratio × speed >= 0.9` 等待整章的行为，也取消起播前积累 10 秒音频的条件；每个完整语组合成或命中缓存后立即入播放队列，后台继续按音频时长预生成。
- 前端默认 `stream`，旧 `auto` 设置读取时映射为 `stream`，原生入口也兼容旧 `auto`；显式 `chapter` 选择保留。设置页提供边生成边播放和先准备本章两项。速度统计只控制后续预生成量，不再控制整章等待；普通界面和无障碍模式共用此策略。
- 合成单元、模型配置、官方声音参考及 `narration-fixed-voice-v2` 缓存键保持一致。整章总计算耗时没有被本轮调度修改加速；长段会拆成多个语组，因此缓冲也可能出现在段落内部的语组衔接处。没有测得用户手机的实际生成/播放比，不能仅凭整章生成分钟数判定持续生成速度慢于播放。
- 43 项前端及 46 项原生测试通过，前端/Android 构建通过，lint 无错误。新增旧自动设置迁移及高耗时样本不触发整章准备的回归。API35 模拟器保留旧生成/播放比约 2.85，直接调用旧 `auto` 入口，43 个语组的章节在第 1 个语组完成时进入 playing，首段约 3 秒音频，证明没有整章或 10 秒音频门槛。该次冷启动约 28.8 秒，包括模型加载及 18.1 秒首语组合成，不能代表用户真机延迟；同一首段缓存回放约 1.1 秒进入 playing。测试后还原 chapter2/paragraph18 位置。
- 发布版本为 0.3.5/code10，说明见 `docs/releases/0.3.5.md`，沿用用户授权的 GitHub 发布流程供继续测试。
- 已公开发布：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.5>。标签提交 `3b6b871f5f8bad712a70d8710aa360571220f025`；流水线 <https://github.com/Escapingbug/myreads/actions/runs/37752915843> 全部成功，89 项测试通过。实际线上 APK 58,844,083 bytes，SHA-256 `628f2415dd746a3969e05b634338f6f8b32a4f662f3f744c75abd280aedcce4c`；GitHub 资产摘要、update.json 与 SHA256SUMS 一致，签名仍与 0.3.4 相同，APK 无 DEBUGGABLE 标记、无模型权重。最新正式 Release API 已返回 0.3.5，`release/` 已同步线上资产。
- 线上 APK 已覆盖安装到 API35 模拟器，版本 0.3.5/code10。升级前后书籍记录逐项一致，《山间来信》六章、chapter2/paragraph18 进度，以及 13 个模型文件和 `.ready` 标记保留。安装与验证材料在 `artifacts/accessibility/`。

## 0.3.6 有界文字／语音续接（2026-10-08）

用户明确授权实现完整语义单元与有界续接并直接发布，供实体手机实听。本次未改为句内 PCM 流式播放：仍在首个完整语组 WAV 完成后起播，默认不会等待整章。

- 叙述在 75 文本 token / 估计 22 秒范围内尽量一起合成；完整对白及其常见叙述尾句保持一组，不再为满足最短时长合并不同对白或叙述。省略号的外部停顿边界保留。
- `NarrationContext` 滚动保留至多三个完整生成单元，同时限制 150 文本 token / 300 音频帧（24 秒）；按完整单元淘汰，不截取不匹配的文字和尾音。普通段落间延续；标题、章节、空白／分隔标记、跳转及新播放会话清空；至多四次连续续接后重新使用官方音色参考。不足 25 帧的历史先积累，不立刻作为短前缀。参数是有界试用起点，不是经过主观评分证明的最佳窗口。
- 按 Nano 官方 continuation 模板，将历史转录和目标文本放在 user、历史语音 codes 放入 assistant 的 slot 9。每次请求重置 codec，以 32 帧批次预热匹配前缀的有界解码状态，只输出当前新生成帧；不能把前文重复加入播放队列。正文与无障碍提示共享模型时，各请求独立初始化／清理 codec 状态。
- 缓存 revision `narration-context-window-v3`，续接键包含全部历史转录及每帧 codes；缓存命中恢复 codes 和历史选择。达到生成帧上限的续接先从固定声音重试一次，仍触及上限才缩小当前未播放单元；失败结果不进入历史。
- 新听书选项“跨句衔接”：默认连贯朗读；独立朗读不传历史，供同一文字／声音对照，停止后重新开始生效。保存到 tts-options，并随原生 play 请求传递。旧选项没有此字段时默认开启；原来的 auto 准备方式仍迁移为 stream。
- 44 项前端、53 项原生测试通过，生产前端构建、Android assembleDebug 和 lintDebug 通过。覆盖完整窗口淘汰、跨普通段落、短历史积累、重锚定、缓存上下文隔离、对白分开、场景分隔和官方提示结构。
- 实际 Android API35 样例九个单元完成，历史单元数 0/0/1/2/3/3/0/0/0；中间最大使用 134 帧完整前文，周期重锚定和场景 reset 生效，未触及 375 帧上限。输出 WAV 长度只包含目标帧及有界停顿，未含前文；ASR 未发现样例中的前文重复（含过去出现问题的“快进来吧”），有同音字／用字识别误差，不是人工听感评分。九单元相同请求缓存回放全部命中，首尾播放完成，无新生成日志。跳至段落 1 的首单元使用 contextUnits=0，并验证暂停／继续。
- 本机记录在 artifacts/continuation-0.3.6/（不提交）：原始章节、Android WAV/codes 对照、ASR、状态、缓存、设置截图。真实手机重音／情绪、长时间漂移、耗电和实时速度仍由用户实听验证。模型清单及 13 文件不变，无需重下模型。

- 0.3.6 已公开发布：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.6>；源码标签 `fc1366044641ab6caeae77519c34b2bf7d4b72e7`；标签流水线 <https://github.com/Escapingbug/myreads/actions/runs/37767026731> 成功。APK 58,844,247 bytes，SHA-256 `07e26c041f7bdf0bd4fa9328ec3e7ea322082460754ba7857a5bd8a2c808f58e`，证书 SHA-256 `a0abb5c432d5ada068efe059a63c1ec4adef40754a915559e0520538124c1112` 与旧版一致。已核对 APK / update.json / SHA256SUMS / GitHub assets 摘要，正式且为 latest；APK 非调试、不含模型，包含新续接代码和选择器。实际公开 APK 已覆盖安装 API35 模拟器为 0.3.6 / code11，原书架及 chapter2 / paragraph18 保留，原 13 模型文件不变。独立朗读也完成同一样例九单元播放；临时测试章节已移除，原播放偏好恢复。

## 0.3.7 解码开销与起播保护（2026-10-08）

用户反馈 0.3.6 连贯性改善，但手机发热、标点附近怪音和吞首字仍存在。复现文本为「已经冷静下来的维罗妮卡……发出嗤笑：“呵，真厉害啊……极品啊！”」。当前规范化后是一个完整语组，约 49 个实际文本 token，没有在“呵”附近切分。当前规则去掉引号、冒号转逗号；本机尝试保留冒号或完整引号，两种声音的结果不一致，因此没有把这些试验作为稳定修复合入。原始生成音频及 Android WAV 的 ASR 中，“呵”会识别成“哼”或“啊”；ASR 是诊断参考，不是人工听感评分。

- `CodecHistory` / `CodecStreamDecoder`：只在当前前缀逐帧完全匹配之前解码的完整序列时复用状态；缓存命中补充的新前缀仅预热缺失帧。窗口淘汰、重锚定、独立语音或提示以及取消／失败时重置，避免接错历史。保留序列受既有 300 帧前缀和小于 375 帧目标限制；语言模型仍重新预填充，因为新目标文字位于 assistant 音频前。
- 前缀及目标 codec 运算改为每批 16 帧，保持同一因果状态，只收集当前目标 PCM，最后仍生成一个完整语组 WAV。限制单次注意力／波形临时内存，直接读取 FloatBuffer 转单声道；不改变文字切分、采样种子、声音参考或跨句上下文。
- `PlaybackWarmup`：首次起播和 `STATE_ENDED` 后重新起播插入独立静音 WAV，48 kHz / 16-bit / mono 与正文相同，按 0.5–2 倍速调整为约 500ms 墙钟时间。正常连续队列不插入；静音不含 chapter / paragraph 元数据，不推进阅读记录。已有首字裁剪保护保留。
- 合成日志 `stagesMs` 顺序为预填充／语音编码生成／codec／VAD，另记录 `codecWarmFrames`，暂停检查点的等待时间不计入主动合成耗时。
- 44 项前端和 59 项原生单元测试通过，前端生产构建、Android assembleDebug / lintDebug 通过。API35 完成包含原文的九语组播放与暂停／继续。跨句历史 63 / 133 / 166 帧复用时不再重新预热；滚动窗口变化时重新预热；缓存命中的历史可在随后未命中的语组中重新建立状态。
- 在同一模拟器的单次前后对照中，原文续接的 codec 耗时 27,151ms → 13,102ms（后者还包含预热 29 帧），独立合成 24,093ms → 9,174ms。总合成耗时仍明显长于播放，不能保证实时播放。九语组生成 codes 和缓存侧车完全相同；重算 WAV 的长度相同，PCM 最多相差一个 16 位量化单位。Mac ONNX 对照的小批次／状态复用波形差异也小于一个量化单位。
- 用保存的 Android codes 重新解码用户原文，确认两个样本从 raw 开头到 WAV 的 PCM 逐样本完全保留，仅增加外部停顿；其“已经”可被 ASR 识别。本次没有找到首字被裁剪的证据。播放器启动保护的实体手机效果、发热、电量与感叹词读法仍需实听，不能宣称全部修复。

诊断记录保存在 `artifacts/feedback-0.3.6/`（不提交），模型清单和已有 13 文件不变。版本号 0.3.7 / code12，旧缓存继续可用。

- 0.3.7 已公开发布为 latest：<https://github.com/Escapingbug/myreads/releases/tag/v0.3.7>；源码标签 `50c31876b06841ec1d32478a85c937f93114e9a0`；标签流水线 <https://github.com/Escapingbug/myreads/actions/runs/37777355749> 全部成功。APK 58,844,239 bytes，SHA-256 `29c11ac5be5090c0cb02a66358424d60c3849dbb5ac662993cab1a3650d698e3`，签名与 0.3.6 一致。APK、update.json、SHA256SUMS 与 GitHub assets 摘要一致，非调试，不含模型，包含 CodecHistory / PlaybackWarmup。正式 APK 已覆盖安装 API35 为 0.3.7 / code12，7 条原数据库记录逐值一致，原播放 chapter2 / paragraph18 及 13 个模型文件（719,055,289 bytes）保留，普通书架正常。九语组缓存回放全部命中并完成，仅首次起播加入静音，无新合成；临时测试章节及生成缓存已清理。
