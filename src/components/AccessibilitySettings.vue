<script setup lang="ts">
import { ref } from "vue";
import { accessibility, enableAccessibility, configureRecognition } from "../services/accessibility";
import { tts } from "../services/tts";
async function enable() { await enableAccessibility().catch(() => {}); }
const configuring = ref(false), microphoneMessage = ref("");
async function configure() {
  configuring.value = true;
  try { microphoneMessage.value = await configureRecognition() ? "麦克风权限已允许，手机具备语音识别服务。建议协助者试一次中文搜索。" : "麦克风权限已允许，但手机没有语音识别服务，请先配置服务。"; }
  catch (error) { microphoneMessage.value = (error as Error).message; }
  finally { configuring.value = false; }
}
</script>
<template>
  <section class="accessibility-settings">
    <h2>语音无障碍模式</h2>
    <p>由协助者完成首次设置。开启后使用超大按钮和语音选择书籍、查看最近读听、搜索与听书，无需开启 TalkBack。</p>
    <p>必须先下载语音模型；每次进入模式会加载模型，操作提示和正文共用本地模型。首次生成提示可能需要等待，重复提示会使用缓存。</p>
    <button class="button secondary" :disabled="configuring || accessibility.preparing" @click="configure">{{ configuring ? '正在配置…' : '先配置语音搜索的麦克风权限' }}</button>
    <p v-if="microphoneMessage" role="status">{{ microphoneMessage }}</p>
    <p v-if="accessibility.error" role="alert">{{ accessibility.error }}</p>
    <button class="button" :disabled="accessibility.preparing || !tts.initialized" @click="enable">
      {{ accessibility.preparing ? '正在加载语音模型…' : '加载模型并开启无障碍模式' }}
    </button>
  </section>
</template>
