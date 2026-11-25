<template>
  <div class="shell">
    <aside class="side">
      <div class="side-brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
      </div>
      <nav class="side-nav">
        <div class="nav-item" @click="$router.push('/')"><i class="ic">🗂</i><span>我的文件</span></div>
        <div class="nav-item" @click="$router.push('/shares')"><i class="ic">🔗</i><span>我的分享</span></div>
        <div class="nav-item on"><i class="ic">⤓</i><span>专属下载器</span></div>
      </nav>
      <div class="side-foot">
        <div class="side-user">
          <div class="avatar">{{ (me?.nickname || 'U').slice(0, 1) }}</div>
          <div class="who">
            <div class="name">{{ me?.nickname }}</div>
            <div class="mail muted">{{ me?.email }}</div>
          </div>
        </div>
      </div>
    </aside>

    <main class="main">
      <div class="head">
        <div>
          <h2>专属下载器</h2>
          <p class="muted">服务端多线程分段 · 断点续传 · 直连入盘</p>
        </div>
      </div>

      <div class="card panel">
        <div class="row">
          <input class="input grow" v-model="url" placeholder="粘贴下载直链 (http/https)" />
          <select class="input sel" v-model.number="workers">
            <option :value="4">4 线程</option>
            <option :value="8">8 线程</option>
            <option :value="16">16 线程</option>
            <option :value="24">24 线程</option>
            <option :value="32">32 线程</option>
          </select>
          <button class="btn btn-primary" :disabled="busy || !url" @click="create">
            <span v-if="busy" class="spinner"></span>
            <span>{{ busy ? '创建中' : '开始下载' }}</span>
          </button>
        </div>
        <div class="hint muted">
          服务器支持 Range 时按 8MB 切片并发；不支持则退化为单线程。文件直接落进网盘。
        </div>
        <div v-if="err" class="err">{{ err }}</div>
      </div>

      <div class="list-head">
        <h3>任务列表</h3>
        <button class="btn btn-sm" @click="load">刷新</button>
      </div>

      <div v-if="jobs.length === 0" class="empty">还没有任务，贴个链接开始吧</div>

      <div v-else class="jobs">
        <div v-for="j in jobs" :key="j.id" class="job card">
          <div class="job-main">
            <div class="job-name">{{ j.filename }}</div>
            <div class="job-meta muted">
              <span :class="tagClass(j.status)">{{ statusText(j.status) }}</span>
              <span v-if="j.totalSize">{{ sizeText(j.doneBytes) }} / {{ sizeText(j.totalSize) }}</span>
              <span v-if="j.chunksTotal">· {{ j.chunksDone }}/{{ j.chunksTotal }} 片 · {{ j.workers }} 线程</span>
            </div>
            <div class="job-bar" v-if="j.status === 'RUNNING'">
              <div class="job-in" :style="{ width: pctOf(j) + '%' }"></div>
            </div>
            <div v-if="j.error" class="job-err">{{ j.error }}</div>
          </div>
          <div class="job-ops">
            <button v-if="j.fileNodeId" class="btn btn-sm" @click="openFile(j)">查看</button>
            <button v-if="j.status === 'RUNNING' || j.status === 'PENDING'"
                    class="btn btn-sm btn-danger" @click="cancel(j)">取消</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const router = useRouter()
const me = ref(null)
const url = ref('')
const workers = ref(8)
const busy = ref(false)
const err = ref('')
const jobs = ref([])
let timer = null

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B','KB','MB','GB','TB']; let i=0,n=b
  while (n >= 1024 && i < u.length-1) { n/=1024; i++ }
  return `${n.toFixed(n>=100||i===0?0:1)} ${u[i]}`
}
function pctOf(j) {
  if (!j.totalSize) return 0
  return Math.min(100, (j.doneBytes / j.totalSize) * 100).toFixed(1)
}
function statusText(s) {
  return { PENDING:'排队中', RUNNING:'下载中', DONE:'完成', FAILED:'失败', CANCELED:'已取消' }[s] || s
}
function tagClass(s) {
  return { PENDING:'tag tag-idle', RUNNING:'tag tag-run', DONE:'tag tag-ok',
           FAILED:'tag tag-fail', CANCELED:'tag tag-warn' }[s] || 'tag'
}

async function load() {
  try {
    jobs.value = await api.remoteJobs()
    me.value = await api.me()
  } catch (e) { /* 忽略 */ }
}

async function create() {
  err.value = ''
  busy.value = true
  try {
    await api.createRemote(url.value.trim(), null, null, workers.value)
    url.value = ''
    await load()
  } catch (e) {
    err.value = e.message
  } finally {
    busy.value = false
  }
}

async function cancel(j) {
  try { await api.cancelRemote(j.id); await load() } catch (e) { alert(e.message) }
}

function openFile(j) {
  router.push('/')
}

onMounted(() => {
  load()
  timer = setInterval(load, 1500)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
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
.side-foot { margin-top: auto; }
.side-user { display: flex; align-items: center; gap: 9px; padding: 8px 4px; }
.avatar { width: 34px; height: 34px; border-radius: 50%; background: linear-gradient(135deg, var(--brand), #8b5cf6);
          color: #fff; display: grid; place-items: center; font-weight: 600; font-size: 14px; }
.who { flex: 1; min-width: 0; }
.name { font-size: 13px; font-weight: 600; }
.mail { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.main { flex: 1; padding: 26px 30px; overflow-y: auto; }
.head h2 { margin: 0; font-size: 20px; }
.head p { margin: 5px 0 22px; font-size: 13px; }

.panel { padding: 18px 20px; margin-bottom: 26px; }
.row { display: flex; gap: 10px; }
.grow { flex: 1; }
.sel { width: 108px; flex-shrink: 0; }
.hint { font-size: 12px; margin-top: 11px; }
.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
       padding: 9px 12px; border-radius: 9px; font-size: 12.5px; margin-top: 12px; }

.list-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.list-head h3 { margin: 0; font-size: 15px; }

.jobs { display: flex; flex-direction: column; gap: 10px; }
.job { display: flex; align-items: center; gap: 14px; padding: 14px 18px; }
.job-main { flex: 1; min-width: 0; }
.job-name { font-size: 13.5px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.job-meta { display: flex; align-items: center; gap: 9px; font-size: 11.5px; margin-top: 6px; flex-wrap: wrap; }
.job-bar { height: 4px; background: #e6e8f0; border-radius: 3px; margin-top: 9px; overflow: hidden; }
.job-in { height: 100%; background: linear-gradient(90deg, var(--brand), #8b9bff); transition: .3s; }
.job-err { font-size: 11.5px; color: var(--danger); margin-top: 7px; }
.job-ops { display: flex; gap: 6px; flex-shrink: 0; }
</style>