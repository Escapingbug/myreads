<script setup lang="ts">
import { computed } from "vue";
import { Download, Pause, RefreshCw, LoaderCircle, ArrowUpCircle, ExternalLink } from "lucide-vue-next";
import { updates, releasesUrl, checkUpdates, setAutomatic, downloadUpdate, pauseUpdate, installUpdate, updateAction } from "../services/updates";
const downloading = computed(() => ["downloading", "verifying"].includes(updates.download.phase));
const percent = computed(() => updates.download.total ? Math.min(100, Math.floor(updates.download.downloaded / updates.download.total * 100)) : 0);
const mib = (value: number) => `${(value / 1024 / 1024).toFixed(1)} MB`;
const checked = computed(() => updates.lastCheck ? new Date(updates.lastCheck).toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }) : "尚未检查");
</script>
<template>
  <section class="app-update-card">
    <div class="section-heading"><div><h2>应用更新</h2><span class="muted">当前版本 {{ updates.versionName }}</span></div><ArrowUpCircle :size="24" /></div>
    <template v-if="updates.supported">
      <label class="update-auto"><span>自动检查新版本<small>每天在打开应用时检查</small></span><input type="checkbox" :checked="updates.automatic" @change="updateAction(() => setAutomatic(($event.target as HTMLInputElement).checked))" /></label>
      <div class="update-check"><span class="muted">{{ updates.checking ? '正在检查…' : `上次检查：${checked}` }}</span><button class="text-button" :disabled="updates.checking || updates.busy" @click="checkUpdates(true)"><RefreshCw :size="15" :class="{ spin: updates.checking }" />检查更新</button></div>
      <div v-if="updates.latest || updates.download.phase !== 'idle'" class="update-release">
        <h3>{{ updates.download.phase === 'ready' ? '更新已下载' : '可用新版本' }} {{ updates.download.phase !== 'idle' ? updates.download.versionName : updates.latest?.versionName }}</h3>
        <p v-if="updates.latest?.notes && (updates.download.phase === 'idle' || updates.latest.versionName === updates.download.versionName)" class="update-notes">{{ updates.latest.notes }}</p>
        <template v-if="downloading">
          <progress :value="updates.download.downloaded" :max="updates.download.total || 1" :aria-label="`更新下载进度 ${percent}%`"></progress>
          <p class="update-progress"><span>{{ updates.download.phase === 'verifying' ? '正在校验安装包…' : `${percent}% · ${mib(updates.download.downloaded)} / ${mib(updates.download.total)}` }}</span><button class="text-button" :disabled="updates.busy" @click="updateAction(pauseUpdate)"><Pause :size="14" />暂停</button></p>
        </template>
        <button v-else-if="updates.download.phase === 'ready'" class="button" :disabled="updates.busy" @click="updateAction(installUpdate)"><LoaderCircle v-if="updates.busy" class="spin" :size="17" /><ArrowUpCircle v-else :size="17" />{{ updates.canInstall ? '安装更新' : '允许安装更新' }}</button>
        <button v-else-if="updates.latest || ['paused', 'error'].includes(updates.download.phase)" class="button" :disabled="updates.busy" @click="updateAction(downloadUpdate)"><Download :size="17" />{{ ['paused', 'error'].includes(updates.download.phase) ? '继续下载' : '下载更新' }} · {{ mib(updates.latest?.size || updates.download.total) }}</button>
        <button v-if="updates.download.phase === 'ready' && updates.latest && updates.latest.versionName !== updates.download.versionName" class="text-button" :disabled="updates.busy" @click="updateAction(downloadUpdate)">下载最新版本 {{ updates.latest.versionName }}</button>
        <p class="update-help">更新保留书架和听书模型，安装时按系统提示确认。</p>
      </div>
      <p v-if="updates.error || updates.download.error" class="error-text" role="alert">{{ updates.error || updates.download.error }}</p>
      <p v-if="updates.message" class="update-message" role="status">{{ updates.message }}</p>
    </template>
    <p v-else>Android 版支持在应用内下载和安装更新。</p>
    <a class="text-button update-releases-link" :href="releasesUrl" target="_blank" rel="noopener noreferrer">GitHub 发布页面 <ExternalLink :size="14" /></a>
  </section>
</template>
