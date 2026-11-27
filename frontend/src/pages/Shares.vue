<template>
  <div class="page">
      <h2>我的分享</h2>
      <p class="muted sub">这里列出你创建过的所有分享链接</p>

      <!-- 骨架屏：数据没回来前先给「形状」，而不是白屏或"加载中…"字样。
           这样内容进来时是原地填满，不会出现"整块突然出现"的跳变。 -->
      <div v-if="loading" class="rows" aria-hidden="true">
        <div v-for="n in 3" :key="n" class="row card sk">
          <div class="row-main">
            <div class="sk-line w40"></div>
            <div class="sk-line w70 thin"></div>
          </div>
          <div class="row-ops">
            <div class="sk-btn"></div><div class="sk-btn"></div>
          </div>
        </div>
      </div>

      <div v-else-if="list.length === 0" class="empty">还没有分享，去文件列表点「分享」</div>

      <div v-else class="rows fade-in">
        <div v-for="s in list" :key="s.id" class="row card">
          <div class="row-main">
            <div class="row-link">/s/{{ s.id }}</div>
            <div class="row-meta muted">
              <span v-if="s.hasPassword">🔒 有提取码</span>
              <span v-else>公开</span>
              <span v-if="s.expiresAt">· 到期 {{ fmt(s.expiresAt) }}</span>
              <span v-else>· 永久</span>
              <span>· 下载 {{ s.downloadCount }} 次</span>
            </div>
          </div>
          <div class="row-ops">
            <button class="btn btn-sm" @click="copyLink(s)">复制</button>
            <button class="btn btn-sm btn-danger" @click="revoke(s)">取消分享</button>
          </div>
        </div>
      </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const list = ref([])
const loading = ref(false)
const me = ref(null)

function fmt(iso) {
  return new Date(iso).toLocaleString('zh-CN')
}

async function load() {
  loading.value = true
  try { list.value = await api.myShares() } catch (e) {} finally { loading.value = false }
}

async function loadMe() {
  try { me.value = await api.me() } catch (e) {}
}

function copyLink(s) {
  navigator.clipboard.writeText(`${location.origin}/s/${s.id}`).then(() => alert('已复制'))
}

async function revoke(s) {
  if (!confirm('取消这条分享？对方将无法再访问')) return
  try { await api.revokeShare(s.id); await load() } catch (e) { alert(e.message) }
}

onMounted(() => {
  // 并行拉取：侧栏配额和列表互不依赖，串行会让骨架屏多停一会儿
  load()
  loadMe()
})
</script>

<style scoped>
/* 布局骨架（.shell/.side/.main）已经搬到 AppShell 里，
   这里只留「内容区」自己的样式，避免三份重复定义互相打架。 */
.page { padding: 26px 30px; }
.page h2 { margin: 0; font-size: 20px; }
.sub { margin: 5px 0 22px; font-size: 13px; }

.rows { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 14px; padding: 14px 18px; }
.row-main { flex: 1; min-width: 0; }
.row-link { font-size: 13.5px; font-weight: 600; font-family: ui-monospace, monospace; }
.row-meta { font-size: 11.5px; margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.row-ops { display: flex; gap: 6px; }

/* ---- 骨架屏 ----
   用和真实行完全相同的盒模型（同样的 padding / gap / 高度），
   数据回来后是"原地填满"，不会因为尺寸变化抖一下。 */
.sk { pointer-events: none; }
.sk-line, .sk-btn {
  background: linear-gradient(90deg, #eef1f7 25%, #f6f8fc 37%, #eef1f7 63%);
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease-in-out infinite;
  border-radius: 5px;
}
.sk-line { height: 12px; }
.sk-line.w40 { width: 40%; }
.sk-line.w70 { width: 70%; }
.sk-line.thin { height: 9px; margin-top: 9px; }
.sk-btn { width: 56px; height: 28px; border-radius: 7px; flex-shrink: 0; }
@keyframes sk-shimmer {
  0%   { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 数据就位后整组轻轻淡入，取代"突然出现" */
.fade-in { animation: content-in .26s cubic-bezier(.25,.55,.4,.95) both; }
@keyframes content-in {
  from { opacity: .55; transform: translateY(4px); }
  to   { opacity: 1;   transform: none; }
}

@media (prefers-reduced-motion: reduce) {
  .sk-line, .sk-btn { animation: none; }
  .fade-in { animation: none; }
}
</style>
