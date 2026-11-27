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
      <!-- 上传按钮：只在「我的文件」出现。
           用 transition 而不是裸 v-if —— 裸 v-if 会让它在切页瞬间消失，
           下面的导航猛地往上跳一下，观感很硬。
           这里收掉时同时缩高度+淡出，让导航平滑顶上（高度/外边距都要动，
           只淡不缩的话位置还是会瞬间跳）。 -->
      <transition name="upload-slot">
        <button v-if="curShowUpload" class="btn btn-primary new-btn" @click="$emit('upload')">
          <span class="plus">+</span> 上传文件
        </button>
      </transition>

      <nav class="side-nav">
        <div v-for="it in navs" :key="it.key"
             class="nav-item" :class="{ on: curActive === it.key }"
             @click="go(it.key)">
          <i class="ic">{{ it.icon }}</i><span>{{ it.label }}</span>
        </div>
      </nav>

      <div class="side-foot">
        <!-- 用户区：头像 + 昵称，点进「个人信息」页。
             放这里是因为侧栏底部是人看「我是谁」的习惯位置；
             以前这块只有一个头像占位，没有通往资料页的路。 -->
        <button type="button" class="user-btn" :class="{ on: route.path === '/profile' }"
                @click="router.push('/profile')">
          <span class="user-ava" :class="{ hasImg: !!avatarUrl }">
            <img v-if="avatarUrl" :src="avatarUrl" alt="" />
            <span v-else>{{ myLetter }}</span>
          </span>
          <span class="user-info">
            <span class="user-name">{{ me?.nickname || '未登录' }}</span>
            <span class="user-sub">{{ me?.role === 'ADMIN' ? '管理员' : '个人用户' }}</span>
          </span>
          <span class="quota-go">›</span>
        </button>

        <!-- 存储空间：整块是可点的，进「存储空间」页。
             之前只是静态展示，看着像能点却没反应。
             hover 时整块轻微提亮 + 右侧浮出箭头，给「这里有下一页」的暗示。 -->
        <button v-if="curShowQuota" type="button" class="quota" @click="router.push('/storage')">
          <div class="quota-top">
            <span class="muted">存储空间</span>
            <span class="quota-go">›</span>
          </div>
          <div class="bar"><div class="bar-in" :style="{ width: Math.max(barPct, barPct > 0 ? 2 : 0) + '%' }"></div></div>
          <div class="quota-num">
            <span>已用 {{ usedText }} / {{ quotaText }}</span>
            <span class="quota-pct">{{ pct }}%</span>
          </div>
        </button>
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
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, getToken } from '../api.js'

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

/// 重新拉一次 me。
/// 为什么需要：个人信息页改完昵称/头像后，侧栏是同一个组件实例（不重建），
/// 不主动刷新的话名字还是旧的 —— 用户会以为没改成功。
async function refreshMe() {
  if (props.me) return          // 父层显式传了就不覆盖
  try { meLocal.value = await api.me() } catch (e) { /* 401 交给路由守卫 */ }
}

/// 两个触发点缺一不可：
/// - 路由变化：从别的页切回来时对齐一次（比如在别处删了头像）
/// - profile-changed 自定义事件：改昵称/头像时路由**没变**，只有事件能通知到；
///   早先只监听路由，实测改完昵称页内变了、侧栏还是旧名。
watch(() => route.fullPath, refreshMe)
onMounted(() => { window.addEventListener('drive:profile-changed', refreshMe) })
onBeforeUnmount(() => { window.removeEventListener('drive:profile-changed', refreshMe) })

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
  if (r.path === '/storage') return 'storage'
  if (r.path === '/profile') return 'profile'
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
  // 个人页 / 存储页不属于左侧四项，保持全都未选中 ——
  // 否则停在「个人信息」却高亮「我的文件」，位置反馈是错的。
  if (route.path === '/profile' || route.path === '/storage') return ''
  if (route.query.view === 'trash') return 'trash'
  return 'files'
})

/// 侧栏头像：URL 带 avatarKey 破缓存，并附 access_token ——
/// <img src> 发不出 Authorization 头，拦截器支持查询参数形式。
const avatarUrl = computed(() => {
  const key = me.value?.avatarKey
  if (!key) return ''
  const q = new URLSearchParams({ v: key, access_token: getToken() || '' })
  return `/api/v1/auth/avatar?${q.toString()}`
})

/// 无头像时显示昵称首字
const myLetter = computed(() =>
  (me.value?.nickname || '?').trim().charAt(0).toUpperCase())

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
  display: flex; align-items: center; gap: 10px;
  padding: 0 6px 20px; font-weight: 700; font-size: 17.5px;
  letter-spacing: -.1px;
}
.side-brand img { width: 30px; height: 30px; border-radius: 50%; }

.new-btn { margin-top: 2px;
  width: 100%; margin-bottom: 16px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.new-btn .plus { font-size: 17px; line-height: 1; }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; }

.side-nav { display: flex; flex-direction: column; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 11px;
  /* 行高比浮起：之前 padding 9px、项间 gap 2px，四项挤成一坨，读着累 */
  padding: 11px 12px; border-radius: 10px;
  font-size: 15px; color: var(--ink-2); cursor: pointer;
  transition: background .18s, color .18s;
  /* 高亮靠背景+颜色平滑过渡，不是一闪就换 */
}
.nav-item i { font-size: 16.5px; }
.nav-item:hover { background: #f6f7fc; color: var(--ink); }
.nav-item.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.nav-item .ic { font-style: normal; width: 18px; text-align: center; }

.side-foot { margin-top: auto; padding-top: 18px; }
.user-btn {
  display: flex; align-items: center; gap: 9px; width: 100%;
  padding: 7px 6px; margin-bottom: 4px;
  border: 0; background: transparent; cursor: pointer;
  text-align: left; font: inherit; border-radius: 9px;
  transition: background .16s ease;
}
.user-btn:hover { background: #f7f8fc; }
.user-btn.on { background: var(--brand-soft); }
.user-btn.on .user-name { color: var(--brand-deep); }
.user-btn:active { background: #eef0f8; }
.user-ava {
  width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; overflow: hidden;
  background: linear-gradient(135deg, var(--brand), #7b5bf7);
  color: #fff; font-size: 14.5px; font-weight: 600;
}
.user-ava > img { width: 100%; height: 100%; object-fit: cover; display: block; }
.user-info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.user-name {
  font-size: 14px; color: var(--ink); font-weight: 550;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.user-sub { font-size: 12px; color: var(--ink-3); margin-top: 2px; }
.user-btn:hover .quota-go { opacity: 1; transform: translateX(0); }

.quota {
  display: block; width: calc(100% + 8px); margin: 10px -4px 0;
  padding: 8px 4px; border: 0; background: transparent; cursor: pointer;
  text-align: left; font: inherit; border-radius: 8px;
  transition: background .16s ease;
}
.quota:hover { background: #f7f8fc; }
.quota:active { background: #eef0f8; }
.quota-go {
  opacity: 0; transform: translateX(-3px);
  transition: opacity .16s ease, transform .16s ease;
  color: var(--ink-3); font-size: 13px;
}
.quota:hover .quota-go { opacity: 1; transform: translateX(0); }
.quota-top { display: flex; justify-content: space-between; font-size: 12.5px; margin-bottom: 8px; }
.bar { height: 6px; border-radius: 3px; background: #eef0f7; overflow: hidden; }
.bar-in { height: 100%; border-radius: 3px; background: linear-gradient(90deg, #5b76f7, #7b5bf7);
          transition: width .5s cubic-bezier(.3,.55,.4,.95); }
.quota-num {
  display: flex; justify-content: space-between; align-items: baseline;
  font-size: 12px; color: var(--ink-2); margin-top: 7px;
  font-variant-numeric: tabular-nums;
}
/* 百分比单独一点强调：数值行之前斜杠+中点全糊在一起，扫不出来 */
.quota-pct { font-weight: 600; color: var(--brand-deep); }

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

/* ---- 上传按钮的出入场 ----
 * 关键：高度和 margin 必须一起动。只做 opacity 的话，
 * 元素虽然看不见了，位置却还在（或者反过来瞬间塌掉），导航照样会跳。
 * 用 max-height 而不是 height：元素高度由 padding 撑，写死 height 会裁字。
 * 节奏：离场 .24s、入场 .26s，和内容区过渡（.16/.26）大致同步。 */
/* 离场：透明度**延迟**到最后才降。
 * 如果和高度一起线性收，文字会在还占着位置时就先褪光 ——
 * 实测 100ms 那帧只剩一条淡色残影，看着像没渲染完。
 * 现在前半段文字清楚、被 overflow 自然裁掉，最后再淡出收尾。 */
.upload-slot-leave-active {
  transition: max-height .24s cubic-bezier(.4,0,.2,1),
              opacity .10s ease .14s,
              margin-bottom .24s cubic-bezier(.4,0,.2,1),
              padding-top .24s cubic-bezier(.4,0,.2,1),
              padding-bottom .24s cubic-bezier(.4,0,.2,1),
              transform .24s cubic-bezier(.4,0,.2,1);
  overflow: hidden;
  /* 这个值要贴着按钮的**真实高度**（实测 36px）。
     写成 60px 会有一段"空转"：max-height 从 60 收到 36 期间，
     实际高度一直被内容撑着不变，实测前 92ms 高度纹丝不动、只有透明度在变，
     看着就是"先淡一下，然后突然塌"。40px 只留 4px 余量，几乎全程都在缩。 */
  max-height: 40px;
}
/* 入场反过来：先从高度 0 长出来，字晚一点显 */
.upload-slot-enter-active {
  transition: max-height .26s cubic-bezier(.34,.8,.35,1),
              opacity .12s ease .08s,
              margin-bottom .26s cubic-bezier(.34,.8,.35,1),
              padding-top .26s cubic-bezier(.34,.8,.35,1),
              padding-bottom .26s cubic-bezier(.34,.8,.35,1),
              transform .26s cubic-bezier(.34,.8,.35,1);
}

.upload-slot-enter-from,
.upload-slot-leave-to {
  max-height: 0;
  opacity: 0;
  margin-bottom: 0;
  padding-top: 0;
  padding-bottom: 0;
  transform: translateY(-4px);
}
</style>
