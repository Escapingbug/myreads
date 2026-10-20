# 国内下载与双渠道更新

0.3.11 接入公开仓库 anciety/myreads。0.3.10 及更旧版本仍只从 GitHub 更新，首次升级可使用原 GitHub 更新入口，或从 Gitee 手动覆盖安装。沿用现有签名，不需要卸载。

## 仓库与发布配置

唯一配置文件：`android/app/src/main/assets/distribution.json`。前端直接导入，Android 原生从 APK assets 读取同一文件。

```json
{
  "githubRepository": "escapingbug/myreads",
  "giteeRepository": "anciety/myreads"
}
```

`giteeRepository` 留空时不显示 Gitee 选项，自动发布跳过 Gitee；不会请求占位地址。GitHub 仓库为现有固定官方仓库，迁移它需要同时修改原生信任策略。

Gitee 必须使用公开仓库。不要求迁移源码；空仓库会由发布脚本创建 README 和初始 main 分支。有分支时默认使用仓库的默认分支，可通过 GitHub Actions Variable `GITEE_TARGET_BRANCH` 指定其他已有分支。Gitee 标签会指向该分支；源代码和构建记录仍以 GitHub 的相同版本标签为准。

在 GitHub 仓库 Actions Secrets 中增加 `GITEE_TOKEN`，授权该 Gitee 仓库的发行版写入。令牌只用于发布端，不能放入 distribution.json 或 APK。Gitee 仓库地址会写入 APK，配置后必须重新构建。

原有发布流程不变：递增 package.json 的 version / androidVersionCode，编写版本说明，推送 `vX.Y.Z` 标签。GitHub Actions 完成验证、签名构建和 GitHub Release 后，将同一份 APK、SHA256SUMS、update.json 同步到 Gitee。

也可在准备好 `release/` 资产后执行：

```sh
npm run release:gitee -- vX.Y.Z --dry-run
npm run release:gitee -- vX.Y.Z
```

本地发布从环境变量读取 `GITEE_TOKEN`，可选 `GITEE_TARGET_BRANCH`。脚本先核对 APK 与更新清单、校验文件和 APK 内的 Gitee 配置，禁止拿仍指向旧仓库的 APK 发布。

同步先创建预发布，依次上传 SHA256SUMS、APK、update.json，逐个匿名下载并验证大小及 SHA-256，全部成功后才改成正式版本。大文件通过 curl 上传，最多等待 15 分钟，避免跨境上传较慢时被小请求的超时限制中断。中途失败保留预发布，App 不会选中它。可重新运行脚本：已有同名附件验证后复用，不重复上传；正式版本不覆盖。GitHub 已发布而 Gitee 同步失败时，GitHub 版本仍可使用，Gitee 同步需单独重试；脚本可以直接本地重跑，或在 GitHub Actions 的「Retry Gitee release sync」工作流中填写已有版本标签重新同步，也可选择另一个 CI 运行环境排查网络问题，不需要重新创建 GitHub Release 或重新构建 APK。

## 自动选择规则

- 自动模式并行检查 Gitee 与 GitHub，每个渠道整个检查最多等待 8 秒；一个渠道失败不影响另一个。
- GitHub 使用最新正式 Release API；Gitee 读取最近 100 个发行版，忽略预发布和非标准版本标签，按数值版本选择较新的正式标签，再读取该标签的 update.json。Gitee 的排序显式使用 direction=desc，避免默认升序停留在最老版本。
- 以 Android versionCode 比较两边结果。相同版本默认选 Gitee；只有 versionName、versionCode、size、SHA-256 全部相同，才将另一个渠道作为下载备用。
- 同 versionCode 的文件身份冲突时停止检查并提示，不拼接下载。不同版本不能互为下载备用，不会因为下载失败自动降级。
- 单个渠道可访问时使用它；如果缓存已确认另一边有较新版本，则保留较新版本并提示其渠道离线，不悄悄换成旧版；部分检查失败时不能断言所有渠道都是最新版。全部失败保留经过校验的本地缓存，并显示网络错误。
- 可手动固定 Gitee 或 GitHub。固定后，检查和后台下载、暂停后继续均只使用所选来源。下载中或检查中不能切换设置。
- 下载连接超时、读取停滞、HTTP 错误或文件校验失败会尝试另一个已验证来源，最多每个来源尝试两次。每 60 秒不足 64 KiB 也视为下载停滞。
- 切源通过 HTTP Range 续传；服务器不支持 Range 时从头重下。完整文件继续验证 SHA-256、大小、包名、版本与当前已安装应用的签名。

初始 APK 地址只接受配置仓库下的固定版本附件路由；清单不能提供任意下载地址。GitHub CDN 和 Gitee CDN 跳转域名分别限制。当前 Gitee 允许 gitee.com / foruda.gitee.com；上线前用实际仓库确认跳转和匿名下载，不会为了适配未知 CDN 放开所有域名。

## 听书模型

大型 MOSS 模型不放入 Gitee APK 发行版。默认使用 HF 国内镜像，失败自动切换 Hugging Face；同一次下载后面的文件优先沿用上一个成功来源。每个文件使用清单内固定的仓库 revision、大小和 SHA-256，自动模式两边最多各尝试两次。手动固定来源时只重试该来源。

新增默认选项为“自动（国内镜像优先）”；旧版保存过的 mirror=true/false 分别迁移为固定镜像/固定官方源，保留用户已有选择。用户可以自行改回自动。

Silero VAD 小文件（1,289,603 bytes）随 APK 提供，在模型准备时从本地复制并校验，因此无需再连接 raw.githubusercontent.com。文件来自清单固定提交，保持原 SHA-256 和 SILERO-LICENSE.txt。总共 13 个模型文件、模型目录 ID、固定 revision 与原文件 SHA-256 不变，已安装模型无需重新下载。

HF 国内镜像是第三方公共服务。若实际国内网络仍不稳定，再增加可控的模型镜像；届时按现有逐文件清单提供文件，不把 686 MiB 模型绑定到每次 APK 更新。

## 发布约束

发布流程及发布验证不得使用模拟器，也不通过 ADB 安装、启动应用或读取模拟器数据。验证使用 GitHub Actions、主机单元测试、APK 静态检查、签名与公开下载文件校验。

## 发布验证

自动测试覆盖双渠道可用/不可用、超时、版本滞后、同版本冲突、手动选择、缓存恢复；原生回归覆盖跨源断点续传、重试次数、暂停、仓库及 CDN 域名策略；发布脚本测试覆盖上传顺序、匿名验证、中断后重试及禁止提前公开。

0.3.12 已在 GitHub 与 Gitee 正式发布。已匿名读取实际发行信息、更新清单和完整 APK，核对 SHA-256、大小、包名、版本、原签名以及 APK 内渠道配置；使用实际应用的解析和选择逻辑处理线上数据，结果为 Gitee 优先、GitHub 同文件备用。126 项前端／发布工具测试与 76 项 Android 主机单元测试通过，云端验证和签名构建通过。APK 为 59,941,870 bytes，SHA-256 为 `af548701187ddebec31f39e6ab94a138cd70f2c508a6af61c64388c5c8b4a09a`。

Gitee 实际 CDN 为 foruda.gitee.com。0.3.11 发布验证时的 Range 请求返回完整文件（HTTP 200），应用按既有分支从头重下，不能把该渠道当前的 Range 行为称为已支持续传。

0.3.11 云端 API 上传大 APK 多次停滞，0.3.12 在 Ubuntu / macOS 环境也出现连接超时。本次改用 Gitee 网页附件表单上传同一签名 APK、清单和校验文件；随后「Retry Gitee release sync」成功复用所有附件、逐项匿名验证并转为正式。今后遇到同样问题，可停止悬挂任务，在网页预发布编辑页上传已签名 APK 及所需文件，保留预发布选项，再运行同步工作流完成验证与公开。不能把本次结果称为附件的云端自动上传已经验证成功。
