<script setup lang="ts">
// 资产总览页：预设 / 世界书 / 正则 / 角色卡
//
// 交互模型（按用户要求重做）：
//   顶部切换大类 → 下面列出该类所有总条目
//   每个总条目点开 → 子条目逐条列出，每条都能单独开关、单独展开修改
//   还能一键全部展开 / 全部收起
import { ref, computed, onMounted } from 'vue';
import { assetCount } from '../lib/imageAssets';
import { useAssetsStore } from '../stores/assets';
import { useDnaStore } from '../stores/characterDna';
import { looksLikePng, readCardFromPng, readRawJsonFromPng, readFileAsBytes } from '../lib/pngCard';
import AssetSubEntry from '../components/AssetSubEntry.vue';
import GalleryPanel from '../components/GalleryPanel.vue';
import AppIcon from '../components/AppIcon.vue';
import { detectKind } from '../engine/stImport';
import { createRegexGroup } from '../engine/regexGroups';
import { ST_REGEX_PLACEMENTS } from '../engine/worldbookOptions';
import { uid } from '../lib/storage';
import type { ImportedItem } from '../stores/assets';

const assets = useAssetsStore();
const dna = useDnaStore();

/**
 * 自己把数据加载起来。
 *
 * 这一页以前**没有**这一步，靠别的页面（设置页 / 冒险页）顺手把资产读进
 * Pinia store。于是"直接进资产页"或"刷新后停在资产页"就会看到全部为 0、
 * 列表空空如也 —— 数据其实好好的，只是没人去读。
 * （DNA 显示正常是因为它在 main.ts 里单独加载过，对比之下更像"数据没了"。）
 */
const loading = ref(true);
onMounted(async () => {
  try {
    await Promise.all([assets.load(), dna.load()]);
  } catch (e) {
    console.error('[资产] 加载失败：', e);
  } finally {
    loading.value = false;
  }
});

type Tab = 'worldbook' | 'preset' | 'regex' | 'character' | 'dna' | 'gallery';
const tab = ref<Tab>('worldbook');

const KIND_LABEL: Record<string, string> = {
  worldbook: '世界书',
  preset: '预设',
  regex: '正则脚本',
  character: '角色卡',
  dna: '角色 DNA',
  gallery: '图片库',
  unknown: '无法识别',
};
const TAB_ICON: Record<Tab, string> = {
  worldbook: 'book',
  preset: 'settings',
  regex: 'sliders',
  character: 'user',
  dna: 'dna',
  gallery: 'image',
};

// ---------------- 展开状态 ----------------
const openItems = ref<Set<string>>(new Set());
const allOpen = ref(false);

function toggleItem(uid: string) {
  const s = new Set(openItems.value);
  if (s.has(uid)) s.delete(uid);
  else s.add(uid);
  openItems.value = s;
}
function isOpen(uid: string) {
  return allOpen.value || openItems.value.has(uid);
}
function toggleAll() {
  allOpen.value = !allOpen.value;
  if (!allOpen.value) openItems.value = new Set();
}

// ---------------- 导入 ----------------
const importText = ref('');
const importResults = ref<ImportedItem[] | null>(null);
const importError = ref('');
const detecting = ref<{ kind: string; reason: string; count?: number } | null>(null);
const showImport = ref(false);

/**
 * 选文件导入。
 *
 * 分两条路：
 *   · PNG（按**文件头**判断，不看扩展名 —— 扩展名经常是错的）
 *     → 角色卡可能整张图就是一张卡，数据在 tEXt chunk 里。
 *       这时不能走 file.text()（二进制会变乱码），要读字节再解析。
 *   · 其它（JSON 文本）
 *     → 照旧读成文本、解析、自动识别类型。
 */
function onFilePick(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  importError.value = '';
  detecting.value = null;

  void readFileAsBytes(file)
    .then(async (bytes) => {
      if (looksLikePng(bytes)) {
        const res = readCardFromPng(bytes);
        if (!res.ok) {
          /* 也试试底层读法：有些非标准导出的卡 fromPNG 不认，但 JSON 是好的 */
          const raw = readRawJsonFromPng(bytes);
          if (raw) {
            await importPngCard(raw, undefined, file.name);
            return;
          }
          importError.value = res.error || 'PNG 解析失败';
          return;
        }
        await importPngCard(res.card, res.characterBook, res.name || file.name);
        return;
      }
      /* 不是 PNG → 当 JSON 文本处理 */
      return file.text().then((t) => {
        importText.value = t;
        detectFromText();
      });
    })
    .catch((err) => {
      importError.value = `读取文件失败：${err instanceof Error ? err.message : String(err)}`;
    });
}

/**
 * 把 PNG 里读出来的角色卡存进库。
 *
 * 两个细节：
 *  1. 卡里内嵌的世界书（character_book）也一起存成一个世界书 ——
 *     很多卡把世界观放在里边，只导角色卡就把设定丢了。
 *  2. 导入后直接跳到角色卡标签页、并把新卡设成当前角色，
 *     否则用户点完导入"什么都没发生"（在别的标签页看不到）。
 */
async function importPngCard(card: unknown, book: unknown, name: string) {
  try {
    const results: ImportedItem[] = [];
    const obj = await assets.importCharacter(card);
    results.push({ ok: true, kind: 'character', name: obj.name || name } as ImportedItem);

    let bookName = '';
    if (book) {
      /* 内嵌世界书起个能认出来的名字：<卡名> · 内置世界书 */
      const wb = await assets.importWorldBook(book);
      bookName = wb.name || '';
      results.push({ ok: true, kind: 'worldbook', name: bookName } as ImportedItem);
    }

    assets.currentCharacterId = obj._uid;
    await assets.save();

    importResults.value = results;
    tab.value = 'character';
    importText.value = '';
    detecting.value = null;
  } catch (e) {
    importError.value = `导入 PNG 角色卡失败：${e instanceof Error ? e.message : String(e)}`;
  }
}

function detectFromText() {
  importError.value = '';
  detecting.value = null;
  const text = importText.value.trim();
  if (!text) return;
  try {
    const raw = JSON.parse(text);
    const d = detectKind(raw);
    detecting.value = {
      kind: KIND_LABEL[d.kind] || d.kind,
      reason: d.reason,
      count: Array.isArray(raw) ? raw.length : d.count,
    };
  } catch (e) {
    importError.value = `JSON 解析失败：${e instanceof Error ? e.message : String(e)}`;
  }
}

async function doImport() {
  importError.value = '';
  importResults.value = null;
  try {
    const raw = JSON.parse(importText.value);
    const results = await assets.importAnyJson(raw);
    importResults.value = results;
    importText.value = '';
    detecting.value = null;
    const first = results.find((r) => r.ok && r.kind !== 'unknown');
    if (first) tab.value = first.kind as Tab;
  } catch (e) {
    importError.value = `导入失败：${e instanceof Error ? e.message : String(e)}`;
  }
}

// ---------------- 数据形状归一 ----------------
function toEntryArray(entries: any): any[] {
  if (Array.isArray(entries)) return entries;
  if (entries && typeof entries === 'object') {
    return Object.entries(entries).map(([uid, e]: [string, any]) => ({ uid, ...e }));
  }
  return [];
}

function presetPrompts(p: any): any[] {
  const raw = p?.prompts;
  if (Array.isArray(raw)) return raw;
  if (raw && typeof raw === 'object') return Object.values(raw);
  return [];
}

function characterData(c: any) {
  return c?.data && typeof c.data === 'object' ? c.data : c;
}

const items = computed<any[]>(() => {
  if (tab.value === 'worldbook') return assets.worldBooks;
  if (tab.value === 'preset') return assets.presets;
  if (tab.value === 'regex') return assets.regexGroups;
  if (tab.value === 'dna') return dna.items;
  return assets.characters;
});

const tabCounts = computed<Record<Tab, number>>(() => ({
  worldbook: assets.worldBooks.length,
  preset: assets.presets.length,
  regex: assets.regexGroups.length,
  character: assets.characters.length,
  dna: dna.items.length,
  // 图片库：从一级 tab 下放到这里当子页，数量用图片资产库的条数
  gallery: assetCount.value,
}));

function subCount(item: any): number {
  if (tab.value === 'worldbook') return toEntryArray(item.entries).length;
  if (tab.value === 'preset') return presetPrompts(item).length;
  if (tab.value === 'regex') return (item.scripts || []).length;
  if (tab.value === 'dna') return (item.dna || '').split(',').filter((s: string) => s.trim()).length;
  return 1;
}

function isEnabled(item: any): boolean {
  if (tab.value === 'preset') return assets.currentPresetId === item._uid;
  if (tab.value === 'character') return assets.currentCharacterId === item._uid;
  if (tab.value === 'worldbook') return assets.currentWorldBookIds.includes(item._uid);
  if (tab.value === 'dna') return item.enabled !== false;
  if (tab.value === 'regex') return assets.currentRegexGroupIds.includes(item._uid);
  return false;
}

async function setEnabled(item: any, on: boolean) {
  if (tab.value === 'preset') await assets.setCurrentPreset(on ? item._uid : undefined);
  else if (tab.value === 'character') await assets.setCurrentCharacter(on ? item._uid : undefined);
  else if (tab.value === 'worldbook') await assets.toggleWorldBook(item._uid, on);
  else if (tab.value === 'dna') await dna.update(item._uid, { enabled: on });
  else if (tab.value === 'regex') await assets.toggleRegexGroup(item._uid, on);
}

/** 正则组里某条脚本的开关 */
async function toggleScriptInGroup(group: any, script: any, on: boolean) {
  await assets.toggleRegexScriptInGroup(group._uid, script.id, on);
}

/** 组内单条脚本的展开状态（key = 组id:序号） */
const openScripts = ref<Set<string>>(new Set());
function scriptKey(groupUid: string, index: number) {
  return `${groupUid}:${index}`;
}
function isScriptOpen(groupUid: string, index: number) {
  return openScripts.value.has(scriptKey(groupUid, index));
}
function toggleScriptOpen(groupUid: string, index: number) {
  const s = new Set(openScripts.value);
  const k = scriptKey(groupUid, index);
  if (s.has(k)) s.delete(k);
  else s.add(k);
  openScripts.value = s;
}

/** 切换单条脚本的生效位置 */
function toggleScriptPlacement(script: any, value: number) {
  const list: number[] = Array.isArray(script.placement) ? [...script.placement] : [];
  script.placement = list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
  void saveAll();
}

async function saveAll() {
  await assets.save();
}

// ---------------- 改名 / 复制 / 删除 / 新建 ----------------
const renamingUid = ref<string | null>(null);
const renameValue = ref('');

function titleOf(item: any): string {
  if (tab.value === 'worldbook') return item.name || '(未命名世界书)';
  if (tab.value === 'preset') return item.name || item.preset_name || '(未命名预设)';
  if (tab.value === 'regex') return item.scriptName || item.name || '(未命名正则)';
  if (tab.value === 'dna') return item.name || '(未命名 DNA)';
  return item.name || item.data?.name || '(未命名角色)';
}

function startRename(item: any) {
  renamingUid.value = item._uid;
  renameValue.value = titleOf(item);
}

async function commitRename(item: any) {
  const name = renameValue.value.trim();
  renamingUid.value = null;
  if (!name) return;
  if (tab.value === 'worldbook') item.name = name;
  else if (tab.value === 'preset') {
    item.name = name;
    if (item.preset_name !== undefined) item.preset_name = name;
  } else if (tab.value === 'regex') {
    item.scriptName = name;
    if (item.name !== undefined) item.name = name;
  } else if (tab.value === 'dna') {
    await dna.update(item._uid, { name });
    return;
  } else {
    item.name = name;
    if (item.data && typeof item.data === 'object') item.data.name = name;
  }
  await saveAll();
}

async function duplicate(item: any) {
  if (tab.value === 'dna') {
    await dna.duplicate(item._uid);
    return;
  }
  const copy = JSON.parse(JSON.stringify(item));
  copy._uid = uid();
  const base = titleOf(item);
  if (tab.value === 'worldbook') {
    copy.name = `${base} 副本`;
    assets.worldBooks.push(copy);
  } else if (tab.value === 'preset') {
    copy.name = `${base} 副本`;
    if (copy.preset_name !== undefined) copy.preset_name = copy.name;
    assets.presets.push(copy);
  } else if (tab.value === 'regex') {
    copy.name = `${base} 副本`;
    // 组内脚本要换新 id，否则复制出来的组两条引用同一个脚本对象
    copy.scripts = (copy.scripts || []).map((s: any) => ({ ...s, id: uid() }));
    assets.regexGroups.push(copy);
  } else {
    copy.name = `${base} 副本`;
    assets.characters.push(copy);
  }
  await saveAll();
}

async function remove(item: any) {
  if (!window.confirm(`删除「${titleOf(item)}」？不可撤销（本地文件有备份）。`)) return;
  if (tab.value === 'worldbook') await assets.removeWorldBook(item._uid);
  else if (tab.value === 'preset') await assets.removePreset(item._uid);
  else if (tab.value === 'regex') await assets.removeRegexGroup(item._uid);
  else if (tab.value === 'dna') await dna.remove(item._uid);
  else await assets.removeCharacter(item._uid);
}

async function clearAll() {
  const n = items.value.length;
  if (n === 0) return;
  if (!window.confirm(`确定删除全部 ${n} 个${KIND_LABEL[tab.value]}？不可撤销（本地文件有备份）。`)) return;
  if (tab.value === 'worldbook') {
    assets.worldBooks = [];
    assets.currentWorldBookIds = [];
  } else if (tab.value === 'preset') {
    assets.presets = [];
    assets.currentPresetId = undefined;
  } else if (tab.value === 'regex') {
    assets.regexGroups = [];
    assets.currentRegexGroupIds = [];
  } else if (tab.value === 'dna') {
    dna.items = [];
    await dna.save();
    return;
  } else {
    assets.characters = [];
    assets.currentCharacterId = undefined;
  }
  await saveAll();
}

async function createNew() {
  if (tab.value === 'worldbook') {
    const wb = {
      _uid: uid(),
      _kind: 'worldbook',
      name: '新世界书',
      description: '',
      entries: [
        {
          uid: String(Date.now()),
          comment: '新条目',
          content: '',
          keys: [],
          key: [],
          constant: false,
          order: 100,
          position: 0,
          depth: 4,
          probability: 100,
          disabled: false,
        },
      ],
    };
    assets.worldBooks.push(wb);
    assets.currentWorldBookIds.push(wb._uid);
    openItems.value = new Set([...openItems.value, wb._uid]);
    await saveAll();
  } else if (tab.value === 'regex') {
    // 建一个组，里面先放一条空脚本
    const g = createRegexGroup('新正则组', 'manual');
    g._uid = uid();
    g.scripts = [
      {
        id: uid(),
        scriptName: '新正则',
        findRegex: '',
        replaceString: '',
        trimStrings: [],
        placement: [2],
        disabled: false,
        markdownOnly: false,
        promptOnly: false,
        runOnEdit: false,
        substituteRegex: 0,
        minDepth: null,
        maxDepth: null,
      },
    ];
    assets.regexGroups.push(g);
    assets.currentRegexGroupIds.push(g._uid);
    openItems.value = new Set([...openItems.value, g._uid]);
    await saveAll();
  } else if (tab.value === 'dna') {
    const d = await dna.add('新角色');
    openItems.value = new Set([...openItems.value, d._uid]);
  } else {
    window.alert('预设和角色卡请用上面的导入区导入（它们需要完整的原始结构）。');
  }
}

// ---------------- 角色卡字段 ----------------
const charFieldCache = ref<Record<string, any[]>>({});

function charFields(c: any): any[] {
  const key = c._uid;
  if (!charFieldCache.value[key]) {
    const d = characterData(c);
    charFieldCache.value[key] = [
      { label: '描述', key: 'description', value: d?.description || '' },
      { label: '性格', key: 'personality', value: d?.personality || '' },
      { label: '场景', key: 'scenario', value: d?.scenario || '' },
      { label: '开场白', key: 'first_mes', value: d?.first_mes || '' },
      { label: '对话示例', key: 'mes_example', value: d?.mes_example || '' },
    ];
  }
  return charFieldCache.value[key];
}

async function onCharFieldChange(c: any, f: any) {
  const d = characterData(c);
  if (d) d[f.key] = f.value;
  if (c.data && typeof c.data === 'object') c.data = d;
  await saveAll();
}
</script>

<template>
  <div class="assets-page">
    <!--
      顶部栏做成**吸顶应用栏**。
      
      之前是普通的流式标题栏（跟着内容滚走），而且右边三个按钮在手机上
      被挤成一行、最右边那个还会被切掉 —— 看着就像个没适配好的网页后台。
      现在：固定在顶部、玻璃质感、按钮改成圆形图标，不再有文字溢出问题。
    -->
    <header class="app-bar">
      <h2><AppIcon name="assets" :size="20" class="head-ico" />资产</h2>
      <div class="bar-actions">
        <!--
          用文字按钮而不是纯 emoji 图标：
          ① 📥 / ⇲ 这类字符在不同安卓机型上字形差异很大（实测有台机器把 📥
             渲染成了完全不同的图形），文字则各平台一致；
          ② 光一个符号用户猜不出是干什么的，写成「导入 / 展开」一眼就懂；
          ③ 三个按钮都是短词，手机宽度完全放得下。
        -->
        <button class="text-btn" :class="{ on: showImport }" @click="showImport = !showImport">
          {{ showImport ? '收起' : '导入' }}
        </button>
        <button class="text-btn" @click="toggleAll">{{ allOpen ? '收起' : '展开' }}</button>
        <button v-if="tab === 'worldbook' || tab === 'regex' || tab === 'dna'" class="new-btn" @click="createNew">
          ＋ 新建
        </button>
      </div>
    </header>

    <!--
      分类：横向滚动的分段控件（segmented control）。
      
      之前是 flex-wrap 的多行标签，6 个类别占了三行 —— 那是网页导航的观感。
      现在单行横滚，选中项有实底高亮，和手机原生 App 的标签切换一致。
    -->
    <nav class="cat-tabs">
      <button v-for="t in (['worldbook', 'preset', 'regex', 'character', 'dna', 'gallery'] as Tab[])" :key="t"
        :class="{ active: tab === t }" @click="tab = t; openItems = new Set(); allOpen = false">
        <span class="ico"><AppIcon :name="TAB_ICON[t]" :size="16" /></span>
        <span class="lab">{{ KIND_LABEL[t] }}</span>
        <span class="count">{{ tabCounts[t] }}</span>
      </button>
    </nav>

    <!-- 导入区 -->
    <div v-show="showImport" class="import-card">
      <p class="hint">
        预设 / 世界书 / 正则 / 角色卡的 JSON 直接粘进来或选文件，
        <strong>不用先选类型</strong>，自动识别；一个数组里混装多种也会分别导入。<br />
        <strong>角色卡 PNG 也能直接选</strong> —— ST 生态里很多卡是一张图片，
        数据藏在图里（chara / ccv3 块）；卡里内嵌的世界观会一并导入。
      </p>
      <input type="file" accept=".json,.png,application/json,image/png" @change="onFilePick" />
      <textarea v-model="importText" rows="4" class="mono"
        placeholder='粘贴 JSON，例如 {"entries":{"0":{"key":["魔法"],"content":"..."}}}'
        @input="detectFromText"></textarea>
      <p class="hint" style="margin: 4px 0 0;">
        选 PNG 时上面的文本框不参与（图是二进制的），直接解析并入库。
      </p>
      <div v-if="detecting" class="detect">
        🔎 识别为 <strong>{{ detecting.kind }}</strong>
        <span class="dim">（{{ detecting.reason }}{{ detecting.count ? `，${detecting.count} 项` : '' }}）</span>
      </div>
      <div v-if="importError" class="err">{{ importError }}</div>
      <div class="row">
        <button class="primary" :disabled="!importText.trim()" @click="doImport">开始导入</button>
        <button v-if="importText" @click="importText = ''; detecting = null">清空</button>
      </div>
      <div v-if="importResults" class="results">
        <div class="results-head">
          导入结果：成功 {{ importResults.filter((r) => r.ok).length }} / 共 {{ importResults.length }}
        </div>
        <div v-for="(r, i) in importResults" :key="i" class="result-row" :class="{ bad: !r.ok }">
          <span class="badge">{{ KIND_LABEL[r.kind] || r.kind }}</span>
          <span>{{ r.name }}</span>
          <span v-if="r.ok" class="ok">✅</span>
          <span v-else>❌ {{ r.error }}</span>
          <div v-if="r.notes?.length" class="notes">{{ r.notes.join(' · ') }}</div>
        </div>
      </div>
    </div>

    <!--
      图片库：**不是**资产列表，是图片网格。
      所以整个列表区换掉，而不是塞进 items 里
      （以前 gallery 落到 items 的默认分支、返回的是角色卡，点进去看到的是角色列表）。
    -->
    <GalleryPanel v-if="tab === 'gallery'" />

    <template v-else>
    <p v-if="tab === 'dna'" class="hint">
      角色 DNA 用来<strong>锚定角色的固定外观</strong>。里面写英文描述（建议直接写 Danbooru 标签），
      生图时会和提示词模板一起发给大模型，并要求它不得改动这些特征。<br>
      在生图弹窗里可以勾选多条；标了<strong>「常驻」</strong>的那条，自动配图（AI 自己选位置插图）也会带上。
    </p>

    <div class="toolbar">
      <span class="dim">
        共 {{ items.length }} 个{{ KIND_LABEL[tab] }} ·
        <template v-if="tab === 'preset'">开关＝设为当前预设</template>
        <template v-else-if="tab === 'character'">开关＝设为当前角色</template>
        <template v-else>开关＝启用</template>
      </span>
      <button v-if="items.length > 1" class="danger" @click="clearAll">删除全部</button>
    </div>

    <!-- 列表 -->
    <div class="list">
      <div v-for="item in items" :key="item._uid" class="card" :class="{ on: isEnabled(item), open: isOpen(item._uid) }">
        <!--
          整行可点：点击展开/收起。
          右侧的按钮、开关、改名输入都带 @click.stop，
          所以它们不会连带触发展开 —— 这是整行可点的前提。
        -->
        <div class="card-head" @click="toggleItem(item._uid)">
          <!--
            整行可点：手机上点标题展开，比去戳那个小三角舒服得多。
            右侧控件用 @click.stop 拦住，避免点按钮时把卡片也展开了。
          -->
          <!--
            展开键：单独点它也要能切换。
            注意这里**不能**再冒泡到整行的 handler —— 否则一次点击
            会切两次（先切一次、再被行 handler 切回来，等于没变）。
            所以用 stop 拦住冒泡，自己调一次。
          -->
          <button class="caret" @click.stop="toggleItem(item._uid)" :aria-expanded="isOpen(item._uid)"
            :aria-label="isOpen(item._uid) ? '收起' : '展开'">
            <span class="caret-arrow">{{ isOpen(item._uid) ? '▾' : '▸' }}</span>
          </button>
          <span class="ico"><AppIcon :name="TAB_ICON[tab]" :size="15" /></span>

          <div class="card-title">
            <input v-if="renamingUid === item._uid" class="rename" type="text" v-model="renameValue"
              @click.stop @keyup.enter="commitRename(item)" @keyup.esc="renamingUid = null" />
            <span v-else class="name" title="点一下改名" @click.stop="startRename(item)">{{ titleOf(item) }}</span>
            <span class="meta">
              <span class="subcount">{{ subCount(item) }} 项</span>
              <span v-if="isEnabled(item)" class="tag">已启用</span>
            </span>
          </div>

          <div class="acts">
            <button class="ib" title="复制一份" @click.stop="duplicate(item)">
              <span aria-hidden="true">⧉</span>
            </button>
            <button class="ib danger" title="删除" @click.stop="remove(item)">
              <span aria-hidden="true">✕</span>
            </button>
            <!--
              开关：做成小拨片而不是原生 checkbox。
              原生复选框在手机上渲染得又大又方，是"网页后台感"的主要来源之一。
            -->
            <label class="sw" :title="isEnabled(item) ? '已启用，点一下关闭' : '点一下启用'">
              <input type="checkbox" :checked="isEnabled(item)" @click.stop
                @change="setEnabled(item, ($event.target as HTMLInputElement).checked)" />
              <span class="sw-track"><span class="sw-thumb"></span></span>
            </label>
          </div>
        </div>

        <!-- 子条目：逐条单独改 -->
        <div v-if="isOpen(item._uid)" class="subs">
          <template v-if="tab === 'worldbook'">
            <AssetSubEntry v-for="(e, i) in toEntryArray(item.entries)" :key="e.uid || i"
              kind="worldbook" :item="e" :index="i" @changed="saveAll" />
            <div v-if="!toEntryArray(item.entries).length" class="empty-sub">没有条目</div>
          </template>

          <template v-else-if="tab === 'preset'">
            <AssetSubEntry v-for="(_, i) in presetPrompts(item)" :key="i"
              kind="preset" :item="item" :index="i" @changed="saveAll" />
            <div v-if="!presetPrompts(item).length" class="empty-sub">这个预设没有 prompts</div>
          </template>

          <!-- 正则组：组内脚本逐条列出，可单独开关/展开改 -->
          <template v-else-if="tab === 'regex'">
            <div v-for="(s, si) in item.scripts || []" :key="s.id || si" class="sub-wrap"
              :class="{ off: item.enabled === false || s.disabled }">
              <div class="sub-head" @click="toggleScriptOpen(item._uid, si)">
                <span class="caret">{{ isScriptOpen(item._uid, si) ? '▾' : '▸' }}</span>
                <span class="star" :class="{ on: item.enabled !== false && !s.disabled }">✱</span>
                <span class="title">{{ s.scriptName || `脚本 ${si + 1}` }}</span>
                <span class="meta mono">{{ s.findRegex || '(空)' }}</span>
                <label class="sw" @click.stop>
                  <input type="checkbox" :checked="!s.disabled"
                    @change="toggleScriptInGroup(item, s, ($event.target as HTMLInputElement).checked)" />
                </label>
              </div>
              <div v-if="isScriptOpen(item._uid, si)" class="sub-body">
                <div class="f">
                  <label>名字</label>
                  <input type="text" v-model="s.scriptName" @change="saveAll" />
                </div>
                <div class="f">
                  <label>正则表达式 Find Regex · 支持 /pattern/flags</label>
                  <input type="text" class="mono" v-model="s.findRegex" @change="saveAll" />
                </div>
                <div class="f">
                  <label>替换内容 Replace With · 可用 $1</label>
                  <textarea rows="3" class="mono" v-model="s.replaceString" @change="saveAll"></textarea>
                </div>
                <div class="f">
                  <label>替换前修剪 Trim Out · 每行一个</label>
                  <textarea rows="2" class="mono" :value="(s.trimStrings || []).join('\n')"
                    @change="s.trimStrings = ($event.target as HTMLTextAreaElement).value.split('\n').map(x => x.trim()).filter(Boolean); saveAll()"></textarea>
                </div>
                <div class="f">
                  <label>作用范围 Placement</label>
                  <div class="chips">
                    <button v-for="p in ST_REGEX_PLACEMENTS" :key="p.value" class="chip"
                      :class="{ active: (s.placement || []).includes(p.value) }"
                      @click="toggleScriptPlacement(s, p.value)">{{ p.label }}</button>
                  </div>
                </div>
                <div class="f">
                  <label>执行时机 Timing</label>
                  <div class="seg">
                    <button :class="{ active: !s.markdownOnly && !s.promptOnly }"
                      @click="s.markdownOnly = false; s.promptOnly = false; saveAll()">发送时</button>
                    <button :class="{ active: s.markdownOnly }"
                      @click="s.markdownOnly = true; s.promptOnly = false; saveAll()">仅显示</button>
                    <button :class="{ active: s.promptOnly }"
                      @click="s.promptOnly = true; s.markdownOnly = false; saveAll()">仅提示词</button>
                  </div>
                </div>
                <div class="two">
                  <div class="f">
                    <label>参数替换</label>
                    <select v-model.number="s.substituteRegex" @change="saveAll">
                      <option :value="0">不替换</option>
                      <option :value="1">替换（宏生效）</option>
                      <option :value="2">替换（原始）</option>
                    </select>
                  </div>
                  <div class="f">
                    <label>消息深度（留空=不限）</label>
                    <div class="two">
                      <input type="number" placeholder="最小" v-model.number="s.minDepth" @change="saveAll" />
                      <input type="number" placeholder="最大" v-model.number="s.maxDepth" @change="saveAll" />
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="!(item.scripts || []).length" class="empty-sub">这个组里还没有脚本</div>
          </template>

          <!-- 角色 DNA -->
          <template v-else-if="tab === 'dna'">
            <div class="dna-body">
              <div class="f">
                <label>
                  角色外观描述（英文，建议直接写 Danbooru 标签，逗号分隔）
                </label>
                <textarea rows="4" v-model="item.dna"
                  placeholder="1girl, silver hair, red eyes, gothic dress, black choker, pale skin"
                  @change="dna.update(item._uid, { dna: item.dna })"></textarea>
                <div class="dna-preview">
                  生图时会这样发给大模型：
                  <pre>{{ item.dna || '(还没填)' }}</pre>
                </div>
              </div>
              <div class="f">
                <label>备注（给自己看）</label>
                <input type="text" v-model="item.note"
                  @change="dna.update(item._uid, { note: item.note })" />
              </div>
              <!--
                这里以前还有个「设为常驻」复选框 —— 和卡片右上角的开关是两套逻辑
                （一个管自动配图、一个管弹窗），用户看到的两个开关互相打架。
                现在只有一个开关：卡片右上角那个，勾上就参与所有生图。
              -->
            </div>
          </template>

          <template v-else>
            <AssetSubEntry v-for="(f, i) in charFields(item)" :key="f.key"
              kind="character" :item="f" :index="i" @changed="onCharFieldChange(item, f)" />
          </template>
        </div>
      </div>

      <div v-if="!items.length" class="empty">
        还没有{{ KIND_LABEL[tab] }}。点右上角「📥 导入」导入，或「＋ 新建」。
      </div>
    </div>
    </template>
  </div>
</template>

<style scoped>
/*
 * 资产页：从"网页后台表格"改成手机 App 的观感。
 *
 * 之前的三个问题（用户反馈"像浏览器界面，掉档次"）：
 *   1. 顶部标题栏跟着内容滚走，右边三个文字按钮挤成一行还会被切掉；
 *   2. 6 个分类用 flex-wrap 折成三行 —— 那是网页导航的样子；
 *   3. 每张卡片右侧塞了「复制 / 删除 / 大复选框」三个控件，
 *      原生复选框在手机上又大又方，是廉价感的主要来源。
 *
 * 现在对应改成：吸顶应用栏 + 单行横滚分段控件 + 小拨片开关。
 */
.assets-page {
  padding: 0 0 32px;
  max-width: var(--content-max, 1100px);
  /*
   * width + min-width: 0 是必须的。
   *
   * 这是个 flex 列容器，而里面的分类栏（.cat-tabs）有 6 个按钮、
   * 横向内容比屏幕宽。flex 子项默认 `min-width: auto` —— 意思是
   * "不许压到比内容更窄"。结果就是分类栏把整个页面**撑得比屏幕还宽**，
   * 卡片右侧的按钮和开关全被挤出屏幕（实测就是这样）。
   *
   * 显式声明 width: 100% + min-width: 0，让自己可以被压窄，
   * 超出的部分交给分类栏自己的 overflow-x 去滚。
   */
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 0;
  /* 保险：万一还有子元素想撑宽，也不要出现横向滚动条 */
  overflow-x: hidden;
}

/* ---------------- 吸顶应用栏 ---------------- */
.app-bar {
  position: sticky;
  top: 0;
  z-index: 20;
  /*
   * ⚠ 这条 top 必须压过全局的 `top: var(--safe-top) !important`。
   *
   * 起因（实测数据，别删这段说明）：
   *   全局 main.css（手机端）做了两件互相叠加的事：
   *     ① .main-content { padding-top: var(--safe-top) }  → 内容整体下移 24px
   *     ② .app-bar { top: var(--safe-top) !important }    → 吸顶位置再下移 24px
   *   于是应用栏在 y=48，而紧跟其后的分类标签栏还在文档流 y=87 ——
   *   两者重叠 24px，分类栏被压掉一半（用户反馈"顶部 ui 把选项给遮挡了"）。
   *
   *   把 top 归零后：应用栏回到 y=24（= .main-content 的内边距下沿，正是
   *   安全区之后），分类栏紧随其后 y=87 —— 实测 gap 从 -24 变成 0，严丝合缝。
   *   滚动时的吸顶位置也一起对齐了，不会再出现"翻两下就叠上去"。
   *
   * 为什么在这里而不是改全局：别的页面顶栏是普通流内元素，没这个双重计算，
   * 全局那条 top 对它们是对的。只修这一页。
   */
  top: 0 !important;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px;
  background: var(--glass, var(--panel));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--hairline, var(--border));
}
.app-bar h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.head-ico {
  color: var(--accent);
}
.bar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
/* 文字按钮：小胶囊，比图标按钮更省心（不受 emoji 字体差异影响） */
.text-btn {
  height: 36px;
  padding: 0 13px;
  font-size: 12.5px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  background: var(--surface-1, var(--panel-2));
  border: 1px solid var(--border);
  border-top-color: var(--hairline-strong, var(--border));
  box-shadow: var(--shadow-1);
  color: var(--text);
  white-space: nowrap;
}
.text-btn.on {
  border-color: var(--accent);
  color: var(--accent);
}
.new-btn {
  height: 36px;
  padding: 0 14px;
  font-size: 12.5px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  background: var(--accent-grad, var(--accent));
  color: #fff;
  border: none;
  box-shadow: var(--shadow-1);
  white-space: nowrap;
}

/* ---------------- 分类：单行横滚分段控件 ---------------- */
.cat-tabs {
  display: flex;
  gap: 7px;
  padding: 12px 16px 10px;
  /* 自己可以被压窄，超出的按钮交给 overflow-x 横滚（不能反过来撑宽父容器） */
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  /* 横滚而不是折行：单行看起来像原生标签栏，折行像网页 nav */
  overflow-x: auto;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}
.cat-tabs::-webkit-scrollbar {
  display: none;
}
.cat-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  font-size: 12.5px;
  padding: 7px 13px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: var(--surface-1, var(--panel));
  color: var(--text-dim);
  white-space: nowrap;
  transition: background 0.14s, color 0.14s, border-color 0.14s;
}
.cat-tabs button.active {
  border-color: transparent;
  color: #fff;
  font-weight: 700;
  background: var(--accent-grad, var(--accent));
  box-shadow: 0 2px 10px color-mix(in srgb, var(--accent) 34%, transparent);
}
.cat-tabs .count {
  font-size: 10px;
  font-weight: 500;
  padding: 0 5px;
  min-width: 16px;
  text-align: center;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, currentColor 16%, transparent);
  opacity: 0.9;
}
.ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-dim);
}
.lab {
  font-size: 12.5px;
}

/* ---------------- 工具条 ---------------- */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 4px 16px 10px;
  font-size: 11.5px;
}
.toolbar .danger {
  font-size: 11.5px;
  padding: 5px 12px;
  border-radius: var(--radius-pill);
}

/* ---------------- 列表与卡片 ---------------- */
.list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 0 16px;
  min-width: 0;
}
.card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--panel);
  overflow: hidden;
  box-shadow: var(--shadow-1);
  transition: box-shadow 0.15s, border-color 0.15s;
}
.card.open {
  box-shadow: var(--shadow-2);
}
/* 已启用的卡片：左边一条主色，一眼看出哪些在生效 */
.card.on {
  border-color: color-mix(in srgb, var(--accent) 34%, var(--border));
}
.card.on .card-head::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--accent-grad, var(--accent));
}

.card-head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: transparent;
  /* 同样的收缩问题：不写的话标题会把右侧控件挤出卡片 */
  min-width: 0;
  /*
   * 整行可点 = 展开/收起。
   * 这样即使展开键和操作按钮做得紧凑（26/30px），
   * 点起来仍然轻松 —— 手机上最舒服的点击区是"整行"。
   */
  cursor: pointer;
}
.card-head:active {
  background: color-mix(in srgb, var(--accent) 6%, transparent);
}
.caret {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  padding: 0;
  background: var(--surface-1, var(--panel-2));
  border: 1px solid var(--hairline, var(--border));
  border-radius: var(--radius-sm);
  color: var(--text-dim);
  box-shadow: none;
  /*
   * 盖掉全局的 `button { min-height: 38px; min-width: 38px }`。
   * 那条规则是给"单独的按钮"用的（手机上要够大才点得中）。
   * 但这里的展开键只是**视觉提示** —— 整行标题都能点展开，
   * 保留 38px 只会让卡头凭空高 12px，看着臃肿（实测 64px 高）。
   */
  min-width: 0;
  min-height: 0;
}
.caret-arrow {
  font-size: 10px;
  line-height: 1;
}
.card-head .ico {
  flex-shrink: 0;
  font-size: 17px;
}

/* 标题区：标题 + 一行小字（项数 / 已启用） */
.card-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: text;
}
.meta {
  display: flex;
  align-items: center;
  gap: 7px;
}
.rename {
  font-size: 13px;
  padding: 5px 8px;
  min-width: 0;
}
.subcount {
  font-size: 10.5px;
  color: var(--text-dim);
  white-space: nowrap;
}
.tag {
  font-size: 9.5px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
  white-space: nowrap;
}

/* 右侧控件：统一 30px 直径，视觉重量一致 */
.acts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.ib {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  padding: 0;
  font-size: 13px;
  line-height: 1;
  border-radius: var(--radius-sm);
  background: var(--surface-1, var(--panel-2));
  border: 1px solid var(--hairline, var(--border));
  color: var(--text-dim);
  box-shadow: none;
  /* 同 caret：盖掉全局的 38px 最小尺寸，否则卡头会被撑高 */
  min-width: 0;
  min-height: 0;
}
.ib:hover {
  color: var(--text);
  border-color: var(--border);
}
.ib.danger:hover {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 50%, transparent);
}

/* ---------------- 开关：小拨片（替代原生大复选框） ----------------
 * 只作用于卡片右侧那个"启用"开关（.acts 里的 .sw）。
 * 下面的 .sw.inline 是别处带文字标签的勾选项，那个要继续用原生复选框，
 * 所以这里用 .acts .sw 限定范围，避免样式串过去。
 */
.acts .sw {
  position: relative;
  display: inline-flex;
  align-items: center;
  width: 40px;
  height: 24px;
  /*
   * 必须显式盖掉 min-width / min-height。
   *
   * 全局移动端样式里有 `label.sw, .sw { min-width: 38px; min-height: 38px }`
   * —— 那是为了让**原生复选框**的点击区域够大。但它会把这个小拨片
   * 一起撑成 38x38，拨片就变成一个方块了（实测踩到）。
   * 拨片本身 40x24 已经够点（比原生复选框点击区还大），不需要那个兜底。
   */
  min-width: 0;
  min-height: 0;
  padding: 0;
  /* 别被 flex 拉高（父级 align-items 会影响到它） */
  align-self: center;
  flex: 0 0 auto;
  flex-shrink: 0;
  cursor: pointer;
}
/* 真 input 藏起来但保留可访问性（键盘、屏幕阅读器都还能用） */
.acts .sw input {
  position: absolute;
  opacity: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  cursor: pointer;
}
.sw-track {
  position: absolute;
  inset: 0;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--text-dim) 24%, transparent);
  transition: background 0.18s ease;
  pointer-events: none;
}
.sw-thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  transition: transform 0.18s cubic-bezier(0.32, 0.72, 0, 1);
}
.acts .sw input:checked + .sw-track {
  background: var(--accent-grad, var(--accent));
}
.acts .sw input:checked + .sw-track .sw-thumb {
  transform: translateX(16px);
}
.acts .sw input:focus-visible + .sw-track {
  outline: 2px solid color-mix(in srgb, var(--accent) 60%, transparent);
  outline-offset: 2px;
}

.subs {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--hairline, var(--border));
}
.empty-sub {
  padding: 14px 16px;
  font-size: 11.5px;
  color: var(--text-dim);
}
.empty {
  padding: 40px 24px;
  text-align: center;
  color: var(--text-dim);
  font-size: 12.5px;
}
.import-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hint {
  font-size: 11px;
  color: var(--text-dim);
  margin: 0;
  line-height: 1.6;
}
.mono {
  font-family: ui-monospace, monospace;
}
.import-card textarea {
  font-size: 12px;
  padding: 7px 9px;
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
}
.detect {
  font-size: 11.5px;
}
.dim {
  color: var(--text-dim);
  font-size: 11.5px;
}
.err {
  font-size: 11.5px;
  color: var(--danger);
  white-space: pre-wrap;
}
.row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.results {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.results-head {
  font-size: 11.5px;
  color: var(--text-dim);
}
.result-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 11.5px;
}
.result-row.bad {
  color: var(--danger);
}
.badge {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-dim);
}
.ok {
  color: #4ade80;
}
.notes {
  width: 100%;
  font-size: 10px;
  color: var(--text-dim);
}
.sw.inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-dim);
  cursor: pointer;
}
.sw.inline input {
  width: auto;
  margin: 0;
}
@media (max-width: 640px) {
  /* 外层 padding 由各个区块自己控制（应用栏/分类/列表各自带），这里不再统一加 */
  .assets-page {
    padding: 0 0 28px;
  }
  .list {
    padding: 0 18px;
  }
  .cat-tabs {
    padding: 10px 14px 8px;
  }
  .app-bar {
    /* 手机上留 18px 边距（原来 12px）：
       贴边的按钮手指不好点，全面屏的圆角/手势区也压在最边上。 */
    padding: 12px 18px;
  }
  .toolbar {
    padding: 4px 18px 9px;
  }
}
</style>
