<template>
  <div class="page">
    <h2>存储空间</h2>
    <p class="muted sub">看看空间都用在了哪儿</p>

    <!-- 骨架屏：先给「形状」再填数据，避免整块突然出现 -->
    <div v-if="loading" class="sk-wrap" aria-hidden="true">
      <div class="card sk-head">
        <div class="sk-line w30"></div>
        <div class="sk-bar"></div>
        <div class="sk-line w50 thin"></div>
      </div>
      <div class="card sk-body">
        <div v-for="n in 5" :key="n" class="sk-row">
          <div class="sk-dot"></div>
          <div class="sk-line w30 thin"></div>
          <div class="sk-line w15 thin"></div>
        </div>
      </div>
    </div>

    <template v-else-if="data">
      <!-- ===== 总览 ===== -->
      <div class="card head">
        <div class="head-top">
          <div>
            <div class="head-label muted">已用空间</div>
            <div class="head-num">{{ size(data.used) }}</div>
          </div>
          <div class="head-right">
            <div class="muted">共 {{ size(data.quota) }}</div>
            <div class="head-pct">{{ pct }}%</div>
          </div>
        </div>

        <!-- 配额条：只表示「用了总量的百分之几」。
             这里**不分段** —— 已用往往只占配额极小一部分（实测 0.1%），
             真按类型分段的话，所有分类会挤在最左边一个点，
             条形图就废了。构成由下面单独一条来表达。 -->
        <div class="quota-bar" role="img" :aria-label="'已用 ' + pct + '%'">
          <div class="quota-in" :style="{ width: Math.max(pct, pct > 0 ? 1 : 0) + '%' }"></div>
        </div>

        <!-- 构成条：按「已用空间内部的比例」归一化，压缩包占大头就是长条。
             这条才是回答「空间都用在哪」的图。 -->
        <div class="seg-title muted">空间构成</div>
        <div class="seg" role="img" :aria-label="'空间构成'">
          <div v-for="b in bucketShares" :key="b.key"
               class="seg-part"
               :class="'c-' + b.key"
               :style="{ width: b.width + '%' }"
               :title="b.label + ' · ' + size(b.bytes) + ' · ' + b.pct + '%'">
          </div>
        </div>

        <div class="legend">
          <span v-for="b in bucketsWithSize" :key="b.key" class="lg">
            <i class="dot" :class="'c-' + b.key"></i>{{ b.label }}
            <b>{{ size(b.bytes) }}</b>
            <span class="lg-pct">{{ sharePct(b) }}%</span>
          </span>
          <span v-if="data.trashBytes > 0" class="lg">
            <i class="dot c-trash"></i>回收站
            <b>{{ size(data.trashBytes) }}</b>
          </span>
        </div>
      </div>

      <!-- ===== 分类明细 ===== -->
      <div class="card list">
        <div class="list-head">
          <span>按类型占用</span>
          <span class="muted">占用 · 文件数</span>
        </div>

        <div v-if="bucketsWithSize.length === 0" class="empty">
          还没有上传任何文件
        </div>

        <div v-for="b in bucketsWithSize" :key="b.key" class="item">
          <i class="dot big" :class="'c-' + b.key"></i>
          <div class="item-main">
            <div class="item-name">{{ b.label }}</div>
            <div class="item-bar">
              <div class="item-bar-in" :class="'c-' + b.key"
                   :style="{ width: barOf(b) + '%' }"></div>
            </div>
          </div>
          <div class="item-num">
            <b>{{ size(b.bytes) }}</b>
            <span class="muted">{{ b.files }} 个</span>
          </div>
        </div>

        <!-- 回收站单独一栏：它不是一个「类型」，而是一种状态 ——
             这里用分隔线+浅底把它和上面的类型行视觉上分开，
             免得读成"回收站这种文件有 1 个"。 -->
        <div class="trash-divider muted">回收站（不属于文件类型，但同样占空间）</div>
        <div class="item trash-item">
          <i class="dot big c-trash"></i>
          <div class="item-main">
            <div class="item-name">回收站</div>
            <div class="item-hint muted">
              <template v-if="data.trashFiles > 0">
                清空可释放 {{ size(data.trashBytes) }}
              </template>
              <template v-else>空的，没有可释放的空间</template>
            </div>
          </div>
          <div class="item-num">
            <b>{{ size(data.trashBytes) }}</b>
            <span class="muted">{{ data.trashFiles }} 个</span>
          </div>
          <button v-if="data.trashFiles > 0" class="btn btn-sm"
                  :class="data.trashBytes >= 1048576 ? 'btn-danger' : 'btn-quiet'"
                  @click="goTrash">去清理</button>
        </div>
      </div>

      <!-- ===== 最占地方的文件 =====
           这一块同时解决两件事：页面下半截不再空，以及「想知道删哪个最省空间」
           这个真实需求 —— 只给分类占比，用户还是不知道该动哪个文件。 -->
      <div v-if="data.bigFiles?.length" class="card list">
        <div class="list-head">
          <span>最占地方的文件</span>
          <span class="muted">共 {{ data.bigFiles.length }} 项 · 点击打开所在目录</span>
        </div>
        <button v-for="f in data.bigFiles" :key="f.id" type="button"
                class="big-row" @click="openFile(f)">
          <i class="dot" :class="'c-' + f.bucket"></i>
          <span class="big-name" :title="f.path">{{ f.name }}</span>
          <span class="big-size">{{ size(f.size) }}</span>
        </button>
      </div>
    </template>

    <div v-else class="empty">读取失败，刷新试试</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const router = useRouter()
const data = ref(null)
const loading = ref(true)

function size(bytes) {
  const b = Number(bytes) || 0
  if (b < 1024) return b + ' B'
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = b / 1024, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[i]
}

const pct = computed(() => {
  const d = data.value
  if (!d || !d.quota) return 0
  return Math.min(100, Math.round((d.used / d.quota) * 1000) / 10)
})

/// 只保留有内容的分类，空分类不占版面（「视频 0 B」没意义）
const bucketsWithSize = computed(() =>
  (data.value?.buckets ?? []).filter(b => b.bytes > 0 || b.files > 0))

const sumBytes = computed(() =>
  bucketsWithSize.value.reduce((a, b) => a + b.bytes, 0))

/// 某个分类占「已用空间」的百分比。
/// 用它而不是占配额的比例 —— 后者永远接近 0，看着像没数据。
function sharePct(b) {
  if (sumBytes.value <= 0) return 0
  const v = (b.bytes / sumBytes.value) * 100
  // ≥10% 取整，但别四舍五入成 100 —— 99.7% 显示成 100% 会让人以为满了。
  if (v >= 10) {
    if (v < 99.95 && Math.round(v) >= 100) return 99
    return Math.round(v)
  }
  const r = Math.round(v * 10) / 10
  return r === 0 && v > 0 ? '<0.1' : r      // 别显示成 0，那看着像没有
}

/// 构成条的段宽：直接就是占已用的比例，加起来 100%。
/// 几乎不可见的给 0.8% 留一线颜色，不然「有 17 B」和「没有」长得一样。
const bucketShares = computed(() =>
  bucketsWithSize.value.map(b => ({
    ...b,
    pct: sharePct(b),
    width: Math.max(sharePct(b), b.bytes > 0 ? 0.8 : 0),
  })))

/// 明细条：同样按「占已用空间」画，和上面的构成条同一套尺度 ——
/// 之前按「相对最大那一类」算，压缩包 99.7% 却不满格，看着像数据错。
function barOf(b) {
  return Math.max(sharePct(b), b.bytes > 0 ? 1 : 0)
}

function goTrash() {
  router.push('/?view=trash')
}

/// 点大文件 → 回到文件列表打开它所在的目录。
/// 不做「直接删除」：误删风险太高，让用户在熟悉的列表里自己确认。
function openFile(f) {
  const dir = f.path.slice(0, f.path.lastIndexOf('/'))
  const q = dir && dir !== '' ? { dir } : {}
  router.push({ path: '/', query: q })
}

onMounted(async () => {
  try { data.value = await api.storage() } catch (e) { data.value = null }
  finally { loading.value = false }
})
</script>

<style scoped>
.page {
  flex: 1; display: flex; flex-direction: column;
  overflow-y: auto; padding: 30px var(--page-pad) 46px; min-height: 0;
}
/* 内容宽度上限由全局 styles/page-layout.css 统一给（随侧栏收放自适应） */
h2 { margin: 0 0 6px; font-size: 21px; font-weight: 600; color: var(--ink); }
.sub { margin: 0 0 18px; font-size: 13.5px; }
.muted { color: var(--ink-3); }
.card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px; margin-bottom: 14px;
}

/* ---------- 总览 ---------- */
.head-top { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 14px; }
.head-label { font-size: 13px; margin-bottom: 3px; }
.head-num { font-size: 26px; font-weight: 650; color: var(--ink); letter-spacing: -.3px; }
.head-right { text-align: right; font-size: 13px; }
.head-pct { font-size: 15px; font-weight: 600; color: var(--brand-deep); margin-top: 2px; }

/* 配额条：单色，只回答「用了总量的百分之几」。
   最小 1% 宽度保证「用了一点点」也能看见，而不是一条空槽。 */
.quota-bar {
  height: 11px; border-radius: 6px; background: #eef0f6; overflow: hidden;
}
.quota-in {
  height: 100%; border-radius: 6px;
  background: linear-gradient(90deg, var(--brand), #8b9bff);
  transition: width .5s cubic-bezier(.3,.7,.35,1);
}

.seg-title { font-size: 12.5px; margin: 14px 0 7px; }

/* 构成条：所有段首尾相接，圆角只在两端 */
.seg {
  display: flex; height: 11px; border-radius: 6px;
  background: #eef0f6; overflow: hidden;
}
.seg-part { height: 100%; transition: width .45s cubic-bezier(.3,.7,.35,1); }
.seg-part + .seg-part { box-shadow: inset 1px 0 0 rgba(255,255,255,.55); }

.legend {
  display: flex; flex-wrap: wrap; gap: 6px 16px;
  margin-top: 13px; font-size: 12.5px; color: var(--ink-2);
}
.lg { display: inline-flex; align-items: center; gap: 5px; }
.lg b { font-weight: 600; color: var(--ink); }
.lg-pct { color: var(--ink-3); font-size: 11.5px; }

.dot {
  width: 8px; height: 8px; border-radius: 3px;
  display: inline-block; flex-shrink: 0;
}
.dot.big { width: 10px; height: 10px; border-radius: 4px; }

/* 类型配色：同一套色用在这里和明细里，靠颜色就能对上 */
.c-image   { background: #5b6cff; }
.c-video   { background: #8b5cf6; }
.c-audio   { background: #ec4899; }
.c-doc     { background: #0ea5e9; }
.c-archive { background: #f59e0b; }
.c-code    { background: #10b981; }
.c-other   { background: #94a3b8; }
.c-trash   { background: #cbd5e1; }

/* ---------- 明细 ---------- */
.list { padding-bottom: 8px; }
.list-head {
  display: flex; justify-content: space-between;
  font-size: 13px; font-weight: 600; color: var(--ink-2);
  padding-bottom: 10px; border-bottom: 1px solid var(--line); margin-bottom: 4px;
}
.item {
  display: flex; align-items: center; gap: 11px;
  padding: 11px 2px; border-bottom: 1px solid #f2f4f9;
}
.item:last-child { border-bottom: 0; }
.item-main { flex: 1; min-width: 0; }
.item-name { font-size: 13.5px; color: var(--ink); margin-bottom: 6px; }
.item-bar { height: 5px; border-radius: 3px; background: #f0f2f8; overflow: hidden; }
.item-bar-in { height: 100%; border-radius: 3px; transition: width .5s cubic-bezier(.3,.7,.35,1); }
.item-num { text-align: right; min-width: 88px; font-size: 13px; }
.item-num b { display: block; font-weight: 600; color: var(--ink); }
.item-num span { font-size: 11.5px; }
.item-hint { font-size: 12.5px; }

.trash-divider {
  font-size: 11.5px; padding: 12px 2px 6px;
  border-top: 1px solid var(--line); margin-top: 6px;
}
.trash-item { padding-top: 8px; }
.trash-item .item-name { margin-bottom: 3px; }
.trash-item .btn { margin-left: 4px; }

.big-row {
  display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 9px 4px; border: 0; background: transparent; cursor: pointer;
  font: inherit; text-align: left; border-bottom: 1px solid #f2f4f9;
  border-radius: 6px; transition: background .15s ease;
}
.big-row:last-child { border-bottom: 0; }
.big-row:hover { background: #f7f8fc; }
.big-name {
  flex: 1; min-width: 0; font-size: 13.5px; color: var(--ink);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.big-size {
  font-size: 13px; font-weight: 600; color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}

.empty {
  padding: 34px 0; text-align: center; color: var(--ink-3); font-size: 13.5px;
}
/* 回收站只有几字节时用普通按钮，别用红色描边抢主视线 */
.btn-quiet { color: var(--ink-2); }
.btn-danger { color: #d64545; border-color: #f0cccc; }
.btn-danger:hover { background: #fdf3f3; }

/* ---------- 骨架 ---------- */
.sk-wrap { }
.sk-line { height: 11px; border-radius: 5px; background: #eef0f5; margin-bottom: 9px; }
.sk-line.thin { height: 9px; }
.w15 { width: 15%; } .w30 { width: 30%; } .w50 { width: 50%; }
.sk-bar { height: 11px; border-radius: 6px; background: #eef0f5; margin: 14px 0 10px; }
.sk-row { display: flex; align-items: center; gap: 11px; padding: 11px 0; }
.sk-dot { width: 10px; height: 10px; border-radius: 4px; background: #eef0f5; flex-shrink: 0; }
.sk-row .sk-line { margin: 0; }
.sk-head { margin-bottom: 14px; }
.sk-wrap .sk-line, .sk-wrap .sk-bar, .sk-wrap .sk-dot {
  animation: sk-pulse 1.3s ease-in-out infinite;
}
@keyframes sk-pulse { 0%,100% { opacity: .55 } 50% { opacity: 1 } }
</style>