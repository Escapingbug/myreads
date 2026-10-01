<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{
  title: string;
  author?: string;
  small?: boolean;
}>();
const palette = computed(() => {
  const colors = ["sage", "umber", "ink", "rose", "sand"];
  return colors[
    [...props.title].reduce((n, c) => n + c.charCodeAt(0), 0) % colors.length
  ];
});
</script>
<template>
  <div class="book-cover" :class="[palette, { small }]" aria-hidden="true">
    <div class="cover-border"></div>
    <span class="cover-edition">纸 间 藏 书</span>
    <span class="cover-title">{{ title }}</span
    ><span class="cover-author">{{ author }}</span
    ><span class="cover-mark">纸间</span>
  </div>
</template>
