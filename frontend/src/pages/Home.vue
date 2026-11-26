<template>
  <div class="shell">
    <!-- ===== 侧栏 ===== -->
    <aside class="side">
      <div class="side-brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
      </div>

      <button class="btn btn-primary new-btn" @click="pickUpload">
        <span class="plus">+</span> 上传文件
      </button>

      <nav class="side-nav">
        <div class="nav-item" :class="{ on: view === 'files' }" @click="goHome">
          <i class="ic">🗂</i><span>我的文件</span>
        </div>
        <div class="nav-item" @click="$router.push('/shares')">
          <i class="ic">🔗</i><span>我的分享</span>
        </div>
        <div class="nav-item" @click="$router.push('/downloader')">
          <i class="ic">⤓</i><span>专属下载器</span>
        </div>
        <div class="nav-item" :class="{ on: view === 'trash' }" @click="openTrash">
          <i class="ic">🗑</i><span>回收站</span>
        </div>
      </nav>

      <div class="side-foot">
        <div class="quota">
          <div class="quota-top"><span class="muted">存储空间</span></div>
          <div class="bar"><div class="bar-in" :style="{ width: barPct + '%' }"></div></div>
          <div class="quota-num">{{ usedText }} / {{ quotaText }} · {{ pct }}%</div>
        </div>
        <div class="side-user">
          <div class="avatar">{{ (me?.nickname || 'U').slice(0, 1) }}</div>
          <div class="who">
            <div class="name">{{ me?.nickname }}</div>
            <div class="mail muted" :title="me?.email">{{ me?.email }}</div>
          </div>
          <button class="icon-btn" title="退出" @click="logout">⏻</button>
        </div>
      </div>
    </aside>

    <!-- ===== 主区 ===== -->
    <main class="main">
      <header class="topbar">
        <div class="crumbs">
          <template v-if="view === 'files'">
            <span class="crumb" :class="{ cur: trail.length === 0 }" @click="goHome">我的文件</span>
            <template v-for="(c, i) in trail" :key="c.id">
              <span class="sep">/</span>
              <span class="crumb" :class="{ cur: i === trail.length - 1 }"
                    @click="jumpTo(i)">{{ c.name }}</span>
            </template>
          </template>
          <span v-else class="crumb cur">回收站</span>
        </div>

        <div class="top-actions">
          <div class="search-box">
            <span class="search-ic">⌕</span>
            <input v-model="keyword" placeholder="搜索全部文件…" @keyup.enter="doSearch" />
            <button v-if="keyword" class="clear" @click="clearSearch">×</button>
          </div>
          <button class="icon-btn" title="刷新" @click="refresh">↻</button>
          <button class="btn btn-sm" @click="newFolder">新建文件夹</button>
        </div>
      </header>

      <div class="bar-info">
        <span class="muted">
          <template v-if="searching">搜索「{{ searchKw }}」命中 {{ items.length }} 项</template>
          <template v-else-if="view === 'trash'">回收站 {{ items.length }} 项</template>
          <template v-else>{{ items.length }} 项</template>
        </span>
        <div class="right-side">
          <button v-if="searching" class="btn btn-sm" @click="clearSearch">返回目录</button>
        </div>
      </div>

      <div v-if="loading" class="empty-plain">加载中…</div>

      <div v-else-if="items.length === 0" class="empty">
        <div class="empty-icon">{{ view === 'trash' ? '🗑' : '📂' }}</div>
        <p v-if="view === 'trash'">回收站是空的</p>
        <p v-else-if="searching">没有匹配的文件</p>
        <p v-else>这里还空着，上传文件或新建文件夹</p>
      </div>

      <div v-else class="list" @contextmenu.self.prevent="openCtx(null, $event)">
        <div class="thead">
          <div class="th th-name-group" @click="setSort('name')">
            <span class="th-icon-inline"></span>
            <span>名称<i v-if="orderBy === 'name'">{{ dir === 'asc' ? '↑' : '↓' }}</i></span>
          </div>
          <div class="th th-path" v-if="searching">所在目录</div>
          <div class="th th-type" v-else>类型</div>
          <div class="th th-size" @click="setSort('size')">
            大小<i v-if="orderBy === 'size'">{{ dir === 'asc' ? '↑' : '↓' }}</i>
          </div>
          <div class="th th-time" @click="setSort('updatedAt')">
            修改时间<i v-if="orderBy === 'updatedAt'">{{ dir === 'asc' ? '↑' : '↓' }}</i>
          </div>
          <div class="th th-ops">操作</div>
        </div>
        <div v-for="it in items" :key="it.id" class="row" @click="onItemClick(it)"
             :class="{ 'ctx-target': ctxMenu && ctxMenu.node && ctxMenu.node.id === it.id }"
             @contextmenu.prevent.stop="openCtx(it, $event)">
          <div class="row-icon">{{ it.kind === 'dir' ? '📁' : fileIcon(it.name) }}</div>
          <div class="row-name" :title="it.name">{{ it.name }}</div>
          <div class="row-path muted" v-if="searching" :title="parentPathOf(it.path)">
            {{ parentPathOf(it.path) }}
          </div>
          <div class="row-type muted">{{ it.kind === 'dir' ? '文件夹' : extOf(it.name) }}</div>
          <div class="row-size muted">{{ it.kind === 'dir' ? '—' : sizeText(it.size) }}</div>
          <div class="row-time muted">{{ fmtDate(it.updatedAt) }}</div>
          <div class="row-ops" @click.stop>
            <template v-if="view === 'trash'">
              <button class="btn btn-sm" @click="restore(it)">恢复</button>
              <button class="btn btn-sm btn-danger" @click="purge(it)">彻底删除</button>
            </template>
            <template v-else>
              <button v-if="it.kind === 'file' && canPreview(it)" class="btn btn-sm" @click="preview(it)">预览</button>
              <button v-if="it.kind === 'file'" class="btn btn-sm" @click="download(it)">下载</button>
              <button class="btn btn-sm" @click="share(it)">分享</button>
              <div class="more-wrap">
                <button class="btn btn-sm icon-only" @click.stop="toggleMenu(it)">⋯</button>
                <div v-if="menuFor === it.id" class="menu" @click.stop>
                  <div v-if="it.kind === 'file' && canPreview(it)" class="menu-item"
                       @click="preview(it)">在线预览</div>
                  <div class="menu-item" @click="doRename(it)">重命名</div>
                  <div class="menu-item" @click="openMove(it)">移动到…</div>
                  <div class="menu-sep"></div>
                  <div class="menu-item danger" @click="remove(it)">移入回收站</div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div v-if="uploads.length" class="uploads">
        <div class="up-title">上传队列 <span class="muted">{{ uploads.length }}</span></div>
        <div v-for="u in uploads" :key="u.key" class="up-row">
          <div class="up-name" :title="u.name">{{ u.name }}</div>
          <div class="up-bar"><div class="up-in" :class="{ err: u.pct < 0 }"
                                  :style="{ width: Math.max(u.pct, 0) + '%' }"></div></div>
          <div class="up-pct">{{ u.pct < 0 ? '失败' : u.pct + '%' }}</div>
        </div>
      </div>
    </main>

    <input ref="fileInput" type="file" multiple hidden @change="onPick" />

    <!-- 右键菜单：定位到鼠标处，超出视口自动翻转 -->
    <div v-if="ctxMenu" ref="ctxEl" class="ctx-menu" :style="ctxStyle" @click.stop>
      <!-- 右键点在某个条目上：ctxMenu.node 有值 -->
      <template v-if="ctxMenu.node">
        <template v-if="view === 'trash'">
          <div class="ctx-item" @click="ctxDo(restore)">恢复</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item danger" @click="ctxDo(purge)">彻底删除</div>
        </template>
        <template v-else>
          <div v-if="ctxMenu.node.kind === 'dir'" class="ctx-item"
               @click="ctxDo(enterDir)">打开</div>
          <template v-else>
            <div v-if="canPreview(ctxMenu.node)" class="ctx-item" @click="ctxDo(preview)">在线预览</div>
            <div class="ctx-item" @click="ctxDo(download)">下载</div>
          </template>
          <div class="ctx-item" @click="ctxDo(share)">分享…</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item" @click="ctxDo(doRename)">重命名</div>
          <div class="ctx-item" @click="ctxDo(openMove)">移动到…</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item danger" @click="ctxDo(remove)">移入回收站</div>
        </template>
      </template>

      <!-- 空白处右键 -->
      <template v-else>
        <div class="ctx-item" @click="ctxDo(() => fileInput.click())">上传文件</div>
        <div class="ctx-item" @click="ctxDo(newFolder)">新建文件夹</div>
        <div class="ctx-sep"></div>
        <div class="ctx-item" @click="ctxDo(refresh)">刷新</div>
      </template>
      <div class="ctx-sep"></div>
      <div class="ctx-item" @click="ctxMenu = null">取消</div>
    </div>

    <!-- 轻提示：替代原生 alert（原生会阻塞页面） -->
    <transition name="toast">
      <div v-if="toast" class="toast" :class="toast.kind">{{ toast.text }}</div>
    </transition>

    <!-- 通用输入框（替代原生 prompt）：改名 / 新建文件夹共用 -->
    <div v-if="promptBox" class="mask" @click.self="closePrompt">
      <div class="modal modal-sm">
        <h3>{{ promptBox.title }}</h3>
        <p v-if="promptBox.hint" class="muted tip-line">{{ promptBox.hint }}</p>
        <input ref="promptInput" class="input" type="text" v-model="promptBox.value"
               :placeholder="promptBox.placeholder"
               @keydown.enter.prevent="doPrompt"
               @keydown.esc.prevent="closePrompt" />
        <p v-if="promptBox.err" class="err-line">{{ promptBox.err }}</p>
        <div class="modal-foot">
          <button class="btn" @click="closePrompt">取消</button>
          <button class="btn btn-primary" :disabled="promptBox.busy" @click="doPrompt">
            {{ promptBox.busy ? '处理中…' : promptBox.okText }}
          </button>
        </div>
      </div>
    </div>

    <!-- 通用确认框（替代原生 confirm） -->
    <div v-if="confirmBox" class="mask" @click.self="confirmBox = null">
      <div class="modal modal-sm">
        <h3>{{ confirmBox.title }}</h3>
        <p class="tip-line">{{ confirmBox.text }}</p>
        <div class="modal-foot">
          <button class="btn" @click="confirmBox = null">取消</button>
          <button class="btn" :class="confirmBox.danger ? 'btn-danger' : 'btn-primary'"
                  @click="runConfirm">{{ confirmBox.okText }}</button>
        </div>
      </div>
    </div>

    <div v-if="shareBox" class="mask" @click.self="shareBox = null">
      <div class="modal">
        <h3>分享「{{ shareBox.node.name }}」</h3>
        <label><span>提取码（留空则免密）</span>
          <input class="input" v-model="shareBox.password" placeholder="可选" />
        </label>
        <label><span>有效期（小时，留空永久）</span>
          <input class="input" v-model.number="shareBox.hours" type="number" placeholder="如 24" />
        </label>
        <div v-if="shareBox.result" class="share-out">
          <div class="link-row">
            <input class="input" :value="shareBox.result" readonly @focus="$event.target.select()" />
            <button class="btn btn-sm" @click="copy(shareBox.result)">复制</button>
          </div>
          <p class="muted tip">把这条链接发给对方就能下载</p>
        </div>
        <div class="modal-foot">
          <button class="btn" @click="shareBox = null">关闭</button>
          <button v-if="!shareBox.result" class="btn btn-primary" @click="doShare">创建分享</button>
        </div>
      </div>
    </div>

    <!-- 移动对话框 -->
    <div v-if="moveBox" class="mask" @click.self="moveBox = null">
      <div class="modal">
        <h3>移动「{{ moveBox.node.name }}」</h3>
        <p class="muted move-tip">选择目标目录</p>
        <div class="dir-picker">
          <div class="dp-crumb">
            <span class="dp-link" @click="dpGo(null)">我的文件</span>
            <template v-for="(c, i) in moveBox.trail" :key="c.id">
              <span class="dp-sep">/</span>
              <span class="dp-link" @click="dpJump(i)">{{ c.name }}</span>
            </template>
          </div>
          <div class="dp-list">
            <div v-if="moveBox.dirs.length === 0" class="dp-empty muted">这个目录下没有子文件夹</div>
            <div v-for="d in moveBox.dirs" :key="d.id" class="dp-item"
                 :class="{ disabled: moveBox.node.kind === 'dir' && d.path.startsWith(moveBox.node.path + '/') }"
                 @click="dpEnter(d)">
              <span class="dp-ic">📁</span>
              <span class="dp-nm">{{ d.name }}</span>
              <span class="dp-arrow" v-if="!moveBox.node.path.startsWith(d.path + '/')">›</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn" @click="moveBox = null">取消</button>
          <button class="btn btn-primary" @click="doMove">移动到这里</button>
        </div>
      </div>
    </div>

    <!-- 预览弹窗 -->
    <div v-if="previewBox" class="mask darker" @click.self="previewBox = null">
      <div class="preview-modal">
        <div class="pv-head">
          <div class="pv-name" :title="previewBox.node.name">{{ previewBox.node.name }}</div>
          <div class="pv-actions">
            <button class="btn btn-sm" @click="download(previewBox.node)">下载</button>
            <button class="btn btn-sm" @click="previewBox = null">关闭</button>
          </div>
        </div>
        <div class="pv-body">
          <img v-if="previewBox.kind === 'image'" :src="previewBox.url" :alt="previewBox.node.name" />
          <video v-else-if="previewBox.kind === 'video'" :src="previewBox.url" controls autoplay></video>
          <audio v-else-if="previewBox.kind === 'audio'" :src="previewBox.url" controls></audio>
          <iframe v-else-if="previewBox.kind === 'pdf'" :src="previewBox.url"></iframe>
          <pre v-else-if="previewBox.kind === 'text'">{{ previewBox.text }}</pre>
          <div v-else class="pv-unsupported muted">这种文件不支持在线预览，请下载后查看</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken, downloadUrl, previewUrl } from '../api.js'

const router = useRouter()
const CHUNK = 8 * 1024 * 1024

const me = ref(null)
const items = ref([])
const loading = ref(false)
const view = ref('files')
const currentId = ref(null)
const trail = ref([])
const uploads = ref([])
const shareBox = ref(null)
const moveBox = ref(null)
const previewBox = ref(null)
const menuFor = ref(null)
// 右键菜单：ctxMenu = { node, x, y }，node 为空表示在空白处右键
const ctxMenu = ref(null)
const ctxEl = ref(null)
const ctxStyle = ref({})
// 通用输入框：{ title, hint, value, placeholder, okText, busy, err, submit }
const promptBox = ref(null)
const promptInput = ref(null)
// 通用确认框：{ title, text, okText, danger, run }
const confirmBox = ref(null)
// 轻提示（替代 alert）
const toast = ref(null)
let toastTimer = 0
const fileInput = ref(null)

const keyword = ref('')
const searchKw = ref('')
const searching = ref(false)
const orderBy = ref('name')
const dir = ref('asc')
const sorts = [
  { key: 'name', label: '名称' },
  { key: 'size', label: '大小' },
  { key: 'updatedAt', label: '时间' },
]

const pct = computed(() => {
  const q = me.value?.storageQuota || 0
  if (!q) return '0.0'
  return ((me.value.storageUsed / q) * 100).toFixed(1)
})
const barPct = computed(() => {
  const p = (me.value?.storageUsed / me.value?.storageQuota) * 100 || 0
  return p > 0 ? Math.max(p, 1.2) : 0
})
const usedText = computed(() => sizeText(me.value?.storageUsed || 0))
const quotaText = computed(() => sizeText(me.value?.storageQuota || 0))

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, n = b
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}
function extOf(name) {
  const i = name.lastIndexOf('.')
  if (i < 0) return '文件'
  return name.slice(i + 1).toUpperCase()
}
function fileIcon(name) {
  const e = (name.split('.').pop() || '').toLowerCase()
  if (['md', 'txt'].includes(e)) return '📄'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(e)) return '🖼'
  if (['mp4', 'mkv', 'avi', 'mov'].includes(e)) return '🎬'
  if (['mp3', 'flac', 'wav', 'm4a'].includes(e)) return '🎵'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(e)) return '📦'
  if (e === 'pdf') return '📕'
  return '📄'
}
function parentPathOf(p) {
  const i = p.lastIndexOf('/')
  return i <= 0 ? '/' : p.slice(0, i)
}
function fmtDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  const diff = (Date.now() - d.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)} 天前`
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function loadMe() {
  try { me.value = await api.me() } catch (e) { /* 401 交给路由守卫 */ }
}

async function refresh() {
  if (view.value === 'trash') return openTrash()
  searching.value = false
  loading.value = true
  try {
    const d = await api.list(currentId.value, orderBy.value, dir.value)
    items.value = d.items || []
    await loadMe()
  } catch (e) {
    console.warn(e)
  } finally {
    loading.value = false
  }
}

async function openTrash() {
  view.value = 'trash'
  searching.value = false
  menuFor.value = null
  loading.value = true
  try {
    items.value = await api.trashList()
    await loadMe()
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function goHome() {
  view.value = 'files'
  currentId.value = null
  trail.value = []
  keyword.value = ''
  refresh()
}

function jumpTo(i) {
  const t = trail.value[i]
  trail.value = trail.value.slice(0, i + 1)
  currentId.value = t.id
  refresh()
}

function open(node) {
  view.value = 'files'
  currentId.value = node.id
  trail.value = [...trail.value, node]
  menuFor.value = null
  refresh()
}

function onItemClick(it) {
  if (menuFor.value) { menuFor.value = null; return }
  if (view.value === 'trash') return
  if (it.kind === 'dir') open(it)
}

// ---------- 右键菜单 ----------
async function openCtx(node, ev) {
  menuFor.value = null
  // 先记坐标，等 DOM 出来再量尺寸决定是否翻转到鼠标左上
  ctxMenu.value = { node, x: ev.clientX, y: ev.clientY }
  await nextTick()
  const el = ctxEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  let x = ev.clientX, y = ev.clientY
  // 贴边时翻到另一侧，否则菜单会出框看不见
  if (x + r.width > window.innerWidth - 8) x = Math.max(8, ev.clientX - r.width)
  if (y + r.height > window.innerHeight - 8) y = Math.max(8, ev.clientY - r.height)
  ctxStyle.value = { left: x + 'px', top: y + 'px' }
}

/** 点了菜单项：先关菜单，再执行动作。有 node 就传进去，没有就无参调用。 */
function ctxDo(fn) {
  const node = ctxMenu.value?.node
  ctxMenu.value = null
  if (node) fn(node)
  else fn()
}

function enterDir(it) { open(it) }

function closeCtx(e) {
  if (!ctxMenu.value) return
  // 点在菜单内部不关（交给 ctx-item 自己处理）
  if (e && e.target && e.target.closest && e.target.closest('.ctx-menu')) return
  ctxMenu.value = null
}

function setSort(key) {
  if (orderBy.value === key) dir.value = dir.value === 'asc' ? 'desc' : 'asc'
  else { orderBy.value = key; dir.value = 'asc' }
  refresh()
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) { refresh(); return }
  loading.value = true
  searching.value = true
  searchKw.value = q
  menuFor.value = null
  try {
    const d = await api.search(q)
    items.value = d.items || []
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function clearSearch() {
  keyword.value = ''
  searching.value = false
  refresh()
}

async function newFolder() {
  openPrompt({
    title: '新建文件夹',
    placeholder: '文件夹名称',
    okText: '创建',
    value: '',
    submit: async (name) => {
      await api.mkdir(currentId.value, name)
      await refresh()
    },
  })
}

/** 轻提示：2.6 秒后自动消失，不阻塞页面 */
function showToast(text, kind = 'info') {
  toast.value = { text, kind }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = null }, 2600)
}

// ---------- 通用输入框 / 确认框 ----------
function openPrompt(cfg) {
  promptBox.value = { hint: '', placeholder: '', okText: '确定', busy: false, err: '', ...cfg }
  nextTick(() => promptInput.value && promptInput.value.focus())
}

function closePrompt() {
  if (promptBox.value && promptBox.value.busy) return
  promptBox.value = null
}

async function doPrompt() {
  const b = promptBox.value
  if (!b || b.busy) return
  const v = (b.value || '').trim()
  // 名字类校验放在这里统一做，比每个调用点各写一遍靠谱
  if (!v) { b.err = '不能为空'; return }
  if (/[\\/:*?"<>|]/.test(v)) { b.err = '不能包含 / \\ : * ? " < > |'; return }
  if (v.length > 120) { b.err = '名字太长（最多 120 字）'; return }
  b.err = ''
  b.busy = true
  try {
    await b.submit(v)
    promptBox.value = null
  } catch (e) {
    b.err = e.message || '操作失败'
    b.busy = false
  }
}

/** 确认框：run 返回 Promise 就等它，抛错就提示 */
function askConfirm(cfg) { confirmBox.value = { okText: '确定', danger: false, ...cfg } }

async function runConfirm() {
  const c = confirmBox.value
  if (!c) return
  confirmBox.value = null
  try { await c.run() } catch (e) { showToast(e.message || '操作失败', 'err') }
}

function pickUpload() { fileInput.value?.click() }

async function onPick(ev) {
  const files = [...ev.target.files]
  ev.target.value = ''
  for (const f of files) await uploadOne(f)
}

async function uploadOne(file) {
  const key = file.name + '-' + Date.now()
  const rec = { key, name: file.name, pct: 0 }
  uploads.value.push(rec)
  try {
    if (file.size <= CHUNK) {
      await api.uploadSmall(currentId.value, file)
      rec.pct = 100
    } else {
      const total = Math.ceil(file.size / CHUNK)
      const sess = await api.createUpload(currentId.value, file.name, file.size, CHUNK)
      let finished = 0, next = 0
      const CONC = 3
      async function worker() {
        while (true) {
          const idx = next++
          if (idx >= total) return
          const start = idx * CHUNK
          const blob = file.slice(start, Math.min(start + CHUNK, file.size))
          await api.putChunk(sess.id, idx, blob)
          finished++
          rec.pct = Math.round((finished / total) * 100)
        }
      }
      await Promise.all(Array.from({ length: Math.min(CONC, total) }, worker))
      await api.completeUpload(sess.id)
      rec.pct = 100
    }
    if (view.value === 'files') await refresh()
  } catch (e) {
    rec.pct = -1
  } finally {
    setTimeout(() => { uploads.value = uploads.value.filter(u => u.key !== key) }, 1600)
  }
}

function download(it) { window.open(downloadUrl(it.id), '_blank') }

function toggleMenu(it) { menuFor.value = menuFor.value === it.id ? null : it.id }

async function doRename(it) {
  menuFor.value = null
  ctxMenu.value = null
  openPrompt({
    title: '重命名',
    hint: `原名称：${it.name}`,
    value: it.name,
    okText: '保存',
    submit: async (name) => {
      if (name === it.name) return
      await api.rename(it.id, name)
      await refresh()
    },
  })
}

async function remove(it) {
  menuFor.value = null
  ctxMenu.value = null
  askConfirm({
    title: '移入回收站',
    text: `把「${it.name}」移入回收站？之后可以在回收站里恢复。`,
    okText: '移入回收站',
    danger: true,
    run: async () => {
      await api.trash(it.id)
      await refresh()
    },
  })
}

// ---------- 移动 ----------
async function openMove(it) {
  menuFor.value = null
  // 从根开始选目标，避免一进来就困在当前目录里
  moveBox.value = { node: it, trail: [], dirs: [], targetId: null }
  await loadDirs(null)
}

async function loadDirs(parentId) {
  try {
    const d = await api.list(parentId, 'name', 'asc')
    moveBox.value.dirs = (d.items || []).filter(x => x.kind === 'dir')
    moveBox.value.targetId = parentId
  } catch (e) {
    moveBox.value.dirs = []
  }
}

async function dpEnter(d) {
  // 不许走进自身的子树，否则移动会成环
  const n = moveBox.value.node
  if (n.kind === 'dir' && d.path.startsWith(n.path + '/')) return
  moveBox.value.trail = [...moveBox.value.trail, d]
  await loadDirs(d.id)
}

async function dpGo() {
  moveBox.value.trail = []
  await loadDirs(null)
}

function dpJump(i) {
  const t = moveBox.value.trail[i]
  moveBox.value.trail = moveBox.value.trail.slice(0, i + 1)
  loadDirs(t.id)
}

async function doMove() {
  const box = moveBox.value
  try {
    await api.move(box.node.id, box.targetId)
    moveBox.value = null
    await refresh()
  } catch (e) { showToast(e.message, 'err') }
}

// ---------- 预览 ----------
function previewKind(name, mime) {
  const e = (name.split('.').pop() || '').toLowerCase()
  const m = (mime || '').toLowerCase()
  if (m.startsWith('image/') || ['png','jpg','jpeg','gif','webp','bmp'].includes(e)) return 'image'
  if (m.startsWith('video/') || ['mp4','webm','mov','mkv'].includes(e)) return 'video'
  if (m.startsWith('audio/') || ['mp3','wav','flac','m4a','ogg'].includes(e)) return 'audio'
  if (m === 'application/pdf' || e === 'pdf') return 'pdf'
  if (m.startsWith('text/') || ['md','txt','json','log','csv','py','js','css'].includes(e)) return 'text'
  return null
}

function canPreview(it) { return it.kind === 'file' && previewKind(it.name, it.mime) != null }

async function preview(it) {
  menuFor.value = null
  const kind = previewKind(it.name, it.mime)
  if (!kind) { showToast('这种文件不支持在线预览', 'err'); return }
  const url = previewUrl(it.id)

  if (kind === 'text') {
    // 文本自己 read 回来渲染，别丢进 iframe 显示原始字节
    try {
      const r = await fetch(url)
      if (!r.ok) throw new Error('HTTP ' + r.status)
      const text = await r.text()
      const MAX = 400 * 1024  // 超大文本截断，防止拖死浏览器
      previewBox.value = { node: it, kind, url,
        text: text.length > MAX
          ? text.slice(0, MAX) + `\n\n…（已截断，全文 ${text.length} 字符，请下载查看）`
          : text }
    } catch (e) { showToast('读取失败：' + e.message, 'err') }
    return
  }
  previewBox.value = { node: it, kind, url }
}

async function restore(it) {
  ctxMenu.value = null
  try { await api.restore(it.id); await openTrash(); showToast('已恢复') }
  catch (e) { showToast(e.message, 'err') }
}

function purge(it) {
  ctxMenu.value = null
  askConfirm({
    title: '彻底删除',
    text: `「${it.name}」将被永久删除，无法恢复。确定吗？`,
    okText: '彻底删除',
    danger: true,
    run: async () => {
      await api.purge(it.id)
      await openTrash()
    },
  })
}

function share(it) {
  shareBox.value = { node: it, password: '', hours: null, result: '' }
}
async function doShare() {
  const s = shareBox.value
  try {
    const r = await api.createShare(s.node.id, s.password || null, s.hours || null, null)
    s.result = `${location.origin}/s/${r.share.id}`
  } catch (e) { showToast(e.message, 'err') }
}
function copy(txt) { navigator.clipboard.writeText(txt).then(() => showToast('已复制')) }

async function logout() {
  try { await api.logout() } catch (e) {}
  setToken('')
  router.push('/login')
}

function closeMenu(e) {
  if (!e.target.closest || !e.target.closest('.more-wrap')) menuFor.value = null
}

/** Esc 逐层关闭：右键菜单 → ⋯ 菜单 → 预览 → 移动框 */
function onKey(e) {
  if (e.key !== 'Escape') return
  if (promptBox.value) { closePrompt(); return }
  if (confirmBox.value) { confirmBox.value = null; return }
  if (ctxMenu.value) { ctxMenu.value = null; return }
  if (menuFor.value) { menuFor.value = null; return }
  if (previewBox.value) { previewBox.value = null; return }
  if (moveBox.value) { moveBox.value = null }
}

onMounted(() => {
  refresh()
  document.addEventListener('click', closeMenu)
  document.addEventListener('click', closeCtx)
  // 右键用 capture 阶段：先关掉旧菜单，再让目标自己的 handler 打开新菜单。
  // 用冒泡会被自己关掉（新菜单刚设上就被这个监听清空）——踩过。
  document.addEventListener('contextmenu', closeCtx, true)
  document.addEventListener('scroll', closeCtx, true)
  document.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('click', closeCtx)
  document.removeEventListener('contextmenu', closeCtx, true)
  document.removeEventListener('scroll', closeCtx, true)
  document.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
.shell { display: flex; height: 100vh; overflow: hidden; background: var(--bg); }

.side {
  width: 236px; flex-shrink: 0; background: #fff; border-right: 1px solid var(--line);
  display: flex; flex-direction: column; padding: 18px 14px 14px;
}
.side-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px 16px;
              font-weight: 700; font-size: 15.5px; letter-spacing: .3px; }
.side-brand img { width: 30px; height: 30px; border-radius: 50%; }

.new-btn { width: 100%; justify-content: center; height: 38px; margin-bottom: 18px; }
.plus { font-size: 17px; line-height: 1; margin-top: -1px; }

.side-nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 11px;
  border-radius: 9px; font-size: 13.5px; color: var(--ink-2);
  cursor: pointer; transition: background .15s, color .15s;
}
.nav-item:hover { background: #f6f7fc; color: var(--ink); }
.nav-item.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.ic { font-style: normal; font-size: 15px; width: 18px; text-align: center; }

.side-foot { margin-top: auto; }
.quota { padding: 11px 12px; background: #f8f9fd; border: 1px solid #eef0f7;
         border-radius: 10px; margin-bottom: 10px; }
.quota-top { font-size: 11px; margin-bottom: 7px; color: var(--ink-2); }
.bar { height: 5px; background: #e3e6f0; border-radius: 4px; overflow: hidden; }
.bar-in { height: 100%; border-radius: 4px;
          background: linear-gradient(90deg, var(--brand), #8b9bff); transition: width .35s; }
.quota-num { font-size: 10.5px; margin-top: 6px; color: var(--ink-2); }

.side-user { display: flex; align-items: center; gap: 9px; padding: 6px 4px 2px; }
.avatar {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, var(--brand), #8b5cf6);
  color: #fff; display: grid; place-items: center; font-weight: 600; font-size: 13px;
}
.who { flex: 1; min-width: 0; }
.name { font-size: 12.5px; font-weight: 600; }
.mail { font-size: 10.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.icon-btn {
  width: 30px; height: 30px; border-radius: 8px; border: 1px solid var(--line);
  background: #fff; color: var(--ink-3); font-size: 14px; display: grid; place-items: center;
}
.icon-btn:hover { border-color: #cfd4e4; color: var(--ink); background: #fafbff; }

.main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }

.topbar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 13px 24px; background: #fff; border-bottom: 1px solid var(--line);
}
.crumbs { display: flex; align-items: center; gap: 7px; font-size: 13.5px;
          min-width: 0; overflow: hidden; }
.crumb { cursor: pointer; color: var(--ink-3); white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.crumb:hover { color: var(--brand); }
.crumb.cur { color: var(--ink); font-weight: 600; cursor: default; }
.sep { color: #ced3e2; }

.top-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.search-box {
  display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 10px;
  background: #f5f6fb; border: 1px solid transparent; border-radius: 9px;
  width: 210px; transition: .16s;
}
.search-box:focus-within { background: #fff; border-color: var(--brand);
                           box-shadow: 0 0 0 3px rgba(91,108,255,.10); }
.search-ic { color: var(--ink-3); font-size: 15px; }
.search-box input { flex: 1; border: 0; background: transparent; outline: none;
                    font-size: 13px; color: var(--ink); min-width: 0; }
.search-box input::placeholder { color: var(--ink-3); }
.clear { border: 0; background: transparent; color: var(--ink-3); font-size: 15px;
         line-height: 1; padding: 0 2px; }

.bar-info {
  display: flex; align-items: center; justify-content: space-between;
  padding: 11px 24px 8px; font-size: 12.5px; gap: 12px; flex-shrink: 0;
}
.right-side { display: flex; align-items: center; gap: 10px; }
.sort-group { display: flex; align-items: center; gap: 4px; }
.sort-btn {
  border: 0; background: transparent; color: var(--ink-3); font-size: 12.5px;
  padding: 4px 9px; border-radius: 7px; transition: .15s;
}
.sort-btn:hover { background: #eef0f8; color: var(--ink); }
.sort-btn.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.sort-btn i { font-style: normal; margin-left: 3px; font-size: 11px; }

.list { flex: 1; overflow-y: auto; padding: 0 24px 24px; }

/* 表头：列宽必须与 .row 完全一致，否则文字会跟数据错位 */
.thead {
  display: flex; align-items: center; gap: 12px; padding: 0 14px 7px;
  font-size: 11.5px; color: var(--ink-3); position: sticky; top: 0;
  background: var(--bg); z-index: 5;
}
.th { white-space: nowrap; user-select: none; }
/* 名称表头横跨「图标 + 文件名」两列，这样文字起点和文件名左缘对齐 */
.th-name-group { flex: 1 1 0; min-width: 144px; cursor: pointer; display: flex;
                 align-items: center; gap: 12px; }
.th-icon-inline { width: 24px; flex-shrink: 0; }
.th-path { flex: 1 1 0; min-width: 60px; }
.th-type { flex: 0 0 56px; text-align: right; }
.th-size { flex: 0 0 70px; text-align: right; cursor: pointer; }
.th-time { flex: 0 0 78px; text-align: right; cursor: pointer; }
.th i, .sort-btn i { font-style: normal; margin-left: 3px; font-size: 10px; }
.th-name:hover, .th-size:hover, .th-time:hover { color: var(--ink); }
.th-ops { flex: 0 0 132px; text-align: right; }
@media (max-width: 1100px) { .th-time, .row-time { display: none; } }
@media (max-width: 900px) { .th-type, .row-type { display: none; } }
.row {
  display: flex; align-items: center; gap: 12px; padding: 10px 14px;
  background: #fff; border: 1px solid #eef0f7; border-radius: 10px;
  margin-bottom: 7px; transition: .14s; cursor: pointer; font-size: 13px;
}
.row:hover { border-color: #d8dcef; box-shadow: 0 2px 10px rgba(22,25,42,.05); }
.row { position: relative; }
.row-icon { font-size: 19px; line-height: 1; width: 24px; text-align: center; flex-shrink: 0; }
/* 名称是主信息，必须优先拿宽度：flex-basis 0 + 高 grow + min-width 下限，
   否则窄窗口下会被固定列挤成 0（实测 766px 窗口时宽度真的是 0）。 */
.row-name { flex: 1 1 0; min-width: 120px; font-weight: 500; overflow: hidden;
            text-overflow: ellipsis; white-space: nowrap; }
.row-path { flex: 1 1 0; min-width: 60px; font-size: 11.5px; overflow: hidden;
            text-overflow: ellipsis; white-space: nowrap; }
.row-type { flex: 0 0 56px; font-size: 11.5px; text-align: right; }
.row-size { flex: 0 0 70px; font-size: 11.5px; text-align: right; }
.row-time { flex: 0 0 78px; font-size: 11.5px; text-align: right; }
/* 操作区不参与 flex 分配（绝对定位在右侧），改由 hover 覆盖，
   这样窄窗口下也不会把名称挤没。 */
/* 操作列固定占位，避免和大小列视觉粘连；hover 才完全显形 */
.row-ops { flex: 0 0 132px; display: flex; gap: 4px; justify-content: flex-end;
           opacity: .45; transition: opacity .14s; flex-shrink: 0; }
.row:hover .row-ops { opacity: 1; }
/* 窗口窄时隐藏次要列，保住名称 */
@media (max-width: 1100px) {
  .row-time { display: none; }
}
@media (max-width: 900px) {
  .row-type { display: none; }
}
.row:hover .row-ops { opacity: 1; }
.icon-only { padding: 0; width: 30px; justify-content: center; }

.more-wrap { position: relative; }
.menu {
  position: absolute; right: 0; top: 34px; min-width: 130px; z-index: 40;
  background: #fff; border: 1px solid var(--line); border-radius: 10px;
  box-shadow: 0 8px 26px rgba(22,25,42,.13); padding: 5px;
}
.menu-item { padding: 8px 11px; font-size: 12.5px; border-radius: 7px; cursor: pointer; }
.menu-item:hover { background: #f5f6fb; }
.menu-item.danger { color: var(--danger); }
.menu-item.danger:hover { background: #fff5f4; }
.menu-sep { height: 1px; background: var(--line); margin: 4px 0; }

/* ---------- 右键菜单 ---------- */
.ctx-menu {
  position: fixed; z-index: 900; min-width: 168px;
  background: #fff; border: 1px solid var(--line); border-radius: 10px;
  box-shadow: 0 12px 32px rgba(20, 26, 48, .16), 0 2px 6px rgba(20, 26, 48, .06);
  padding: 5px; font-size: 12.5px; user-select: none;
  animation: ctx-in .09s ease-out;
}
@keyframes ctx-in {
  from { opacity: 0; transform: scale(.96) translateY(-2px); }
  to   { opacity: 1; transform: none; }
}
.ctx-item {
  padding: 7px 11px; border-radius: 7px; cursor: pointer;
  display: flex; align-items: center; gap: 6px;
}
.ctx-item:hover { background: #f2f4fb; }
.ctx-item.danger { color: var(--danger); }
.ctx-item.danger:hover { background: #fff5f4; }
.ctx-item.off { opacity: .4; cursor: default; }
.ctx-item.off:hover { background: transparent; }
.ctx-sep { height: 1px; background: var(--line); margin: 4px 2px; }

/* 右键选中的那一行给个底色，让人知道菜单作用在谁身上 */
.row.ctx-target { background: #f0f3ff; }

/* ---------- 移动对话框 ---------- */
.mask.darker { background: rgba(10, 14, 24, .72); }
.move-tip { font-size: 12.5px; margin: -6px 0 10px; }
.dir-picker { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.dp-crumb {
  display: flex; align-items: center; flex-wrap: wrap; gap: 2px;
  padding: 9px 12px; font-size: 12.5px; border-bottom: 1px solid var(--line);
  background: #fafbfd;
}
.dp-link { color: var(--accent); cursor: pointer; }
.dp-link:hover { text-decoration: underline; }
.dp-sep { color: var(--ink-3); }
.dp-list { max-height: 264px; overflow-y: auto; padding: 6px; }
.dp-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-radius: 8px; cursor: pointer; font-size: 13.5px;
}
.dp-item:hover { background: #f5f6fb; }
.dp-item.disabled { opacity: .35; cursor: not-allowed; }
.dp-item.disabled:hover { background: transparent; }
.dp-ic { font-size: 15px; }
.dp-nm { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dp-arrow { color: var(--ink-3); font-size: 15px; }
.dp-empty { padding: 18px 10px; text-align: center; font-size: 12.5px; }

/* ---------- 预览弹窗 ---------- */
.preview-modal {
  width: min(1040px, 94vw); max-height: 90vh; display: flex; flex-direction: column;
  background: #fff; border-radius: 14px; overflow: hidden;
  box-shadow: 0 24px 70px rgba(0, 0, 0, .45);
}
.pv-head {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  border-bottom: 1px solid var(--line); flex-shrink: 0;
}
.pv-name { flex: 1; font-size: 13.5px; font-weight: 500;
           overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-actions { display: flex; gap: 6px; flex-shrink: 0; }
.pv-body {
  flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center;
  background: #f7f8fb; overflow: auto; padding: 16px;
}
.pv-body img { max-width: 100%; max-height: 76vh; object-fit: contain; border-radius: 6px; }
.pv-body video { max-width: 100%; max-height: 76vh; border-radius: 6px; }
.pv-body audio { width: 100%; max-width: 560px; }
.pv-body iframe { width: 100%; height: 76vh; border: 0; border-radius: 6px; background: #fff; }
.pv-body pre {
  width: 100%; align-self: flex-start; margin: 0; padding: 4px;
  font-size: 12.5px; line-height: 1.62; white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace; color: var(--ink);
}
.pv-unsupported { padding: 40px; font-size: 13px; }

.empty, .empty-plain { flex: 1; display: grid; place-content: center;
                        text-align: center; color: var(--ink-3); font-size: 13px; gap: 8px; }
.empty-icon { font-size: 40px; opacity: .45; }

.uploads {
  position: fixed; right: 22px; bottom: 22px; width: 310px; z-index: 60;
  background: #fff; border: 1px solid var(--line); border-radius: 12px;
  box-shadow: 0 10px 34px rgba(22,25,42,.14); padding: 13px 15px;
}
.up-title { font-size: 12.5px; font-weight: 600; margin-bottom: 9px; color: var(--ink-2);
            display: flex; justify-content: space-between; }
.up-row { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; font-size: 11.5px; }
.up-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.up-bar { width: 84px; height: 4px; background: #e6e8f0; border-radius: 3px;
          overflow: hidden; flex-shrink: 0; }
.up-in { height: 100%; background: var(--brand); transition: width .2s; }
.up-in.err { background: var(--danger); }
.up-pct { width: 32px; text-align: right; color: var(--ink-3); flex-shrink: 0; }

.mask { position: fixed; inset: 0; background: rgba(16,18,32,.38);
        display: grid; place-items: center; z-index: 100; backdrop-filter: blur(2px); }
.modal { background: #fff; border-radius: 14px; width: 400px; padding: 24px;
         box-shadow: 0 20px 60px rgba(22,25,42,.22); }
.modal h3 { margin: 0 0 18px; font-size: 15.5px; }
.modal label { display: block; margin-bottom: 13px; }
.modal label span { display: block; font-size: 12px; color: var(--ink-2); margin-bottom: 6px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.modal-sm { width: 340px; }
.modal-sm h3 { margin-bottom: 10px; }
.tip-line { font-size: 12.5px; color: var(--ink-2); margin: 0 0 12px; line-height: 1.6; }
.err-line { font-size: 12px; color: var(--danger); margin: 8px 0 0; }

/* 轻提示 */
.toast {
  position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%);
  z-index: 2000; padding: 10px 18px; border-radius: 9px; font-size: 13px;
  background: #2b3144; color: #fff; box-shadow: 0 10px 28px rgba(16, 20, 40, .26);
  max-width: 420px; text-align: center; pointer-events: none;
}
.toast.err { background: #c0392b; }
.toast.ok { background: #1f8a55; }
.toast-enter-active, .toast-leave-active { transition: opacity .18s, transform .18s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(8px); }
.share-out { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--line); }
.link-row { display: flex; gap: 8px; }
.tip { font-size: 11.5px; margin: 8px 0 0; }
</style>