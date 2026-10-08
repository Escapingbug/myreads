<script setup lang="ts">
import { computed, ref } from "vue";
import { Download, Pause, Headphones, Trash2, CheckCircle2, LoaderCircle } from "lucide-vue-next";
import { tts, voices, formatModelSize, ttsAction, downloadModel, pauseModelDownload, removeModel, saveTtsOptions } from "../services/tts";
const deleting = ref(false);
const downloading = computed(() => ["downloading", "verifying"].includes(tts.model.phase));
const percent = computed(() => Math.min(100, Math.floor(tts.model.downloaded / tts.model.total * 100)));
const labels = { missing: "尚未下载", downloading: "正在下载", verifying: "正在校验", paused: "下载已暂停", ready: "已下载，可离线使用", error: "下载未完成" };
</script>
<template>
  <section class="listen-model-card" aria-label="听书模型管理">
    <div class="listen-model-heading">
      <span class="source-symbol"><Headphones :size="23" /></span>
      <div><h2>中文离线听书</h2><span class="muted">MOSS-TTS-Nano · {{ formatModelSize(tts.model.total) }}</span></div>
    </div>
    <p>点击下载声音模型，之后无需联网就能朗读已下载的小说。首次下载建议使用 Wi-Fi。</p>
    <p class="muted">声音在手机上生成，会比播放普通音频更耗电。暂停听书也会暂停生成；已有缓存的片段可直接播放。</p>
    <div class="listen-model-status" role="status">
      <CheckCircle2 v-if="tts.model.phase === 'ready'" :size="17" />
      <LoaderCircle v-else-if="downloading" class="spin" :size="17" />
      <span>{{ labels[tts.model.phase] }}</span>
      <strong v-if="tts.model.downloaded > 0 && tts.model.phase !== 'ready'">{{ percent }}%</strong>
    </div>
    <template v-if="tts.model.downloaded > 0 && tts.model.phase !== 'ready' || downloading">
      <progress :value="tts.model.downloaded" :max="tts.model.total" aria-label="模型下载进度"></progress>
      <p class="muted">{{ formatModelSize(tts.model.downloaded) }} / {{ formatModelSize(tts.model.total) }}<span v-if="tts.model.phase === 'verifying'"> · 检查文件完整性</span></p>
    </template>
    <p v-if="tts.model.downloaded > tts.model.total * 0.9 && tts.model.phase !== 'ready'" class="muted">升级只需补充下载，已有模型会保留。</p>
    <p v-if="tts.model.error" class="download-error" role="alert">{{ tts.model.error }}</p>
    <p v-if="!tts.supported" class="inline-message">在 Android App 中下载并使用本地听书。</p>
    <div v-if="tts.model.phase !== 'ready'" class="listen-download-source">
      <label for="model-download-source">下载来源</label>
      <select id="model-download-source" v-model="tts.mirror" :disabled="downloading" @change="ttsAction(saveTtsOptions)">
        <option :value="false">官方 Hugging Face</option><option :value="true">HF 国内镜像</option>
      </select>
    </div>
    <div class="download-actions">
      <button v-if="downloading" class="button secondary" :disabled="tts.busy" @click="ttsAction(pauseModelDownload)"><Pause :size="17" />暂停下载</button>
      <button v-else-if="tts.model.phase !== 'ready'" class="button" :disabled="!tts.supported || tts.busy || !tts.initialized" @click="ttsAction(downloadModel)">
        <Download :size="17" />{{ tts.model.downloaded ? '继续下载' : '下载听书模型' }}
      </button>
      <button v-if="!downloading && (tts.model.phase === 'ready' || tts.model.downloaded > 0)" class="text-button danger" :disabled="tts.busy" @click="deleting = true"><Trash2 :size="15" />删除模型</button>
    </div>
    <div v-if="deleting" class="listen-delete-confirm" role="alert">
      <p>删除模型和声音缓存？正在播放的听书会停止，小说正文会保留。</p>
      <button class="button destructive compact" :disabled="tts.busy" @click="ttsAction(async () => { await removeModel(); deleting = false; })">删除模型</button>
      <button class="text-button" :disabled="tts.busy" @click="deleting = false">取消</button>
    </div>
  </section>
  <section class="listen-voice-card">
    <div class="section-heading"><h2>声音与语速</h2><span class="muted">切换声音在下次开始听书时生效</span></div>
    <div class="listen-voices">
      <button v-for="voice in voices" :key="voice.id" :class="{ selected: tts.voice === voice.id }" @click="tts.voice = voice.id; ttsAction(saveTtsOptions)">
        <strong>{{ voice.name }}</strong><small>{{ voice.style }}</small>
      </button>
    </div>
    <div class="setting-row"><label for="listen-speed">播放速度</label><strong>{{ tts.speed.toFixed(2).replace(/0$/, '') }}×</strong></div>
    <input id="listen-speed" v-model.number="tts.speed" type="range" min="0.5" max="2" step="0.25" @change="ttsAction(saveTtsOptions)" />
    <div class="listen-download-source">
      <label for="listen-mode">播放准备方式</label>
      <select id="listen-mode" v-model="tts.mode" @change="ttsAction(saveTtsOptions)">
        <option value="stream">边生成边播放（默认）</option>
        <option value="chapter">先准备本章</option>
      </select>
    </div>
    <p class="muted">默认在首个语组生成后开始播放，后台继续生成后续内容。生成暂时跟不上时会等待下一段；先准备本章需要等待整章生成完成。设置在下次开始听书时生效。</p>
  </section>
  <p v-if="tts.error" class="download-error" role="alert">{{ tts.error }}</p>
</template>
