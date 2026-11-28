<template>
  <div class="shell" :class="{ 'side-collapsed': collapsed }">
    <!-- ===== 侧栏：唯一一份，路由切换时不重建 =====
         收起后只留 64px 图标条：展开/收起用 width 过渡，不用 transform ——
         transform 不改变布局盒子，主区不会跟着让位，收起时内容会压在侧栏底下。 -->
    <aside class="side">
      <!-- 收起态：logo + 图标条。
           中间的空白是一条「点击区」：点它就展开，和右侧竖栏的留白点击一致，
           人不必非得瞄着底部那个小箭头。 -->
      <div v-if="collapsed" class="side-mini">
        <button class="mini-brand" title="展开侧栏" @click="collapsed = false">
          <img src="/whale-flat-128.png" alt="北冥云盘" />
        </button>

        <nav class="mini-nav">
          <button v-for="it in navs" :key="it.key"
                  class="mini-item" :class="{ on: curActive === it.key }"
                  :title="it.label" @click="go(it.key)">
            <svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><path :d="it.path" /></svg>
          </button>
        </nav>

        <!-- 空白区：点一下展开。cursor 给 pointer，配一条淡提示线说明这里能点。 -->
        <div class="mini-gap" title="展开侧栏" @click="collapsed = false">
          <span class="gap-tip">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
          </span>
        </div>

        <div class="mini-foot">
          <!-- 收起态不放头像：只有 32px 大，认不出是谁，还占掉最显眼的位置。
               改成横向电池：百分比写在电池里，数字右边挂一个状态图标。 -->
          <button class="mini-battery" :class="batLevel" :title="batTip"
                  @click="router.push('/storage')">
            <!-- 一颗透明电池：内部不填色，占用率只从「边框渐变到哪一格」读出来。
                 框内写已用百分比 —— 收起态空间太小放不下外部文字，
                 数字就压在电池肚子里。 -->
            <span class="bat-shell" :style="batShellStyle">
              <!-- 电流：从左侧流进来的能量条，只占已用比例那么长 -->
              <span class="bat-current" :style="batCurrentStyle">
                <i class="spark s1"></i><i class="spark s2"></i><i class="spark s3"></i>
              </span>
              <span class="bat-label">
                <span class="bat-num">{{ usedPctText }}<i>%</i></span>
              </span>
            </span>
          </button>
          <button class="mini-toggle" title="展开侧栏" @click="collapsed = false">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </div>
      </div>

      <!-- 展开态 -->
      <div v-else class="side-full">
      <div class="side-brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
        <button class="side-toggle" title="收起侧栏" @click="collapsed = true">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
        </button>
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
          <svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><path :d="it.path" /></svg><span>{{ it.label }}</span>
        </div>
      </nav>

      <!-- 展开态的空白也是可点的：点一下就收起。
           没有这块的话，收起只能靠顶栏那个小箭头，空白处点了没反应显得呆。 -->
      <div class="side-gap" title="收起侧栏" @click="collapsed = true"></div>

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

        <!-- 存储空间：整块可点，进「存储空间」页。
             用的是收起态那颗电池的「放大版」：
             同一个大圆角外框 + 填充 + 数字 + 状态图标，
             只是这里横向空间够，把「存储空间」标签和明细一起写进去。
             这样收放侧栏时，底部是同一个东西在变大变小，
             而不是收起是一颗电池、展开变成另一种进度条。 -->
        <button v-if="curShowQuota" type="button" class="quota" @click="router.push('/storage')"
                :title="batTip">
          <div class="quota-top">
            <span>存储空间</span>
            <span class="quota-go">›</span>
          </div>
          <div class="quota-bat" :class="batLevel" :style="batShellStyle">
            <span class="bat-current" :style="batCurrentStyle">
              <i class="spark s1"></i><i class="spark s2"></i><i class="spark s3"></i><i class="spark s4"></i>
            </span>
          </div>
          <!-- 展开态数字放在框外：框内部只留给电流，不被字盖住 -->
          <div class="quota-num">
            <span class="quota-used">已用 {{ usedPctText }}%</span>
            <span class="quota-total">{{ usedText }} / {{ quotaText }}</span>
          </div>
        </button>
      </div>
      </div><!-- /side-full -->
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

    <!-- 右侧竖栏常驻：以前写在 Home.vue 里，只有「我的文件」有，
         切到别的页右边就空了。挂在外壳上，五页共用同一份实例，
         展开/收起状态也不会因为切页被重置。
         搜索依赖页面内容，所以只往上抛事件，由当前页决定怎么处理。 -->
    <SideRail @search="$emit('rail-search', $event)" />
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, getToken } from '../api.js'
import SideRail from './SideRail.vue'

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

/// 左侧栏是否收起。
/// 存进 localStorage：这是「我这台机器习惯怎么用」的偏好，刷新一次就变回去会很烦。
/// 读的时候兜住 localStorage 不可用的情况（隐私模式会抛异常）。
const SIDE_KEY = 'drive_side_collapsed'
const collapsed = ref((() => {
  try { return localStorage.getItem(SIDE_KEY) === '1' } catch (e) { return false }
})())
watch(collapsed, v => {
  try { localStorage.setItem(SIDE_KEY, v ? '1' : '0') } catch (e) { /* 存不了就算了 */ }
})

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
  if (r.path === '/settings') return 'settings'
  if (r.path === '/admin/users') return 'admin-users'
  if (r.query.view === 'trash') return 'trash'
  return 'files:' + (r.query.dir || 'root')
}

/// 导航项顺序固定，四个页面共用。回收站以前只有「我的文件」侧栏里有，
/// 导致切到分享/下载器时侧栏会突然少一项 —— 这里统一成四项。
/* 导航图标用内联 SVG 而不是 emoji。
   原因：emoji（🗂 🔗 🗑）是彩色位图字体，`color` 管不了它 ——
   收起态没有文字兜底，四个图标颜色深浅不一、还各自带一套配色，
   放在 64px 图标条里就是「不明显」。SVG 用 currentColor，
   选中/悬停/深浅全跟着 CSS 变量走，形态也比 emoji 干净。 */
const navs = [
  { key: 'files', label: '我的文件', to: '/',
    path: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z' },
  { key: 'shares', label: '我的分享', to: '/shares',
    path: 'M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1-1' },
  { key: 'downloader', label: '专属下载器', to: '/downloader',
    path: 'M12 4v11m0 0l-4-4m4 4l4-4M4 19h16' },
  { key: 'trash', label: '回收站', to: null,
    path: 'M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M10 11v6M14 11v6' },
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
/// 已用百分比 —— 电池整套显示逻辑都基于它。
/// 之前用的是「剩余」，但电池框该表达的是「被占掉多少」：
/// 占得越多框越满、颜色越危险，和「存了多少东西」直接对应。
const usedPct = computed(() => {
  const q = me.value?.storageQuota || 0
  if (!q) return 0
  const p = ((me.value?.storageUsed || 0) / q) * 100
  return Math.max(0, Math.min(100, p))
})

/* 电池上的数字：不足 1% 显示 <1，不圆整成 0。
   新账号 9.8KB / 50GB 真实值 0.02%，显示 0% 会让人以为一点没存。 */
const usedPctText = computed(() => {
  const p = usedPct.value
  if (p > 0 && p < 1) return '<1'
  return Math.round(p)
})

/* 框内电流的长度 = 已用比例。
   下限 6%：只剩一丝时也要看得见，否则像渲染坏了。 */
const batCurrentStyle = computed(() => {
  const p = usedPct.value
  return { width: p <= 0 ? '0%' : Math.max(p, 6).toFixed(1) + '%' }
})

/* 边框渐变：从蓝到紫，一直铺满整圈（不是只铺到已用比例）。
   为什么渐变铺满而不是铺到已用位置 —— 边框是「容器」的形状，
   截断的边框看起来像破了。占用率由「电流条流到哪」+ 颜色档位表达。 */
const batShellStyle = computed(() => {
  const p = usedPct.value
  // 占用越高，渐变越往后取（蓝 → 靛 → 紫 → 琥珀 → 红）
  const angle = 100 + p * 1.4          // 100deg ~ 240deg，让渐变方向随占用缓变
  return { '--bat-angle': angle.toFixed(0) + 'deg' }
})

/* 三档配色：颜色说的是「占了多少」，不是「还剩多少」。
   · normal 蓝紫 —— 用得少
   · warn   琥珀 —— 过半了（60% 以上）
   · full   红   —— 快满（85% 以上） */
const batLevel = computed(() => {
  const p = usedPct.value
  if (p >= 85) return 'is-full'
  if (p >= 60) return 'is-warn'
  return ''
})

const freeText = computed(() => sizeText((me.value?.storageQuota || 0) - (me.value?.storageUsed || 0)))
const batTip = computed(() =>
  `已用 ${usedText.value} / 共 ${quotaText.value}（${pct.value}%），还剩 ${freeText.value}`)
</script>

<style scoped>
/* 布局是全局骨架，从三个页面里搬过来统一到这里，只留这一份 */
.shell { display: flex; height: 100vh; overflow: hidden; background: var(--bg); }

.side {
  /* 收起 64px、展开 232px。用 width 过渡而不是 transform：
     transform 不改变布局盒子，主区不会跟着让位，收起后内容会压在图标条底下。 */
  width: 232px; flex-shrink: 0;
  background: #fff; border-right: 1px solid var(--line);
  padding: 18px 14px 16px;
  display: flex; flex-direction: column;
  overflow: hidden;
  transition: width .28s cubic-bezier(.3, .6, .35, 1),
              padding .28s cubic-bezier(.3, .6, .35, 1);
}
/* 注意选择器的特异性：展开态是 `.side`（一个类），收起态是 `.shell.side-collapsed .side`
   （两个类），本该赢。但 Vue 的 scoped 会给两边都补 `[data-v-xxx]`，
   变成 `[0,2,0]` 对 `[0,3,0]` —— 还是收起态赢。
   实测却输了，说明两条规则的**书写顺序**在编译后反了：
   稳妥做法是再叠一层，把收起态抬到明显更高的权重，别赌顺序。 */
.shell.side-collapsed .side,
.side.side-collapsed {
  /* 78px 而不是原来的 64：电池改成横向之后，这个宽度要能装下
     「电池框 + 百分比 + 状态图标」。78 - 8×2 内边距 = 62 可用宽度，
     刚好够百分比（约 24px）+ 图标 10px + 电池框余量。
     再窄数字就被挤出电池，回到「数字在旁边」的老样子。 */
  width: 78px; padding: 18px 8px 16px;
}
.side-full { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.side-brand {
  display: flex; align-items: center; gap: 10px;
  padding: 0 6px 20px; font-weight: 700; font-size: 17.5px;
  letter-spacing: -.1px;
}
.side-brand img { width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0; }
.side-brand > span { flex: 1; min-width: 0; }
/* 收起按钮：贴着品牌行右端，平时很淡，hover 才显形 —— 常驻会一直和 logo 抢视线 */
.side-toggle {
  width: 26px; height: 26px; border-radius: 7px; flex-shrink: 0;
  border: 1px solid transparent; background: transparent; color: var(--ink-3);
  display: grid; place-items: center; cursor: pointer; transition: .16s;
}
.side-toggle svg { width: 15px; height: 15px; fill: none; stroke: currentColor;
                   stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round; }
.side-toggle:hover { background: #f2f4fb; border-color: var(--line); color: var(--ink); }

/* ---------- 收起态 ---------- */
.side-mini {
  height: 100%; display: flex; flex-direction: column;
  /* stretch 而不是 center：横向电池要撑满整个条宽，
     否则会缩成「百分比+图标」那么宽（实测只有 36px）。
     需要居中的元素（logo、展开箭头）自己写 margin:auto 居中。 */
  align-items: stretch;
  gap: 14px;
}
.mini-brand, .mini-toggle { align-self: center; }
.mini-nav { align-items: center; }
.mini-brand {
  width: 34px; height: 34px; border-radius: 50%; padding: 0; border: 0;
  background: transparent; cursor: pointer; flex-shrink: 0;
  transition: transform .2s cubic-bezier(.3,.6,.35,1);
}
.mini-brand img { width: 100%; height: 100%; border-radius: 50%; display: block; }
.mini-brand:hover { transform: scale(1.08); }

.mini-nav { display: flex; flex-direction: column; gap: 4px; width: 100%; }
.mini-item {
  position: relative;
  width: 100%; height: 42px; border: 0; border-radius: 11px;
  /* 收起态只剩图标，没有文字兜底，所以：
     1) 颜色比展开态深一档（展开态未选中是 --ink-2，这里用 --ink）
     2) 字号加大到 19px（原来是 17.5，在 64px 宽里显得小） */
  background: transparent; color: var(--ink); cursor: pointer;
  display: grid; place-items: center;
  transition: background .2s ease, color .2s ease, transform .16s cubic-bezier(.3,.6,.35,1);
}
.mini-item .ic {
  width: 21px; height: 21px;
  fill: none; stroke: currentColor;
  stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round;
  /* 线条图标没有文字兜底时全靠它撑住存在感 */
  filter: drop-shadow(0 1px 1px rgba(20,26,48,.08));
  transition: transform .18s cubic-bezier(.3,.6,.35,1);
}
.mini-item:hover { background: #eef1fa; }
.mini-item:hover .ic { transform: scale(1.08); }
.mini-item.on { background: var(--brand-soft); color: var(--brand-deep); }
.mini-item.on .ic { filter: drop-shadow(0 1px 2px rgba(68,83,214,.22)); }

/* ---- 空白点击区 ----
   收起态中间那大片留白现在是可点的：点一下展开。
   光秃秃一片看不出能点，所以：
   · 一条很淡的竖引导线，把这段「通道」和导航区分开
   · 中央常驻一个很淡的 » 箭头（不是只 hover 才出现）——
     常驻才叫「被发现」，只 hover 才出现等于没做，因为没人会去悬停一片空白。
     透明度压到 0.22，平时只是一点若有若无的痕迹，悬停时才完全亮起来。 */
.mini-gap {
  flex: 1; width: 100%; position: relative;
  display: grid; place-items: center;
  cursor: pointer; border-radius: 10px;
  transition: background .2s ease;
}
.mini-gap::before {
  content: '';
  position: absolute;
  width: 1px; height: 100%; max-height: 140px;
  background: linear-gradient(180deg, transparent, #d9deea 18%, #d9deea 82%, transparent);
  transition: background .22s ease;
}
.mini-gap:hover { background: #f7f9fd; }
.mini-gap:hover::before { background: linear-gradient(180deg, transparent, #c2c9dc 18%, #c2c9dc 82%, transparent); }
.gap-tip {
  position: relative;
  display: grid; place-items: center;
  /* 0.22 实测太淡（放大两倍才看得见）；0.45 采样 rgb(181,187,203) = 1.9:1；
     0.42 采样 rgb(157,163,180) = 2.5:1 —— 都不到非文字图形 3:1 的底线。
     这个滑块是 60px 宽的白底窄条，箭头再深就抢导航注意力了，
     所以不靠调透明度硬凑，改用「常驻但不刺眼 + 悬停立刻变实」：
     0.55 是实测刚好过 3:1 的值。 */
  opacity: .55; transform: translateX(-2px);
  transition: opacity .22s ease, transform .22s ease;
}
.gap-tip svg {
  width: 15px; height: 15px; fill: none; stroke: var(--ink-2);
  stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round;
}
.mini-gap:hover .gap-tip { opacity: 1; transform: translateX(2px); }

.mini-foot { display: flex; flex-direction: column;
             align-items: stretch; gap: 10px; padding-top: 6px; }
.mini-foot .mini-toggle { margin: 0 auto; }

/* ---- 电池：收起态的存储指示（横向，透明内部）----
   一件事只由一个视觉变量说：
   · 边框颜色 = 占用了多少（蓝 → 紫 → 琥珀 → 红）
   · 框内电流条长度 = 已用百分比
   · 框内文字 = 已用百分比数字（收起态空间不够放外面）
   内部刻意不填底色 —— 填了就会盖住电流的走向和流动感。 */
.mini-battery {
  border: 0; background: transparent; cursor: pointer;
  padding: 0; display: block; width: 100%;
  position: relative;   /* 正极伪元素的定位基准 */
  transition: transform .18s cubic-bezier(.3,.6,.35,1);
}
.mini-battery:hover { transform: translateY(-1px); }

/* 渐变边框的做法：两层叠加。
   外层是 conic 渐变当「颜料」，内层用背景色挖空中心，
   只剩 1.5px 的边 —— 这样边框本身就是渐变，且内部是透明的。
   直接用 border-image 会让 4 个圆角断掉（border-image 不跟 border-radius）。 */
.bat-shell {
  position: relative;
  display: flex; align-items: center; justify-content: center;
  width: 100%; height: 24px;
  border-radius: 9px;
  padding: 1.5px;                 /* 这就是边框粗细 */
  background: conic-gradient(from var(--bat-angle, 120deg),
              #6f8bff, #9a7cf8, #6f8bff, #9a7cf8, #6f8bff);
  overflow: visible;              /* 正极要露在右边 */
  transition: background .5s ease;
}
.bat-shell::before {
  content: '';
  position: absolute; inset: 1.5px;
  border-radius: 7.5px;
  background: #fff;               /* 挖空中心 = 内部透明（白底上的透明） */
}
/* 电池正极：横向后长在右边。挂在外层，避开 overflow */
.mini-battery::after {
  content: '';
  position: absolute; right: 0; top: 50%; transform: translateY(-50%);
  width: 3px; height: 8px;
  border-radius: 1px 3px 3px 1px;
  background: conic-gradient(from var(--bat-angle, 120deg), #6f8bff, #9a7cf8, #6f8bff);
  transition: background .5s ease;
}

/* 电流：从左侧流进来的能量条。宽度 = 已用比例。
   不是纯色块 —— 里面有 3~4 个小光点沿 x 轴循环游走，看着像电流在流。
   z-index 1 必须给：挖空用的 ::before 是伪元素，默认叠在普通子元素之上，
   不给层级电流就被白底盖住了。
   加一圈同色系描边（inset shadow）而不是填更深的底色：
   橙黄/浅紫在纯白上本来就淡，加深底色会把"透明感"做没；
   描边只压边缘，中间仍然透，但边界一下子清楚。 */
.bat-current {
  position: absolute; left: 2px; top: 2px; bottom: 2px;
  border-radius: 6px;
  overflow: hidden;
  background: linear-gradient(90deg,
              rgba(111,139,255,.62), rgba(154,124,248,.72));
  box-shadow: inset 0 0 0 1px rgba(111,139,255,.55);
  transition: width .55s cubic-bezier(.3,.55,.4,.95), background .4s ease,
              box-shadow .4s ease;
  z-index: 1;
  /* 右端斜切的宽度用 min(5px, 2%) 自适应：
     满格时末端已经贴到框的圆角上，再切 5px 就会在右下角露一块缺口
     （实测 100% 时确实露了）。用 min() 让它在左右空间不够时自动缩到 0，
     等于满格时自然退回圆角，不需要额外加类判断。 */
  clip-path: polygon(0 0, calc(100% - min(5px, 2%)) 0, 100% 50%,
                     calc(100% - min(5px, 2%)) 100%, 0 100%);
}

/* 数字压在电流上面 */
.bat-label {
  position: relative; z-index: 2;
  display: flex; align-items: center; gap: 3px;
  font-size: 10.5px; font-weight: 700; line-height: 1;
  color: #0f1526;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 3px rgba(255,255,255,.9), 0 1px 2px rgba(255,255,255,.8);
}
.bat-num i { font-style: normal; font-size: 8px; font-weight: 600; opacity: .8; }

/* 光点：沿电流方向循环游走。用 translateX + opacity，
   不用 background-position（在窄条里会跳格）。 */
.spark {
  position: absolute; top: 0; bottom: 0;
  width: 14px;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,.85), transparent);
  filter: blur(.5px);
  animation: spark-run 2.6s linear infinite;
}
.spark.s1 { animation-delay: 0s; }
.spark.s2 { animation-delay: .65s; width: 8px; opacity: .8; }
.spark.s3 { animation-delay: 1.3s; width: 18px; opacity: .65; }
.spark.s4 { animation-delay: 1.95s; width: 6px; opacity: .9; }
@keyframes spark-run {
  0%   { transform: translateX(-20px); opacity: 0; }
  18%  { opacity: 1; }
  82%  { opacity: 1; }
  100% { transform: translateX(260px); opacity: 0; }
}

/* 三档配色：占用越高边框越往暖色走。
   边框和正极用同一套渐变，保证是「同一根管子」。 */
.mini-battery.is-warn .bat-shell,
.mini-battery.is-warn::after {
  background: conic-gradient(from var(--bat-angle, 160deg),
              #f5c05c, #f0912b, #f5c05c, #f0912b, #f5c05c);
}
.mini-battery.is-warn .bat-current {
  background: linear-gradient(90deg, rgba(245,192,92,.62), rgba(240,145,43,.72));
  box-shadow: inset 0 0 0 1px rgba(232,145,43,.62);
}
.mini-battery.is-full .bat-shell,
.mini-battery.is-full::after {
  background: conic-gradient(from var(--bat-angle, 200deg),
              #f0836f, #e2574a, #f0836f, #e2574a, #f0836f);
}
.mini-battery.is-full .bat-current {
  background: linear-gradient(90deg, rgba(240,131,111,.62), rgba(226,87,74,.74));
  box-shadow: inset 0 0 0 1px rgba(226,87,74,.66);
}

.mini-toggle {
  width: 30px; height: 30px; border-radius: 9px;
  border: 1px solid transparent; background: transparent; color: var(--ink-2);
  display: grid; place-items: center; cursor: pointer;
  transition: background .16s, border-color .16s, color .16s, transform .16s;
}
.mini-toggle svg { width: 16px; height: 16px; fill: none; stroke: currentColor;
                   stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
.mini-toggle:hover { background: #eef1fa; border-color: var(--line); color: var(--ink); }
.mini-toggle:hover svg { transform: translateX(1.5px); }

/* 图标条上的悬停标签：64px 放不下文字，用 tooltip 补上名字。
   纯 CSS 实现，不引第三方库；写右边是因为侧栏在最左。 */
.mini-item::after,
.mini-toggle::after,
.mini-battery::after {
  content: attr(title);
  position: absolute; left: calc(100% + 10px); top: 50%;
  transform: translateY(-50%) translateX(-4px);
  background: rgba(22,25,42,.92); color: #fff;
  font-size: 12.5px; font-weight: 400;
  padding: 5px 10px; border-radius: 7px; white-space: nowrap;
  opacity: 0; pointer-events: none; z-index: 30;
  transition: opacity .16s ease, transform .16s ease;
}
.mini-item:hover::after,
.mini-toggle:hover::after,
.mini-battery:hover::after {
  opacity: 1; transform: translateY(-50%) translateX(0);
}
.mini-toggle, .mini-battery { position: relative; }
/* 收起态下，主区顶部那根分隔线不用再画（侧栏已经只剩一条窄边） */
.shell.side-collapsed .side-brand { padding-bottom: 16px; }

.new-btn { margin-top: 2px;
  width: 100%; margin-bottom: 16px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.new-btn .plus { font-size: 17px; line-height: 1; }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; }

.side-nav { display: flex; flex-direction: column; gap: 4px; }

/* 展开态的弹性空白：占掉导航与用户区之间的所有空间，
   同时是「点一下收起」的热区。不给底色、不放内容，
   视觉上它就是一段留白，但鼠标移上去有细微反馈说明可以点。 */
.side-gap {
  flex: 1; min-height: 24px; border-radius: 10px;
  cursor: pointer;
  transition: background .2s ease;
}
.side-gap:hover { background: #f4f6fc; }
.nav-item {
  display: flex; align-items: center; gap: 11px;
  /* 行高比浮起：之前 padding 9px、项间 gap 2px，四项挤成一坨，读着累 */
  padding: 11px 12px; border-radius: 10px;
  font-size: 15px; color: var(--ink-2); cursor: pointer;
  transition: background .18s, color .18s;
  /* 高亮靠背景+颜色平滑过渡，不是一闪就换 */
}
.nav-item:hover { background: #f6f7fc; color: var(--ink); }
.nav-item.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.nav-item .ic {
  width: 18px; height: 18px; flex-shrink: 0;
  fill: none; stroke: currentColor;
  stroke-width: 1.85; stroke-linecap: round; stroke-linejoin: round;
}

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
/* 展开态的存储条 = 收起态电池的放大版，同一套视觉语言：
   透明内部、渐变边框、框内电流。差别只在体积和「数字放外面」——
   展开后横向空间够，数字挪出去，框里就能完整看到电流在流。 */
.quota-bat {
  position: relative;
  display: flex; align-items: center;
  height: 30px;
  border-radius: 11px;
  padding: 1.5px;
  background: conic-gradient(from var(--bat-angle, 120deg),
              #6f8bff, #9a7cf8, #6f8bff, #9a7cf8, #6f8bff);
  overflow: visible;
  transition: background .5s ease;
}
.quota-bat::before {
  content: '';
  position: absolute; inset: 1.5px;
  border-radius: 9.5px;
  background: #fff;
}
/* 正极 */
.quota-bat::after {
  content: '';
  position: absolute; right: -4px; top: 50%; transform: translateY(-50%);
  width: 4px; height: 10px;
  border-radius: 1px 3px 3px 1px;
  background: conic-gradient(from var(--bat-angle, 120deg), #6f8bff, #9a7cf8, #6f8bff);
  transition: background .5s ease;
}
.quota-bat .bat-current { left: 3px; top: 3px; bottom: 3px; border-radius: 8px;
  clip-path: polygon(0 0, calc(100% - 6px) 0, 100% 50%, calc(100% - 6px) 100%, 0 100%); }

/* 三档：和收起态一一对应 */
.quota-bat.is-warn,
.quota-bat.is-warn::after {
  background: conic-gradient(from var(--bat-angle, 160deg),
              #f5c05c, #f0912b, #f5c05c, #f0912b, #f5c05c);
}
.quota-bat.is-warn .bat-current {
  background: linear-gradient(90deg, rgba(245,192,92,.62), rgba(240,145,43,.74));
}
.quota-bat.is-full,
.quota-bat.is-full::after {
  background: conic-gradient(from var(--bat-angle, 200deg),
              #f0836f, #e2574a, #f0836f, #e2574a, #f0836f);
}
.quota-bat.is-full .bat-current {
  background: linear-gradient(90deg, rgba(240,131,111,.42), rgba(226,87,74,.54));
}
.quota-num {
  display: flex; justify-content: space-between; align-items: baseline;
  font-size: 12px; color: var(--ink-2); margin-top: 7px;
  font-variant-numeric: tabular-nums;
}
/* 百分比单独一点强调：数值行之前斜杠+中点全糊在一起，扫不出来 */
.quota-pct { font-weight: 600; color: var(--brand-deep); }
/* 数值行左右两端分开放，不堆成「X / Y · Z%」一串：
   左边说已用多少，右边说总量，扫读时一眼抓住自己关心的那半。 */
.quota-used { color: var(--ink-2); }
.quota-total { color: var(--ink-3); }

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
