<template>
  <div class="shell">
    <!-- ===== 侧栏：唯一一份，路由切换时不重建 ===== -->
    <aside class="side">
      <div class="side-brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
      </div>

      <!-- 上传按钮：只有「我的文件」能用（要落在当前目录下）。
           其他页面不显示，避免点了不知道传到哪里去。 -->
      <button v-if="curShowUpload" class="btn btn-primary new-btn" @click="$emit('upload')">
        <span class="plus">+</span> 上传文件
      </button>

      <nav class="side-nav">
        <div v-for="it in navs" :key="it.key"
             class="nav-item" :class="{ on: curActive === it.key }"
             @click="go(it.key)">
          <i class="ic">{{ it.icon }}</i><span>{{ it.label }}</span>
        </div>
      </nav>

      <div class="side-foot">
        <!-- 存储空间：有 me 数据才显示，没数据时留空而不是显示 0 B -->
        <div v-if="curShowQuota" class="quota">
          <div class="quota-top"><span class="muted">存储空间</span></div>
          <div class="bar"><div class="bar-in" :style="{ width: barPct + '%' }"></div></div>
          <div class="quota-num">{{ usedText }} / {{ quotaText }} · {{ pct }}%</div>
        </div>
      </div>
    </aside>

    <!-- ===== 主区 =====
         路由视图直接挂在这里，AppShell 本身在路由切换时**不会重建**
         （因为它挂在 App.vue 外层，不随 router-view 换手），
         所以侧栏从头到尾是同一个 DOM 节点 —— 一帧都不动。
         过渡只包 router-view 的内容，侧栏在 transition 之外。 -->
    <main class="main">
      <router-view v-slot="{ Component, route }">
        <transition name="content-swap" mode="out-in">
          <component :is="Component" :key="contentKeyOf(route)" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '../api.js'

const props = defineProps({
  /// 当前高亮项；不传则从当前路由推导（推荐，避免父页面与地址栏不一致）
  active: { type: String, default: null },
  /// 是否显示上传按钮。null = 按路由自动判断（只有「我的文件」显示）
  showUpload: { type: Boolean, default: null },
  /// 是否显示存储配额。null = 自动（除公开页外都显示）
  showQuota: { type: Boolean, default: null },
  /// 用户信息，用于额度计算；由父页面传进来，避免每个页面各拉一次 /me
  me: { type: Object, default: null },
  /// 内容层身份标识：变了才播过渡。
  /// 用「页面 + 视图状态」拼，比如 shares / files / trash，
  /// 这样「我的文件」内部切目录、切回收站也会走过渡，而侧栏始终不动。
  viewKey: { type: String, default: 'files' },
})

defineEmits(['upload'])

const router = useRouter()
const route = useRoute()

/// 外壳自己拉一次用户信息：配额、头像都在这里用，
/// 不依赖各页面是否记得传（之前就是漏传导致配额条一直是空的）
const meLocal = ref(null)
onMounted(async () => {
  if (props.me) { meLocal.value = props.me; return }
  try { meLocal.value = await api.me() } catch (e) { /* 401 交给路由守卫 */ }
})

/// 内容层的 key：变了才播过渡。
/// 「我的文件」里切目录也算内容变化，但路由没变，所以目录由 Home 通过
/// route.query.dir 暴露（见 Home.vue），这里统一拼进来。
function contentKeyOf(r) {
  if (r.path === '/shares') return 'shares'
  if (r.path === '/downloader') return 'downloader'
  if (r.query.view === 'trash') return 'trash'
  return 'files:' + (r.query.dir || 'root')
}

/// 导航项顺序固定，四个页面共用。回收站以前只有「我的文件」侧栏里有，
/// 导致切到分享/下载器时侧栏会突然少一项 —— 这里统一成四项。
const navs = [
  { key: 'files', icon: '🗂', label: '我的文件', to: '/' },
  { key: 'shares', icon: '🔗', label: '我的分享', to: '/shares' },
  { key: 'downloader', icon: '⤓', label: '专属下载器', to: '/downloader' },
  { key: 'trash', icon: '🗑', label: '回收站', to: null },
]

function freeze(el) {
  // 离场瞬间锁住交互：动画期间连点两次不会叠加出两个内容层
  if (el && el.style) el.style.pointerEvents = 'none'
}

function go(key) {
  const it = navs.find(n => n.key === key)
  if (!it) return
  if (key === 'trash') {
    // 回收站不是独立路由，是「我的文件」页里的一个视图状态。
    // 用 query 标记，让 Home 挂载时能直接进回收站；已在该页时则是同一个路由，
    // 由 AppShell 之外的 Home 监听 route.query 处理（见 Home.vue）。
    if (curActive.value === 'trash') return
    router.push({ path: '/', query: { view: 'trash' } })
    return
  }
  if (curActive.value === key && key !== 'trash') return
  router.push(it.to)
}

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = b
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${u[i]}`
}

/// 上传按钮：只有「我的文件」（含子目录）能用，因为要落在当前目录下
const curShowUpload = computed(() =>
  props.showUpload === null ? (route.path === '/' && route.query.view !== 'trash') : props.showUpload)

/// 配额：有外壳的页面都显示
const curShowQuota = computed(() =>
  props.showQuota === null ? true : props.showQuota)

/// 高亮：优先用父页面显式传的，否则按路由判断
const curActive = computed(() => {
  if (props.active) return props.active
  if (route.path === '/shares') return 'shares'
  if (route.path === '/downloader') return 'downloader'
  if (route.query.view === 'trash') return 'trash'
  return 'files'
})

/// 用户信息：父页面传了就用传的，否则用自己拉的那份
const me = computed(() => props.me || meLocal.value)

const barPct = computed(() => {
  const p = (me.value?.storageUsed / me.value?.storageQuota) * 100 || 0
  return p > 0 ? Math.max(p, 1.2) : 0
})
const usedText = computed(() => sizeText(me.value?.storageUsed || 0))
const quotaText = computed(() => sizeText(me.value?.storageQuota || 0))
const pct = computed(() => {
  const p = (me.value?.storageUsed / me.value?.storageQuota) * 100 || 0
  return p > 0 ? p.toFixed(1) : '0.0'
})
</script>

<style scoped>
/* 布局是全局骨架，从三个页面里搬过来统一到这里，只留这一份 */
.shell { display: flex; height: 100vh; overflow: hidden; background: var(--bg); }

.side {
  width: 232px; flex-shrink: 0;
  background: #fff; border-right: 1px solid var(--line);
  padding: 18px 14px 16px;
  display: flex; flex-direction: column;
}
.side-brand {
  display: flex; align-items: center; gap: 9px;
  padding: 0 6px 16px; font-weight: 700; font-size: 15.5px;
}
.side-brand img { width: 30px; height: 30px; border-radius: 50%; }

.new-btn {
  width: 100%; margin-bottom: 16px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.new-btn .plus { font-size: 16px; line-height: 1; }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; }

.side-nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 11px; border-radius: 9px;
  font-size: 13.5px; color: var(--ink-2); cursor: pointer;
  transition: background .18s, color .18s;
  /* 高亮靠背景+颜色平滑过渡，不是一闪就换 */
}
.nav-item:hover { background: #f6f7fc; color: var(--ink); }
.nav-item.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.nav-item .ic { font-style: normal; width: 18px; text-align: center; }

.side-foot { margin-top: auto; padding-top: 14px; }
.quota { padding: 10px 4px 0; }
.quota-top { display: flex; justify-content: space-between; font-size: 11.5px; margin-bottom: 6px; }
.bar { height: 5px; border-radius: 3px; background: #eef0f7; overflow: hidden; }
.bar-in { height: 100%; border-radius: 3px; background: linear-gradient(90deg, #5b76f7, #7b5bf7);
          transition: width .5s cubic-bezier(.3,.55,.4,.95); }
.quota-num { font-size: 10.5px; color: var(--ink-3); margin-top: 5px; }

/* ===== 内容区过渡（侧栏不参与）=====
 * 离场 0.16s 短于入场 0.26s：两段尾巴重叠，避免出现「两边都不存在」的空白帧。
 * 入场从 .38 起而不是 0 —— 从 0 起会先空一下再缓缓出来，反而更明显。
 * 只位移 9px：位移大了会盖住侧栏边缘，也容易让人晕。 */
.content-host { min-height: 0; }

/* 过渡包住的 component 需要一个撑满高度的容器，
   否则内容只有自身高度（实测只有 260px，页面下半截空着） */
.main > .page,
.main > * { flex: 1; min-height: 0; }

.content-swap-leave-active { transition: opacity .16s ease, transform .16s ease, filter .16s ease; }
.content-swap-leave-to { opacity: 0; transform: translateY(-5px); filter: blur(1.5px); }

.content-swap-enter-active { transition: opacity .26s cubic-bezier(.25,.55,.4,.95),
                                       transform .26s cubic-bezier(.25,.55,.4,.95),
                                       filter .26s ease; }
.content-swap-enter-from { opacity: .38; transform: translateY(9px); filter: blur(3px); }

@media (prefers-reduced-motion: reduce) {
  .content-swap-leave-active, .content-swap-enter-active { transition-duration: .01ms !important; }
  .content-swap-enter-from { opacity: 1; transform: none; filter: none; }
}
</style>
