<script setup lang="ts">
// 生图参数面板：NovelAI 官方请求体参数的可视化编辑
// 全部字段都会原样进入 POST /ai/generate-image 的 parameters
import { computed, ref } from 'vue';
import {
  NOVELAI_SAMPLERS,
  SAMPLER_HINTS,
  NOVELAI_SCHEDULES,
  SIZE_PRESETS,
  UC_PRESETS,
  QUALITY_TAG_PRESETS,
  ARTIST_PRESET_EXAMPLES,
  defaultSamplerFor,
} from '../engine/novelai';
import { DEFAULT_PROMPT_TEMPLATE } from '../engine/imageGen';
import { useImageStore } from '../stores/image';
import type { ImageConfigEntry } from '../stores/image';

const imageStore = useImageStore();

const props = defineProps<{
  config: ImageConfigEntry;
  /** 精简模式：只显示常用参数（弹窗内用） */
  compact?: boolean;
}>();

const emit = defineEmits<{ changed: [] }>();

function patch(p: Partial<ImageConfigEntry>) {
  imageStore.updateConfig(props.config._uid, p);
  emit('changed');
}

const isNovelAI = computed(
  () => props.config.provider === 'novelai' || props.config.provider === 'novelai-relay',
);
const isV4 = computed(() => (props.config.model || props.config.novelaiModel || '').startsWith('nai-diffusion-4'));
const isV3 = computed(() => {
  const m = props.config.model || props.config.novelaiModel || '';
  return m.startsWith('nai-diffusion-3') || m.startsWith('nai-diffusion-furry-3');
});

const showTemplate = ref(false);
const showSamplerHelp = ref(false);

/** 采样器分组：常用 / 全部 */
const COMMON_SAMPLERS = [
  'k_euler_ancestral',
  'k_euler',
  'k_dpmpp_2m_sde',
  'k_dpmpp_2m',
  'k_dpmpp_2s_ancestral',
  'k_dpmpp_sde',
  'ddim',
];
const samplerOptions = computed(() => {
  const all = [...NOVELAI_SAMPLERS] as string[];
  const rest = all.filter((s) => !COMMON_SAMPLERS.includes(s));
  return { common: COMMON_SAMPLERS, rest };
});

function resetSampler() {
  const m = props.config.model || props.config.novelaiModel || '';
  patch({ sampler: defaultSamplerFor(m) });
}

function applySize(w: number, h: number) {
  patch({ width: w, height: h });
}

function randomizeSeed() {
  patch({ seed: Math.floor(Math.random() * 4294967295) });
}

function clearSeed() {
  patch({ seed: 0 });
}

function resetTemplate() {
  patch({ promptTemplate: DEFAULT_PROMPT_TEMPLATE });
}

/** 当前尺寸是否命中预设 */
function sizeActive(w: number, h: number) {
  return props.config.width === w && props.config.height === h;
}
</script>

<template>
  <div class="img-params">
    <!-- ============ 尺寸与构图 ============ -->
    <div class="group">
      <div class="group-title">🖼️ 尺寸与构图</div>
      <div class="preset-grid">
        <button
          v-for="p in SIZE_PRESETS"
          :key="p.label"
          class="preset-btn"
          :class="{ active: sizeActive(p.width, p.height) }"
          @click="applySize(p.width, p.height)"
        >
          {{ p.label }}
        </button>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>宽度（像素）</label>
          <input type="number" :value="config.width" min="64" max="2048" step="64"
            @change="patch({ width: Number(($event.target as HTMLInputElement).value) })" />
        </div>
        <div class="form-group">
          <label>高度（像素）</label>
          <input type="number" :value="config.height" min="64" max="2048" step="64"
            @change="patch({ height: Number(($event.target as HTMLInputElement).value) })" />
        </div>
      </div>
    </div>

    <!-- ============ 采样参数 ============ -->
    <div class="group">
      <div class="group-title">
        ⚙️ 采样参数
        <button class="mini-btn" @click="showSamplerHelp = !showSamplerHelp">
          {{ showSamplerHelp ? '收起说明' : '采样器说明' }}
        </button>
      </div>

      <div class="form-group">
        <label>
          采样器（Sampler）
          <button class="mini-btn" @click="resetSampler">恢复模型推荐</button>
        </label>
        <select
          :value="config.sampler"
          @change="patch({ sampler: ($event.target as HTMLSelectElement).value })"
        >
          <optgroup label="常用">
            <option v-for="s in samplerOptions.common" :key="s" :value="s">
              {{ s }} — {{ SAMPLER_HINTS[s] || '' }}
            </option>
          </optgroup>
          <optgroup label="全部（含 GPU / RTX 变体）">
            <option v-for="s in samplerOptions.rest" :key="s" :value="s">
              {{ s }} — {{ SAMPLER_HINTS[s] || '' }}
            </option>
          </optgroup>
        </select>
      </div>

      <div v-if="showSamplerHelp" class="hint-box">
        <p v-for="s in [...samplerOptions.common, ...samplerOptions.rest]" :key="s">
          <code>{{ s }}</code> — {{ SAMPLER_HINTS[s] || '无说明' }}
        </p>
      </div>

      <div class="form-row">
        <div class="form-group">
          <label>迭代步数（Steps）</label>
          <input type="number" :value="config.steps" min="1" max="100"
            @change="patch({ steps: Number(($event.target as HTMLInputElement).value) })" />
        </div>
        <div class="form-group">
          <label>提示词引导强度（CFG Scale）</label>
          <input type="number" :value="config.cfgScale" min="0" max="30" step="0.5"
            @change="patch({ cfgScale: Number(($event.target as HTMLInputElement).value) })" />
        </div>
      </div>

      <div class="form-group">
        <label>
          随机种子（Seed）
          <button class="mini-btn" @click="randomizeSeed">🎲 随机</button>
          <button class="mini-btn" @click="clearSeed">清零（每次随机）</button>
        </label>
        <input type="number" :value="config.seed" min="0"
          @change="patch({ seed: Number(($event.target as HTMLInputElement).value) })" />
        <p class="tip">相同种子 + 相同参数 = 相同图片；填 0 表示每次生成都换新种子。</p>
      </div>
    </div>

    <!-- ============ 提示词 ============ -->
    <div class="group">
      <div class="group-title">✍️ 提示词</div>

      <div class="form-group">
        <label>固定画师串（Artist Tags · 永远排在最前面）</label>
        <textarea
          rows="2"
          :value="config.artistTags"
          placeholder="artist:wlop, artist:ciloranko"
          @change="patch({ artistTags: ($event.target as HTMLTextAreaElement).value })"
        ></textarea>
        <div class="chip-row">
          <button
            v-for="a in ARTIST_PRESET_EXAMPLES"
            :key="a.label"
            class="chip"
            @click="patch({ artistTags: a.value })"
          >
            {{ a.label }}
          </button>
          <button class="chip" @click="patch({ artistTags: '' })">清空</button>
        </div>
        <!-- 已裁剪：原为 60 字的界面说明（只对开发者有意义） -->
      </div>

      <div class="form-group">
        <label>质量标签（Quality Tags · 排在画师串之后）</label>
        <input
          type="text"
          :value="config.qualityTags"
          placeholder="very aesthetic, masterpiece, best quality"
          @change="patch({ qualityTags: ($event.target as HTMLInputElement).value })"
        />
        <div class="chip-row">
          <button
            v-for="q in QUALITY_TAG_PRESETS"
            :key="q.label"
            class="chip"
            @click="patch({ qualityTags: q.value })"
          >
            {{ q.label }}
          </button>
        </div>
      </div>

      <div class="form-group">
        <label>固定负向提示词（Negative Prompt）</label>
        <textarea
          rows="3"
          :value="config.negativePrompt"
          placeholder="lowres, bad anatomy, bad hands, text, error..."
          @change="patch({ negativePrompt: ($event.target as HTMLTextAreaElement).value })"
        ></textarea>
        <p class="tip">
          仅当 LLM 优化没有给出负向提示词、且你没有手动填写时使用。
          NovelAI 官方 <code>ucPreset</code> 也会自动附加一份内置负面词。
        </p>
      </div>

      <div class="form-group">
        <label>
          给 LLM 的提示词模板
          <button class="mini-btn" @click="showTemplate = !showTemplate">
            {{ showTemplate ? '收起' : '展开编辑' }}
          </button>
          <button class="mini-btn" @click="resetTemplate">恢复默认</button>
        </label>
        <textarea
          v-if="showTemplate"
          rows="14"
          class="template-area"
          :value="config.promptTemplate"
          @change="patch({ promptTemplate: ($event.target as HTMLTextAreaElement).value })"
        ></textarea>
        <p v-else class="preview-box">{{ (config.promptTemplate || '').slice(0, 160) }}…</p>
        <p class="tip">
          可用占位符：<code v-text="'{{contextText}}'"></code> 场景正文、
          <code v-text="'{{contextLabel}}'"></code> 场景名称、
          <code v-text="'{{artistTags}}'"></code> 固定画师串。
        </p>
      </div>
    </div>

    <!-- ============ NovelAI 高级参数 ============ -->
    <div v-if="isNovelAI && !compact" class="group">
      <div class="group-title">🧪 NovelAI 高级参数（与官方请求体一致）</div>

      <div class="form-row">
        <div class="form-group">
          <label>内置负向标签档位（ucPreset）</label>
          <select
            :value="config.ucPreset"
            @change="patch({ ucPreset: Number(($event.target as HTMLSelectElement).value) })"
          >
            <option v-for="u in UC_PRESETS" :key="u.value" :value="u.value">{{ u.label }}</option>
          </select>
        </div>
        <div class="form-group">
          <label>噪声调度（Noise Schedule）</label>
          <select
            :value="config.noiseSchedule"
            @change="patch({ noiseSchedule: ($event.target as HTMLSelectElement).value })"
          >
            <option v-for="s in NOVELAI_SCHEDULES" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="form-group">
          <label>CFG 重缩放（cfg_rescale）</label>
          <input type="number" :value="config.cfgRescale" min="0" max="1" step="0.01"
            @change="patch({ cfgRescale: Number(($event.target as HTMLInputElement).value) })" />
        </div>
      </div>

      <div class="switch-row">
        <label class="switch">
          <input type="checkbox" :checked="config.qualityToggle"
            @change="patch({ qualityToggle: ($event.target as HTMLInputElement).checked })" />
          <span>自动追加质量标签（qualityToggle）</span>
        </label>
        <label class="switch">
          <input type="checkbox" :checked="config.dynamicThresholding"
            @change="patch({ dynamicThresholding: ($event.target as HTMLInputElement).checked })" />
          <span>动态阈值（Dynamic Thresholding）</span>
        </label>
        <label class="switch">
          <input type="checkbox" :checked="config.autoSmea"
            @change="patch({ autoSmea: ($event.target as HTMLInputElement).checked })" />
          <span>自动 SMEA（autoSmea）</span>
        </label>
        <template v-if="isV3">
          <label class="switch">
            <input type="checkbox" :checked="config.sm"
              @change="patch({ sm: ($event.target as HTMLInputElement).checked })" />
            <span>SMEA（sm）</span>
          </label>
          <label class="switch">
            <input type="checkbox" :checked="config.smDyn"
              @change="patch({ smDyn: ($event.target as HTMLInputElement).checked })" />
            <span>SMEA + 动态（sm_dyn）</span>
          </label>
        </template>
      </div>

      <div v-if="isV4" class="form-group">
        <label>图生图重绘强度（仅填了参考图时生效）</label>
        <input type="number" :value="config.denoisingStrength" min="0.01" max="1" step="0.01"
          @change="patch({ denoisingStrength: Number(($event.target as HTMLInputElement).value) })" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.img-params {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.group {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mini-btn {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--panel);
  border: 1px solid var(--border);
  color: var(--text-dim);
  cursor: pointer;
}
.mini-btn:hover {
  color: var(--text);
  border-color: var(--accent);
}
.preset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 6px;
}
.preset-btn {
  font-size: 11px;
  padding: 6px 8px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
  text-align: left;
}
.preset-btn:hover {
  border-color: var(--accent);
  color: var(--text);
}
.preset-btn.active {
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 18%, var(--panel));
  color: var(--text);
  font-weight: 600;
}
.form-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 130px;
}
.form-group label {
  font-size: 11px;
  color: var(--text-dim);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.form-group input,
.form-group select,
.form-group textarea {
  font-size: 12px;
  padding: 5px 7px;
}
.template-area {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  line-height: 1.5;
  resize: vertical;
}
.preview-box {
  font-size: 11px;
  color: var(--text-dim);
  background: var(--code-bg);
  padding: 6px 8px;
  border-radius: 4px;
  margin: 0;
  max-height: 60px;
  overflow: hidden;
}
.hint-box {
  font-size: 10px;
  color: var(--text-dim);
  background: var(--code-bg);
  border-radius: 4px;
  padding: 6px 8px;
  max-height: 160px;
  overflow-y: auto;
}
.hint-box p {
  margin: 2px 0;
}
.tip {
  font-size: 10px;
  color: var(--text-dim);
  margin: 0;
  line-height: 1.5;
}
.tip code {
  background: var(--code-bg);
  padding: 0 3px;
  border-radius: 3px;
}
.chip-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-dim);
  cursor: pointer;
}
.chip:hover {
  border-color: var(--accent);
  color: var(--text);
}
.switch-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text);
  cursor: pointer;
}
.switch input {
  width: auto;
}
</style>
