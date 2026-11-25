<template>
  <div class="shell">
    <aside class="side">
      <div class="side-brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
      </div>
      <nav class="side-nav">
        <div class="nav-item" @click="$router.push('/')"><i class="ic">🗂</i><span>我的文件</span></div>
        <div class="nav-item on"><i class="ic">🔗</i><span>我的分享</span></div>
        <div class="nav-item" @click="$router.push('/downloader')"><i class="ic">⤓</i><span>专属下载器</span></div>
      </nav>
    </aside>

    <main class="main">
      <h2>我的分享</h2>
      <p class="muted sub">这里列出你创建过的所有分享链接</p>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="list.length === 0" class="empty">还没有分享，去文件列表点「分享」</div>

      <div v-else class="rows">
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
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const list = ref([])
const loading = ref(false)

function fmt(iso) {
  return new Date(iso).toLocaleString('zh-CN')
}

async function load() {
  loading.value = true
  try { list.value = await api.myShares() } catch (e) {} finally { loading.value = false }
}

function copyLink(s) {
  navigator.clipboard.writeText(`${location.origin}/s/${s.id}`).then(() => alert('已复制'))
}

async function revoke(s) {
  if (!confirm('取消这条分享？对方将无法再访问')) return
  try { await api.revokeShare(s.id); await load() } catch (e) { alert(e.message) }
}

onMounted(load)
</script>

<style scoped>
.shell { display: flex; height: 100vh; overflow: hidden; }
.side { width: 232px; flex-shrink: 0; background: #fff; border-right: 1px solid var(--line);
        display: flex; flex-direction: column; padding: 18px 14px; }
.side-brand { display: flex; align-items: center; gap: 9px; padding: 2px 6px 20px; font-weight: 700; font-size: 15.5px; }
.side-brand img { width: 30px; height: 30px; border-radius: 50%; }
.side-nav { display: flex; flex-direction: column; gap: 3px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 9px 11px; border-radius: 9px;
            font-size: 13.5px; color: var(--ink-2); cursor: pointer; }
.nav-item:hover { background: #f6f7fc; }
.nav-item.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.ic { font-style: normal; font-size: 15px; width: 18px; text-align: center; }

.main { flex: 1; padding: 26px 30px; overflow-y: auto; }
.main h2 { margin: 0; font-size: 20px; }
.sub { margin: 5px 0 22px; font-size: 13px; }

.rows { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: center; gap: 14px; padding: 14px 18px; }
.row-main { flex: 1; min-width: 0; }
.row-link { font-size: 13.5px; font-weight: 600; font-family: ui-monospace, monospace; }
.row-meta { font-size: 11.5px; margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.row-ops { display: flex; gap: 6px; }
</style>