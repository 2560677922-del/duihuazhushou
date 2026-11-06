import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { store } from '../lib/storage';
import type { CharacterDna } from '../engine/characterDna';
import { createDna, buildDnaBlock, mergeDnaTags } from '../engine/characterDna';

// 角色 DNA store：独立于世界书/预设的另一类资产。
//
// ★ 生效规则（只有一个开关：enabled）
//   勾上的每一条 DNA 都会进入**所有**生图请求 ——
//   弹窗手动生图、会话里「🎨 配图」、自动配图（[[IMG]] 标记）都一样。
//   没勾的完全不参与。
//
//   以前有两套开关（selectedIds 只管弹窗、pinnedId 管自动配图作兜底），
//   用户看到的就是"一个小对号 + 一个常驻按钮"，两个开关互相影响、
//   谁也说不清哪条到底生效（用户报的）。现在统一成 enabled：
//   勾选 = 生效，取消勾选 = 不生效，没有任何隐藏兜底。
//
//   为什么引擎层不用改：buildDnaBlock / mergeDnaTags 本来就只收
//   enabled !== false 的条目，之前是 UI 没把开关接到这个字段上。

export const useDnaStore = defineStore('characterDna', () => {
  const items = ref<CharacterDna[]>([]);

  const all = computed(() => items.value);

  /** 勾选（启用）的 DNA —— 所有生图都只带这些 */
  const enabledItems = computed(() => items.value.filter((d) => d.enabled !== false));

  /** 自动配图/手动生图共用的 DNA 块 */
  function dnaBlock(): string {
    return buildDnaBlock(enabledItems.value);
  }

  /** 合并成纯标签（手动提示词模式用） */
  function dnaTags(): string {
    return mergeDnaTags(enabledItems.value);
  }

  async function load() {
    const [list, migrated] = await Promise.all([
      store.getItem<CharacterDna[]>('characterDnaList'),
      store.getItem<boolean>('characterDnaUnifiedSwitch'),
    ]);
    items.value = list ?? [];

    // 老数据迁移：把过去的「勾选 / 常驻」合并成 enabled，跑一次就够
    if (!migrated) {
      await migrateLegacySwitch();
      await store.setItem('characterDnaUnifiedSwitch', true);
    }

    if (items.value.length === 0) {
      const first = createDna('主角');
      items.value = [first];
      await save();
    }
  }

  /**
   * 老版本有两个开关，迁移规则：
   *   - 有勾选过 → 勾选的那些启用
   *   - 没勾选过但有常驻 → 常驻那条启用（它以前确实会生效）
   *   - 两者都没有 → 保持原样（默认启用，和新建一致）
   */
  async function migrateLegacySwitch() {
    const [sel, pin] = await Promise.all([
      store.getItem<string[]>('characterDnaSelected'),
      store.getItem<string | undefined>('characterDnaPinned'),
    ]);
    const selected = sel ?? [];
    if (selected.length === 0 && !pin) return;
    for (const d of items.value) {
      d.enabled = selected.length > 0 ? selected.includes(d._uid) : d._uid === pin;
    }
    await save();
  }

  async function save() {
    await store.setItem('characterDnaList', items.value);
  }

  async function add(name = '新角色') {
    const d = createDna(name);
    items.value.push(d);
    await save();
    return d;
  }

  async function remove(uid: string) {
    items.value = items.value.filter((d) => d._uid !== uid);
    await save();
  }

  async function update(uid: string, patch: Partial<CharacterDna>) {
    const d = items.value.find((x) => x._uid === uid);
    if (d) {
      Object.assign(d, patch, { updatedAt: Date.now() });
    }
    await save();
  }

  /** 唯一的开关入口 */
  async function setEnabled(uid: string, on: boolean) {
    await update(uid, { enabled: on });
  }

  async function duplicate(uid: string) {
    const src = items.value.find((d) => d._uid === uid);
    if (!src) return;
    const copy = createDna(`${src.name} 副本`);
    copy.dna = src.dna;
    copy.note = src.note;
    items.value.push(copy);
    await save();
    return copy;
  }

  return {
    items,
    all,
    enabledItems,
    dnaBlock,
    dnaTags,
    load,
    save,
    add,
    remove,
    update,
    setEnabled,
    duplicate,
  };
});
