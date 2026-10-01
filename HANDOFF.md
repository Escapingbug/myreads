# 纸间 Android 小说阅读器：项目交接

更新时间：2026-10-01

## 用户目标与范围

第一批：Android 小说阅读器，动态书源、搜索、整书下载、本地离线阅读。主要路径为「书架 → 搜索书源 → 书籍详情 → 下载章节到本机 → 本地阅读」。阅读器只读取已保存的章节，不通过实时网页加载正文。已下载内容不依赖书源继续安装。

第二批：接入 MOSS-TTS-Nano 中文本地听书。用户明确要求模型不带在 App 中，必须在 App 内点击下载；只打包 ONNX Runtime、SentencePiece 和下载元数据。仍不支持 iOS。

第三批：用户指定 `github.com/escapingbug/myreads` 作为远程仓库与发布渠道，接入 GitHub Release 自动检查、应用内 APK 下载和覆盖安装，以及标签触发的自动发布。

## 当前完成状态

阅读、听书和更新功能已经实现。当前 0.3.3 已修正引号对白切分、段落生成模式切换、句首裁剪及随机提前结束，本机测试与 Android 样例回归已通过，待 GitHub Actions 签名发布。0.3.2 的逐句 continuation 经用户长听反馈及本轮对照发现不稳定，当前生产版统一采用官方固定声音参考合成完整语组。尚未在实体手机量化长篇听感或耗电。详见下文。

- `src/App.vue`、`src/style.css`：移动端书架、继续阅读、搜索及分页、详情和完整目录预览、下载管理、ZIP/JSON/HTTPS 书源导入确认、启停与卸载、错误/空态、删除确认、原生返回键和应用生命周期。
- `src/components/Reader.vue`：本地单章上下滚动、上下章、目录跳章、字号、行距、纸色/明亮/夜读、自动保存章节/段落/段内位置。未下载章节禁用。
- `src/services/library.ts`：完整目录分页收集、去重及重复游标检测、串行下载队列、失败重试、暂停继续、后台暂停、重启后保留暂停状态、下载时保存书源快照。
- `src/services/storage.ts`：Android SQLite 元数据与 `Directory.Data` 持久章节文件；先写临时文件并重命名，再提交下载记录。浏览器使用 IndexedDB。元数据写入已串行化，初始化可重试。
- `src/services/runtime.ts`、`source.worker.ts`：可终止的 Worker 运行书源，启动/执行超时、返回格式校验、网络串行与 350ms 节流、取消请求。
- `src/services/html.ts`：将 HTML 片段包在 body 中，修正 linkedom 对目录展开片段的不同处理，避免丢失中间章节。
- `src/services/http.ts`、`dev-proxy.ts`：Android 原生 HTTP；开发期本机 HTTPS 代理，校验域名、DNS 公网 IP、固定已校验地址、请求/响应大小及超时。已修复 Node 26 的 DNS `lookup` 全量返回格式。
- `src/services/packages.ts`、`import.ts`：书源包格式、域名与大小验证；ZIP/JSON 解码，HTTPS 下载包，安装前显示名称、版本和声明域名。
- `android/`：Capacitor Android 工程、纸间书本图标及启动主题、版本 0.3.3 / versionCode 8，原生插件已同步。版本从 `package.json` 读取。
- `scripts/build-android.mjs`：当前 Mac 使用已安装的 JDK 21 / SDK；若 shell 配置的是旧 JDK，会切换到 Homebrew JDK 21。

发布 APK：`release/zijian-0.3.3.apk`，同时生成 `update.json` 与 `SHA256SUMS`。公开下载入口：<https://github.com/escapingbug/myreads/releases/latest>。旧版调试 APK 与本机截图/音频记录在 `artifacts/`，均不提交 Git。

## 已完成的验证

- `npm test`：5 个测试文件、23 项测试通过；Android 原生单元测试 26 项通过。更新测试覆盖正式 Release 资产/清单、大小/哈希/版本匹配、版本升级判断、自动检查开关与节流、离线缓存、原生下载恢复和显式下载。原有 13 项覆盖听书边界及位置同步、书源包、数据边界、目录、下载队列、离线保存和恢复。
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
- 更关键的问题是结束标记的随机采样。官方 fixed ONNX 图为 `assistant_random_u <= P(continue)`；Android 每请求重置 seed 1234，同一随机序列曾让两个不同文本都在 58 帧提前结束。改为 `u=0.5`，即选择概率更高的 continue/end；仍保留原音频 token 的随机采样及随机数序列，375 帧封顶 / 缩小重试继续生效。这降低随机早停，不能保证模型永远没有漏词或发音错误。
- 曾实验固定的完整语音前缀，避免逐句递归漂移；Python 样例可运行，但实际 Android ASR 出现一次“快进来吧”的重复。因此最终生产方案统一使用所选声音的同一份**官方 voice_clone 参考**，不在段首切换模式，不传递上一单元声音，不播放校准句，也无需额外准备或下载声音参考。模型在完整语组内部负责多句节奏；语组之间仍由结构停顿校准。应明确这是移除不稳定的续读路径，而不是声称获得了跨整章语义模型。
- 编解码对比：同一批旧 Android 音频 token，step 与 full ONNX 解码的平均绝对差约 3.5e-8 至 6.8e-8，未发现 codec 损坏。当前独立生成的语组均从 codec 初始状态解码，保留零状态张量、及时释放结果，不把历史音频状态混入新语组。ONNX 2 CPU 线程、关闭 spinning、暂停检查点和生产结束释放模型继续保留。
- VAD 只建议首尾边界，裁剪还需确认样本幅值小于 1/32768；有可保留到 16-bit WAV 的轻声或尾音时扩展边界及保护余量。余量由 96ms 增至 160ms，首次输出保留 160ms 启动时间。已添加检测晚于轻声起音、提前结束于轻声尾音的回归，保留区采样逐点一致。不能仅凭这些测试认定用户设备上的首字听感已完全解决。
- 缓存 revision 为 `narration-fixed-voice-v2`，包含模型、声音、当前文本及边界、前一片尾部时长；不再依赖前一片声音 token。旧生成缓存不会被新算法误用，正文与模型不删除。下载清单仍为原 13 文件 719,055,289 bytes；0.3.2 用户无需补充下载。
- 本机回归：26 项 Vitest + 43 项 Java JUnit 共 69 项通过；生产前端构建、Android assembleDebug / lintDebug 通过。测试涵盖完整/嵌套/ASCII/未闭合引号、短句内部标点、长对白句末切分、码点/文本顺序、保守裁剪、首次输出、缓存声音及边界、缓冲和暂停。
- 模型对照与原文保存在 `artifacts/narration-fix/`。六段小说式样例，Java Random 与 Android 音频采样顺序一致；比较旧切分、固定前缀、固定官方参考及确定结束标记。三种声音 Xiaoyu / Junhao / Yuewen 各六段的最终策略样例未触发帧上限；使用 faster-whisper small 本机识别作内容诊断，有同音字、标点和少量词语识别误差，不是人工听感评分，不把 ASR 误字当成已确认的 TTS 错读。ASR 及其模型仅在本机 artifacts 下，不打包或下载到 App。
- 实际 Android 最终样例 `android-final-dialogue.wav` 为 48kHz / mono / 16-bit，包含标题及三段原文，共 22.614 秒；四单元 23 / 50 / 88 / 99 帧，约 6.8 / 12.7 / 20.3 / 23.9 秒有效生成。ASR 中这组段首内容及完整末句可识别，未再次出现固定前缀实验里的那处短语重复。整章准备后播放完成，准备时不提前推进段落；缓存回放不打开 ONNX，系统媒体暂停 / 继续可用。实体手机、用户具体小说及更长文本仍需要试听验证。

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
