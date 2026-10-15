import distribution from "../../android/app/src/main/assets/distribution.json";

export type UpdateSource = "gitee" | "github";
export type UpdateSourcePreference = "auto" | UpdateSource;
export interface UpdateChannel { source: UpdateSource; repository: string; host: string; apiUrl: string; releasesUrl: string }
export const sourceLabels = { auto: "自动（Gitee 优先）", gitee: "Gitee", github: "GitHub" };
const repositoryPattern = /^[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+$/;
export function channelsFor(config = distribution): UpdateChannel[] {
  return (["gitee", "github"] as const).flatMap((source) => {
    const repository = config[`${source}Repository`];
    if (!repository) return [];
    if (!repositoryPattern.test(repository)) throw new Error(`${sourceLabels[source]} 仓库配置无效`);
    const host = source === "github" ? "github.com" : "gitee.com";
    return [{ source, repository, host, releasesUrl: `https://${host}/${repository}/releases`,
      apiUrl: source === "github" ? `https://api.github.com/repos/${repository}/releases/latest`
        : `https://gitee.com/api/v5/repos/${repository}/releases?per_page=100&page=1&direction=desc` }];
  });
}
export const updateChannels = channelsFor();
export function channelFor(source: UpdateSource): UpdateChannel {
  const channel = updateChannels.find((item) => item.source === source);
  if (!channel) throw new Error(`${sourceLabels[source]} 更新渠道尚未配置`);
  return channel;
}
export function releaseFile(channel: UpdateChannel, tag: string, name: string) {
  return `https://${channel.host}/${channel.repository}/releases/download/${tag}/${name}`;
}
