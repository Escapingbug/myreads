<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { state } from "../services/library";
import { loadRecommendations, type Recommendation } from "../services/recommendations";
const emit = defineEmits<{ choose: [entry: Recommendation] }>();
const items = ref<Recommendation[]>([]), start = ref(0), busy = ref(false), error = ref(""), source = ref("");
let controller: AbortController | undefined;
async function load(refresh = false) {
  controller?.abort(); const current = new AbortController(); controller = current;
  busy.value = true; error.value = "";
  try {
    const list = await loadRecommendations(state.sources, current.signal, refresh);
    if (current.signal.aborted) return;
    items.value = list.items; start.value = 0;
    source.value = `${list.sourceName}${list.cached ? " · 已保存的推荐" : ""}`;
  } catch (caught) { if (!current.signal.aborted) error.value = (caught as Error).message; }
  finally { if (controller === current) busy.value = false; }
}
onMounted(() => { void load(); });
onBeforeUnmount(() => controller?.abort());
</script>
<template>
  <section class="recommended-books" aria-label="推荐书籍">
    <div class="section-heading"><h2>随便看看，听个故事</h2><button class="text-button" :disabled="busy" @click="items.length > 4 ? start = (start + 4) % items.length : load(true)">{{ items.length > 4 ? '换一批' : '刷新推荐' }}</button></div>
    <p class="muted">{{ busy ? '正在寻找推荐书籍…' : source }}</p>
    <div v-if="error" role="status"><p>{{ error }}</p><button class="text-button" :disabled="busy" @click="load(true)">重试推荐</button></div>
    <div class="recommendation-grid">
      <button v-for="entry in items.slice(start, start + 4)" :key="`${entry.sourceId}:${entry.book.id}`" class="recommendation-card" @click="emit('choose', entry)">
        <strong>{{ entry.book.title }}</strong><span>{{ entry.book.author || '作者未知' }}</span><small>{{ entry.book.description || '查看书籍介绍与章节' }}</small>
      </button>
    </div>
  </section>
</template>
