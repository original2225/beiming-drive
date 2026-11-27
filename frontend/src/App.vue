<template>
  <!-- 全局外壳在这里，只此一份。
       这样「我的文件 / 我的分享 / 专属下载器 / 回收站」四处切换时，
       侧栏是同一个 DOM 节点，从头到尾不重建、不重绘 —— 一帧都不动。

       登录页 / 公开分享页不要外壳（它们没有侧栏），所以按路由 meta 分流。 -->
  <template v-if="plain">
    <router-view />
  </template>
  <AppShell v-else-if="loggedIn" @rail-search="onRailSearch">
    <!-- 内容区过渡在 AppShell 内部完成，见 AppShell.vue -->
  </AppShell>
  <template v-else>
    <router-view />
  </template>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from './components/AppShell.vue'
import { getToken } from './api.js'

const route = useRoute()
const router = useRouter()

/// 登录页、公开分享页是「无外壳」页面
const plain = computed(() => !!route.meta.public)

/// 竖栏是全局的，搜索却依赖具体页面。
/// 规则：无论当前在哪一页，输入搜索词后统一回「我的文件」执行 ——
/// 那才是能展示搜索结果的地方；停在分享页搜文件没有意义。
function onRailSearch(kw) {
  const q = String(kw || '').trim()
  if (!q) return
  if (route.path === '/') {
    window.dispatchEvent(new CustomEvent('drive:search', { detail: q }))
  } else {
    router.push({ path: '/', query: { q } })
  }
}
const loggedIn = computed(() => !!getToken())
</script>
