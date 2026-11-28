<template>
  <!-- 自绘下拉。
       为什么不用原生 <select>：那套白底灰边的浮层是浏览器自己画的，
       CSS 只能改「收起来的那一行」，弹出的列表完全管不到 ——
       在紫色主题的页面里像贴上来一块系统控件。
       自己画才能真正跟主题一致，也才能控制展开动画。 -->
  <div ref="rootEl" class="tsel" :class="{ open }">
    <button type="button" class="tsel-btn" :disabled="disabled"
            :aria-expanded="open" aria-haspopup="listbox"
            @click.stop="toggle">
      <span class="tsel-label">{{ current?.label ?? placeholder }}</span>
      <svg class="tsel-caret" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <!-- 用 transition 而不是 v-if：v-if 会让它「啪」地出现，
         过渡的目的就是让眼睛跟得上，所以必须常驻、只切类。 -->
    <div class="tsel-pop" role="listbox">
      <button v-for="(o, i) in options" :key="o.value" type="button"
              class="tsel-opt" :class="{ on: o.value === modelValue }"
              role="option" :aria-selected="o.value === modelValue"
              :style="{ '--i': i }"
              @click.stop="pick(o)">
        <span>{{ o.label }}</span>
        <svg v-if="o.value === modelValue" class="tsel-tick" viewBox="0 0 24 24">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },   // [{ value, label }]
  placeholder: { type: String, default: '请选择' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])

const rootEl = ref(null)
const open = ref(false)

const current = computed(() => props.options.find(o => o.value === props.modelValue))

function toggle() {
  if (props.disabled) return
  open.value = !open.value
}

function pick(o) {
  emit('update:modelValue', o.value)
  emit('change', o.value)
  open.value = false
}

/// 点外面关掉。用 pointerdown 而不是 click：
/// click 在按钮自身也会触发，会和 toggle 打架（刚开就被关）。
function onDocDown(e) {
  if (!open.value) return
  if (rootEl.value && !rootEl.value.contains(e.target)) open.value = false
}

function onEsc(e) {
  if (e.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocDown, true)
  document.addEventListener('keydown', onEsc)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocDown, true)
  document.removeEventListener('keydown', onEsc)
})
</script>

<style scoped>
.tsel { position: relative; }

.tsel-btn {
  width: 100%; height: 38px; padding: 0 10px 0 12px;
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  border: 1px solid var(--line); border-radius: 9px; background: #fbfcfe;
  font: inherit; font-size: 14px; color: var(--ink); cursor: pointer;
  transition: border-color .18s, background .18s, box-shadow .18s;
}
.tsel-btn:hover { border-color: #ced5ee; background: #fff; }
.tsel.open .tsel-btn,
.tsel-btn:focus-visible {
  border-color: #b9c4f5; background: #fff; outline: none;
  box-shadow: 0 0 0 3px rgba(91,108,255,.12);
}
.tsel-label {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tsel-caret {
  width: 14px; height: 14px; flex-shrink: 0;
  fill: none; stroke: var(--ink-3); stroke-width: 2;
  stroke-linecap: round; stroke-linejoin: round;
  transition: transform .24s cubic-bezier(.34,1.35,.64,1), stroke .18s;
}
.tsel.open .tsel-caret { transform: rotate(180deg); stroke: var(--brand); }

/* ---- 弹层 ----
   离场不能「直接消失」：卡片突然不见会让眼睛丢失位置。
   这里做三件事让它是「翻到背后」而不是「蒸发」：
   1. 起点是 scaleY(.86) + translateY(-6px) + 透明度 0 —— 像一张卡片翻出来
   2. 收起时反向缩回去（不是瞬间没）
   3. transform-origin 顶在按钮下沿，所以是「从上往下展开」 */
.tsel-pop {
  position: absolute; left: 0; right: 0; top: calc(100% + 6px);
  z-index: 40;
  padding: 5px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 11px;
  box-shadow: 0 2px 6px rgba(22,25,42,.06), 0 12px 30px rgba(22,25,42,.13);
  transform-origin: top center;

  opacity: 0;
  transform: scaleY(.86) translateY(-6px);
  visibility: hidden;
  pointer-events: none;
  transition:
    opacity .17s ease,
    transform .24s cubic-bezier(.34,1.28,.64,1),
    visibility 0s linear .24s;
}
.tsel.open .tsel-pop {
  opacity: 1;
  transform: scaleY(1) translateY(0);
  visibility: visible;
  pointer-events: auto;
  transition:
    opacity .19s ease,
    transform .28s cubic-bezier(.34,1.28,.64,1),
    visibility 0s;
}

.tsel-opt {
  width: 100%; padding: 8px 10px;
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  border: 0; border-radius: 7px; background: transparent;
  font: inherit; font-size: 13.5px; color: var(--ink-2);
  text-align: left; cursor: pointer;
  transition: background .14s, color .14s;
  /* 选项逐条淡入：整体翻出来的同时，条目还有一点点层次，
     比「整块同时出现」更耐看。延迟按索引递增，最多 5 条。 */
  opacity: 0;
  transform: translateY(-3px);
}
.tsel.open .tsel-opt {
  opacity: 1; transform: translateY(0);
  transition:
    opacity .18s ease calc(.03s + var(--i) * .022s),
    transform .2s ease calc(.03s + var(--i) * .022s),
    background .14s, color .14s;
}
.tsel-opt:hover { background: var(--brand-soft); color: var(--brand-deep); }
.tsel-opt.on { color: var(--brand-deep); font-weight: 600; background: #f6f7ff; }
.tsel-tick {
  width: 13px; height: 13px; flex-shrink: 0;
  fill: none; stroke: currentColor; stroke-width: 2.4;
  stroke-linecap: round; stroke-linejoin: round;
}

@media (prefers-reduced-motion: reduce) {
  .tsel-pop, .tsel.open .tsel-pop, .tsel-opt, .tsel.open .tsel-opt {
    transition-duration: .01ms;
  }
  .tsel-pop { transform: none; }
  .tsel-opt { opacity: 1; transform: none; }
}
</style>
