<template>
  <div class="page">
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

      <!-- 骨架屏：首屏数据没回来前先出形状，避免"空一下 → 整块蹦出来" -->
      <div v-if="loading && jobs.length === 0" class="jobs" aria-hidden="true">
        <div v-for="n in 2" :key="n" class="job card sk">
          <div class="job-main">
            <div class="sk-line w45"></div>
            <div class="sk-line w65 thin"></div>
            <div class="sk-line w30 thin"></div>
          </div>
        </div>
      </div>

      <div v-else-if="jobs.length === 0" class="empty">还没有任务，贴个链接开始吧</div>

      <div v-else class="jobs fade-in">
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
            <!-- 已结束的任务给个删除，否则失败记录会一直堆在列表里 -->
            <button v-if="['DONE','FAILED','CANCELED'].includes(j.status)"
                    class="btn btn-sm" @click="remove(j)">删除</button>
          </div>
        </div>
      </div>
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
/// 首屏是否还在取数据：只在"第一次且列表为空"时用骨架屏，
/// 后续轮询刷新不能把已有列表打回骨架（否则每 3 秒闪一次）
const loading = ref(true)
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
  } catch (e) { /* 忽略 */ } finally {
    // 只置一次 false：后面轮询刷新不再回到骨架态
    loading.value = false
  }
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

async function remove(j) {
  if (!confirm(`删除任务「${j.filename}」的记录？已下载的文件不受影响`)) return
  try { await api.deleteRemote(j.id); await load() } catch (e) { alert(e.message) }
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

.head h2 { margin: 0; font-size: 21px; }
.head p { margin: 7px 0 26px; font-size: 14px; }

.panel { padding: 22px 24px; margin-bottom: 30px; }
.row { display: flex; gap: 10px; }
.grow { flex: 1; }
.sel { width: 108px; flex-shrink: 0; }
.hint { font-size: 13.5px; margin-top: 13px; line-height: 1.6; }
.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
       padding: 9px 12px; border-radius: 9px; font-size: 13.5px; margin-top: 12px; }

.list-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; margin-top: 4px; }
.list-head h3 { margin: 0; font-size: 16.5px; }

.jobs { display: flex; flex-direction: column; gap: 10px; }
.job { display: flex; align-items: center; gap: 16px; padding: 17px 20px; }
.job-main { flex: 1; min-width: 0; }
.job-name { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.job-meta { display: flex; align-items: center; gap: 9px; font-size: 12.5px; margin-top: 6px; flex-wrap: wrap; }
.job-bar { height: 4px; background: #e6e8f0; border-radius: 3px; margin-top: 9px; overflow: hidden; }
.job-in { height: 100%; background: linear-gradient(90deg, var(--brand), #8b9bff); transition: .3s; }
.job-err { font-size: 12.5px; color: var(--danger); margin-top: 7px; }
.job-ops { display: flex; gap: 6px; flex-shrink: 0; }

/* ---- 骨架屏 ----
   盒模型和真实任务行一致，数据回来后原地填满。 */
.sk { pointer-events: none; }
.sk-line {
  height: 12px; border-radius: 5px;
  background: linear-gradient(90deg, #eef1f7 25%, #f6f8fc 37%, #eef1f7 63%);
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease-in-out infinite;
}
.sk-line.w45 { width: 45%; }
.sk-line.w65 { width: 65%; }
.sk-line.w30 { width: 30%; }
.sk-line.thin { height: 9px; margin-top: 9px; }
@keyframes sk-shimmer {
  0%   { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 数据就位后整组轻轻淡入 */
.fade-in { animation: content-in .26s cubic-bezier(.25,.55,.4,.95) both; }
@keyframes content-in {
  from { opacity: .55; transform: translateY(4px); }
  to   { opacity: 1;   transform: none; }
}

@media (prefers-reduced-motion: reduce) {
  .sk-line { animation: none; }
  .fade-in { animation: none; }
}
</style>
