<template>
  <div class="pub-wrap">
    <header class="pub-top">
      <div class="brand">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘</span>
        <em class="tag">公开内容</em>
      </div>
      <router-link class="to-login" to="/login">登录 / 注册 →</router-link>
    </header>

    <div class="pub-card">
      <div v-if="loading" class="empty">加载中…</div>

      <template v-else>
        <!-- 面包屑：只显示我们实际点进去的公开目录（不继承，所以只能看点击路径） -->
        <div class="crumbs">
          <button class="crumb" :class="{ cur: !trail.length }" @click="goRoot">公开内容</button>
          <template v-for="(t, idx) in trail" :key="t.id">
            <span class="sep">/</span>
            <button class="crumb" :class="{ cur: idx === trail.length - 1 }"
                    @click="goTo(idx)">{{ t.name }}</button>
          </template>
        </div>

        <div v-if="err" class="err">{{ err }}</div>

        <div v-else-if="!items.length" class="empty">
          这里还没有公开内容。
        </div>

        <div v-else class="items">
          <div v-for="it in items" :key="it.id" class="item"
               :class="{ dir: it.kind === 'dir' }"
               @click="it.kind === 'dir' ? enter(it) : null">
            <span class="ic">{{ it.kind === 'dir' ? '📁' : '📄' }}</span>
            <span class="nm" :title="it.name">{{ it.name }}</span>
            <span class="sz muted">{{ it.kind === 'dir' ? '文件夹' : sizeText(it.size) }}</span>
            <a v-if="it.kind === 'file'" class="dl" :href="`/api/public/nodes/${it.id}/dl`"
               @click.stop>下载</a>
            <span v-else class="enter-hint">进入 ›</span>
          </div>
        </div>
      </template>
    </div>

    <p class="foot muted">不登录只能浏览和下载公开文件。更多内容请登录。</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const loading = ref(true)
const err = ref('')
const items = ref([])
const trail = ref([])   // 面包屑栈：{ id, name }

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0, n = b
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}

async function load(parentId) {
  loading.value = true
  err.value = ''
  try {
    const q = parentId != null ? `?parentId=${parentId}` : ''
    const res = await fetch(`/api/public/nodes${q}`)
    const json = await res.json()
    if (json.code !== 0) throw new Error(json.message || '加载失败')
    items.value = json.data.items || []
  } catch (e) {
    err.value = e.message
    items.value = []
  } finally {
    loading.value = false
  }
}

function goRoot() { trail.value = []; load(null) }
function enter(it) { trail.value = [...trail.value, { id: it.id, name: it.name }]; load(it.id) }
function goTo(idx) { trail.value = trail.value.slice(0, idx + 1); load(trail.value[idx].id) }

onMounted(() => load(null))
</script>

<style scoped>
.pub-wrap {
  min-height: 100vh; padding: 0 0 40px;
  background: radial-gradient(900px 460px at 12% -8%, #e8ecff 0%, transparent 58%), var(--bg);
}
.pub-top {
  display: flex; align-items: center; justify-content: space-between;
  max-width: 760px; margin: 0 auto; padding: 22px 24px 10px;
}
.brand { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 700; color: var(--ink); }
.brand img { width: 28px; height: 28px; border-radius: 50%; }
.brand .tag { font-size: 11.5px; font-weight: 600; color: var(--brand);
              background: var(--brand-soft); border-radius: 999px; padding: 2px 9px; }
.to-login { font-size: 13px; color: var(--brand); text-decoration: none; font-weight: 600; }
.to-login:hover { text-decoration: underline; }

.pub-card {
  max-width: 760px; margin: 6px auto 0; background: var(--card);
  border: 1px solid var(--line); border-radius: 16px; padding: 20px 24px 24px;
  box-shadow: 0 12px 36px rgba(20, 26, 48, .06);
}
.crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-bottom: 14px;
          padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.crumb { border: 0; background: transparent; color: var(--ink-2); font-size: 13px;
         padding: 3px 6px; border-radius: 7px; cursor: pointer; max-width: 200px;
         overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.crumb:hover { background: #f3f5fd; color: var(--ink); }
.crumb.cur { color: var(--ink); font-weight: 600; }
.sep { color: var(--ink-3); font-size: 12px; }

.items { display: flex; flex-direction: column; }
.item { display: flex; align-items: center; gap: 12px; padding: 12px 6px;
        border-bottom: 1px solid #f1f3f8; font-size: 13.5px; border-radius: 8px; }
.item:last-child { border-bottom: 0; }
.item.dir { cursor: pointer; }
.item.dir:hover { background: #f7f8fc; }
.ic { font-size: 18px; flex-shrink: 0; }
.nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sz { font-size: 12px; flex-shrink: 0; }
.dl { font-size: 12.5px; color: var(--brand); font-weight: 600; text-decoration: none;
      padding: 4px 10px; border: 1px solid var(--brand-soft); border-radius: 8px; flex-shrink: 0; }
.dl:hover { background: var(--brand-soft); }
.enter-hint { font-size: 12.5px; color: var(--ink-3); flex-shrink: 0; }

.empty { text-align: center; color: var(--ink-3); font-size: 13.5px; padding: 32px 0; }
.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
       padding: 10px 12px; border-radius: 9px; font-size: 12.5px; }
.foot { max-width: 760px; margin: 16px auto 0; padding: 0 24px; text-align: center; font-size: 12px; }
.muted { color: var(--ink-3); }
</style>
