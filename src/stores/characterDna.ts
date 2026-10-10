import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { store } from '../lib/storage';
import type { CharacterDna } from '../engine/characterDna';
import { createDna, buildDnaBlock, mergeDnaTags } from '../engine/characterDna';

// 角色 DNA store：独立于世界书/预设的另一类资产。
//
// 生图时会用到两部分：
//   selectedIds —— 界面上勾选的，作用于弹窗里的手动生图
//   pinnedId    —— 常驻锚定的那一条，自动配图（[[IMG]] 标记）也会带上
// 这样日常写作时不用每次去勾，自动配图也能保持角色一致。

export const useDnaStore = defineStore('characterDna', () => {
  const items = ref<CharacterDna[]>([]);
  /** 弹窗里勾选的 DNA */
  const selectedIds = ref<string[]>([]);
  /** 常驻锚定：自动配图也会用 */
  const pinnedId = ref<string | undefined>(undefined);

  const all = computed(() => items.value);
  const enabled = computed(() => items.value.filter((d) => d.enabled !== false));
  const selected = computed(() =>
    items.value.filter((d) => selectedIds.value.includes(d._uid)),
  );
  const pinned = computed(() => items.value.find((d) => d._uid === pinnedId.value));

  /** 自动配图使用的 DNA 块：常驻锚定的那条 */
  function autoDnaBlock(): string {
    return pinned.value ? buildDnaBlock([pinned.value]) : '';
  }

  /** 弹窗使用的 DNA 块：勾选的条目（没勾选就退回常驻那条） */
  function selectedDnaBlock(): string {
    const list = selected.value.length > 0 ? selected.value : pinned.value ? [pinned.value] : [];
    return buildDnaBlock(list);
  }

  /** 合并成纯标签（手动提示词模式用） */
  function selectedDnaTags(): string {
    const list = selected.value.length > 0 ? selected.value : pinned.value ? [pinned.value] : [];
    return mergeDnaTags(list);
  }

  async function load() {
    const [list, sel, pin] = await Promise.all([
      store.getItem<CharacterDna[]>('characterDnaList'),
      store.getItem<string[]>('characterDnaSelected'),
      store.getItem<string | undefined>('characterDnaPinned'),
    ]);
    items.value = list ?? [];
    selectedIds.value = sel ?? [];
    pinnedId.value = pin ?? undefined;

    if (items.value.length === 0) {
      const first = createDna('主角');
      items.value = [first];
      pinnedId.value = first._uid;
      await save();
    } else if (pinnedId.value && !items.value.some((d) => d._uid === pinnedId.value)) {
      pinnedId.value = undefined;
    }
  }

  async function save() {
    await Promise.all([
      store.setItem('characterDnaList', items.value),
      store.setItem('characterDnaSelected', selectedIds.value),
      store.setItem('characterDnaPinned', pinnedId.value),
    ]);
  }

  async function add(name = '新角色') {
    const d = createDna(name);
    items.value.push(d);
    await save();
    return d;
  }

  async function remove(uid: string) {
    items.value = items.value.filter((d) => d._uid !== uid);
    selectedIds.value = selectedIds.value.filter((x) => x !== uid);
    if (pinnedId.value === uid) pinnedId.value = undefined;
    await save();
  }

  async function update(uid: string, patch: Partial<CharacterDna>) {
    const d = items.value.find((x) => x._uid === uid);
    if (d) {
      Object.assign(d, patch, { updatedAt: Date.now() });
    }
    await save();
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

  async function toggleSelected(uid: string, on: boolean) {
    selectedIds.value = on
      ? [...new Set([...selectedIds.value, uid])]
      : selectedIds.value.filter((x) => x !== uid);
    await save();
  }

  async function setPinned(uid?: string) {
    pinnedId.value = uid;
    await save();
  }

  return {
    items,
    all,
    enabled,
    selected,
    selectedIds,
    pinnedId,
    pinned,
    autoDnaBlock,
    selectedDnaBlock,
    selectedDnaTags,
    load,
    save,
    add,
    remove,
    update,
    duplicate,
    toggleSelected,
    setPinned,
  };
});
