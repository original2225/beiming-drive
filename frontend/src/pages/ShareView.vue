<template>
  <div class="view-wrap">
    <div class="card view-card">
      <div class="brand-mini">
        <img src="/whale-flat-128.png" alt="logo" />
        <span>北冥云盘 · 分享</span>
      </div>

      <div v-if="loading" class="empty">加载中…</div>

      <div v-else-if="needPassword" class="pw-box">
        <p class="pw-tip">这个分享需要提取码</p>
        <div class="pw-row">
          <input class="input" v-model="password" placeholder="输入提取码" @keyup.enter="fetchShare" />
          <button class="btn btn-primary" @click="fetchShare">确定</button>
        </div>
        <div v-if="err" class="err">{{ err }}</div>
      </div>

      <template v-else-if="node">
        <div class="head">
          <div class="icon">{{ node.kind === 'dir' ? '📁' : '📄' }}</div>
          <div class="head-body">
            <div class="fname">{{ node.name }}</div>
            <div class="muted meta">
              {{ node.kind === 'dir' ? `${items.length} 个文件` : sizeText(node.size) }}
            </div>
          </div>
          <button v-if="loggedIn" class="btn btn-ghost" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存到我的网盘' }}
          </button>
          <button v-if="node.kind === 'file'" class="btn btn-primary" @click="download">
            下载文件
          </button>
        </div>
        <div v-if="saveMsg" class="save-msg" :class="saveMsg.kind">{{ saveMsg.text }}</div>

        <div v-if="node.kind === 'dir'" class="items">
          <div v-for="i in items" :key="i.id" class="item">
            <span class="ic">{{ i.kind === 'dir' ? '📁' : '📄' }}</span>
            <span class="nm">{{ i.name }}</span>
            <span class="sz muted">{{ i.kind === 'dir' ? '' : sizeText(i.size) }}</span>
          </div>
          <div v-if="items.length === 0" class="empty">这个文件夹是空的</div>
        </div>
      </template>

      <div v-else-if="err" class="err">{{ err }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { api, getToken } from '../api.js'

const route = useRoute()
const loading = ref(true)
const needPassword = ref(false)
const password = ref('')
const node = ref(null)
const items = ref([])
const err = ref('')

const saving = ref(false)
const saveMsg = ref(null)   // { kind: 'ok'|'err', text }
const loggedIn = computed(() => !!getToken())

const shareId = computed(() => route.params.id)

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B','KB','MB','GB','TB']; let i=0,n=b
  while (n >= 1024 && i < u.length-1) { n/=1024; i++ }
  return `${n.toFixed(n>=100||i===0?0:1)} ${u[i]}`
}

async function fetchShare() {
  err.value = ''
  loading.value = true
  try {
    const q = password.value ? `?password=${encodeURIComponent(password.value)}` : ''
    const res = await fetch(`/api/share/${shareId.value}${q}`)
    const json = await res.json()
    if (json.code !== 0) throw new Error(json.message || '访问失败')
    const d = json.data
    if (d.requiresPassword && !d.node) {
      needPassword.value = true
      node.value = null
    } else {
      needPassword.value = false
      node.value = d.node
      items.value = d.items || []
    }
  } catch (e) {
    err.value = e.message
  } finally {
    loading.value = false
  }
}

function download() {
  const q = password.value ? `?password=${encodeURIComponent(password.value)}` : ''
  window.open(`/api/share/${shareId.value}/dl${q}`, '_blank')
}

async function save() {
  saveMsg.value = null
  saving.value = true
  try {
    // targetParentId 传 null → 落到根目录。用户可在网盘里再挪。
    const r = await api.saveShare(shareId.value, password.value || null, null)
    const n = r.files != null && r.dirs != null ? `（${r.files} 个文件${r.dirs ? `、${r.dirs} 个目录` : ''}）` : ''
    saveMsg.value = { kind: 'ok', text: `已保存到你的网盘${n}，可在「我的文件」里查看。` }
  } catch (e) {
    const msg = e.message || '保存失败'
    // 普通用户转存文件夹会 403（文件夹只有管理员能建，这是既有规则）
    saveMsg.value = { kind: 'err', text: msg.includes('管理员') ? `${msg}（文件夹只有管理员能建，你可以让分享者重新分享文件，或下载后自己上传）` : msg }
  } finally {
    saving.value = false
  }
}

onMounted(fetchShare)
</script>

<style scoped>
.view-wrap {
  min-height: 100vh; display: grid; place-items: center; padding: 24px;
  background: radial-gradient(1000px 500px at 15% -10%, #e8ecff 0%, transparent 60%), var(--bg);
}
.view-card { width: 100%; max-width: 560px; padding: 26px 28px 30px; }
.brand-mini { display: flex; align-items: center; gap: 9px; font-size: 13px; color: var(--ink-2);
              font-weight: 600; margin-bottom: 22px; }
.brand-mini img { width: 26px; height: 26px; border-radius: 50%; }

.head { display: flex; align-items: center; gap: 14px; padding-bottom: 18px;
        border-bottom: 1px solid var(--line); }
.icon { font-size: 34px; }
.head-body { flex: 1; min-width: 0; }
.fname { font-size: 17px; font-weight: 600; word-break: break-all; }
.meta { font-size: 12.5px; margin-top: 4px; }

.items { margin-top: 8px; max-height: 380px; overflow-y: auto; }
.item { display: flex; align-items: center; gap: 11px; padding: 11px 4px;
        border-bottom: 1px solid #f1f3f8; font-size: 13.5px; }
.item:last-child { border-bottom: 0; }
.ic { font-size: 17px; }
.nm { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sz { font-size: 12px; }

.pw-box { text-align: center; padding: 14px 0 6px; }
.pw-tip { font-size: 14px; margin: 0 0 16px; color: var(--ink-2); }
.pw-row { display: flex; gap: 9px; max-width: 320px; margin: 0 auto; }
.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
       padding: 9px 12px; border-radius: 9px; font-size: 12.5px; margin-top: 14px; }
.btn-ghost { background: transparent; border: 1px solid var(--line); color: var(--ink-2);
             border-radius: 9px; padding: 8px 14px; font-size: 13px; cursor: pointer; }
.btn-ghost:hover:not(:disabled) { border-color: #cfd6ea; color: var(--ink); background: #f7f8fc; }
.btn-ghost:disabled { opacity: .6; cursor: default; }
.save-msg { margin-top: 12px; padding: 9px 12px; border-radius: 9px; font-size: 12.5px; }
.save-msg.ok { background: #f2f7f0; color: #3d7a3a; border: 1px solid #cfe6cb; }
.save-msg.err { background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2; }
</style>