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

      <!-- 空状态也做成一块有形的卡片，而不是一行字飘在空白里：
       上面全是圆角白卡片，这里突然什么都没有，整页看着像没加载完。
       虚线边框沿用「这里可以放东西」的语义，不抢主视线。 -->
      <div v-else-if="jobs.length === 0" class="empty card">
        <div class="empty-icon">⤓</div>
        <p class="empty-title">还没有任务</p>
        <p class="empty-sub muted">贴个链接，开始把它搬进网盘</p>
      </div>

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

/* 之前这一页压根没给 .page 定内边距和宽度上限，
   于是标题、输入框、任务卡片全都顶着窗口边缘，横向拉满 ——
   和「我的文件 / 存储空间」那几页明显不是一套。
   这里补齐，并限宽：一行文字超过 760px 就要来回扫，读着累。 */
.page {
  flex: 1; display: flex; flex-direction: column;
  overflow-y: auto; padding: 30px 34px 48px; min-height: 0;
}
.page > * { max-width: 760px; width: 100%; }

/* 不做宽屏居中：后台界面左对齐才是惯例，且其余四页都是左对齐限宽，
   只有这一页居中会和它们对不上。右侧留白是限宽的自然结果，不是缺陷。 */

.head h2 { margin: 0; font-size: 21px; }
.head p { margin: 7px 0 26px; font-size: 14px; }

.panel { padding: 22px 24px; margin-bottom: 30px; }
.row { display: flex; gap: 10px; }
.grow { flex: 1; }
.sel { width: 108px; flex-shrink: 0; }
.hint { font-size: 13.5px; margin-top: 13px; line-height: 1.6; }
.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
       padding: 9px 12px; border-radius: 9px; font-size: 13.5px; margin-top: 12px; }

.list-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px; margin-top: 4px;
  /* 和卡片内部同一条右边线：「刷新」原本顶在 760px 边界上，
     而卡片里的按钮有 24px 内边距，右边会错开一截。 */
  padding: 0 24px;
}
.list-head h3 { margin: 0; font-size: 16.5px; }

.jobs { display: flex; flex-direction: column; gap: 10px; }
.job { display: flex; align-items: center; gap: 16px; padding: 18px 24px; }
.job-main { flex: 1; min-width: 0; }
.job-name { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.job-meta { display: flex; align-items: center; gap: 9px; font-size: 12.5px; margin-top: 6px; flex-wrap: wrap; }
.job-bar { height: 4px; background: #e6e8f0; border-radius: 3px; margin-top: 9px; overflow: hidden; }
.job-in { height: 100%; background: linear-gradient(90deg, var(--brand), #8b9bff); transition: .3s; }
.job-err { font-size: 12.5px; color: var(--danger); margin-top: 7px; }
.job-ops { display: flex; gap: 6px; flex-shrink: 0; }

/* ---- 空状态 ----
   和任务卡片同一套圆角白底，这样「没有任务」和「有任务」是同一个视觉层级，
   而不是一块卡片区突然变成一行灰字。虚线是因为它装的是「这里可以放东西」，
   用虚线比实线更贴切，也不至于跟真实任务卡片抢注意力。 */
.empty {
  display: flex; flex-direction: column; align-items: center;
  text-align: center; padding: 52px 24px 46px;
  /* .card 只给了背景和圆角、没有边框，所以这里要连宽度一起给，
     只写 border-style 是画不出来的 */
  border: 1px dashed #cdd3e4; background: transparent;
  box-shadow: none;
}
/* 图标和文字都不能太淡：空状态是这页唯一的内容，
   糊成一团灰等于告诉用户「这页没做完」。 */
.empty-icon { font-size: 32px; color: #9aa3bd; opacity: 1; line-height: 1; margin-bottom: 12px; }
.empty-title { margin: 0; font-size: 15.5px; font-weight: 600; color: var(--ink); }
.empty-sub { margin: 6px 0 0; font-size: 13.5px; color: var(--ink-2); }

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
