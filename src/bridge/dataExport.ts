// 一次性数据迁移（web → 原生）。
//
// 为什么不做"一个大 JSON 全导出"：
//   会话里有配图（base64 可达几 MB），几十个会话加起来能让 evaluateJavascript
//   的字符串直接爆掉 WebView，轻则卡死重则 OOM。
//   所以这里全部改成**分页拉取**：原生先拿清单，再一条一条要。
//
// 通道形状（和生图回调同一套思路，原生驱动、web 回调）：
//   原生 → evaluateJavascript("__dhpData.index('tk')")
//   web  → AndroidNative.onMigrationIndex('tk', json)
//   原生 → __dhpData.session('tk', 's_xxx')
//   web  → AndroidNative.onMigrationSession('tk', 's_xxx', json)
//
// 所有导出都**只读**：不修改 web 侧任何数据，迁移失败可以原样重来。
import { useAdventureStore } from '../stores/adventure';
import { useAssetsStore } from '../stores/assets';
import { useApiConfigStore } from '../stores/apiConfig';
import { useImageStore } from '../stores/image';
import { listAssets, assetUrl, loadAssets } from '../lib/imageAssets';

/** 调原生回调（不存在就静默 —— 浏览器里打开时这段不会被调用到）。 */
function callNative(method: string, ...args: unknown[]): void {
  try {
    const native = (window as any).AndroidNative;
    if (native && typeof native[method] === 'function') {
      native[method](...args);
    }
  } catch (e) {
    console.warn('[迁移] 回调原生失败', method, e);
  }
}

/** 超过这个长度的 dataUrl 不塞进会话包，留给资产通道单独取。 */
const INLINE_LIMIT = 64 * 1024;

/**
 * 把会话里的重字段（base64 图片）剥掉，只留引用。
 *
 * 规则：
 *   - 有 assetId 的：直接去掉 dataUrl（原生能按 id 单独取图）
 *   - 没有 assetId 但 dataUrl 不大（< 64KB）：保留，老数据就靠它显示
 *   - 没有 assetId 且很大：去掉 dataUrl，同时把 id 记进 missingAssets，
 *     原生会提示"这几张图要重新生成"（总比整包传不过去好）
 */
function stripSession(session: any): { json: any; missing: string[] } {
  const missing: string[] = [];
  const actions = (session.actions || []).map((act: any) => {
    if (!Array.isArray(act.images) || !act.images.length) return act;
    const images = act.images.map((im: any) => {
      if (!im || typeof im !== 'object') return im;
      const hasAsset = !!im.assetId;
      const url: string = typeof im.dataUrl === 'string' ? im.dataUrl : '';
      if (hasAsset) {
        const clone = { ...im };
        delete clone.dataUrl;
        return clone;
      }
      if (url && url.length > INLINE_LIMIT) {
        if (im.id) missing.push(im.id);
        const clone = { ...im };
        delete clone.dataUrl;
        return clone;
      }
      return im;
    });
    return { ...act, images };
  });
  return { json: { ...session, actions }, missing };
}

/** 清单：会话、角色、配置（都很小）。 */
async function exportIndex(token = ''): Promise<void> {
  // 资产表是懒加载的（只有打开图片库时才载入），不显式加载会导出 0 张图
  try {
    await loadAssets();
  } catch (e) {
    console.warn('[迁移] 图片资产库加载失败', e);
  }
  const adventure = useAdventureStore();
  const assets = useAssetsStore();
  const api = useApiConfigStore();
  const image = useImageStore();

  const payload = {
    version: 1,
    exportedAt: Date.now(),
    sessions: (adventure.sessions || []).map((s: any) => ({
      id: s.id,
      title: s.title || '',
      count: s.actions?.length || 0,
      createdAt: s.createdAt || 0,
      updatedAt: s.updatedAt || s.createdAt || 0,
    })),
    characters: (assets.characters || []).map((c: any) => ({
      uid: c._uid || '',
      name: c.name || c.data?.name || '',
    })),
    configs: {
      llm: { configs: api.configs, currentConfigId: api.currentConfigId, providerNotes: api.providerNotes },
      image: {
        apis: image.apiConfigs,
        styles: image.styleConfigs,
        currentApiId: image.currentApiId,
        currentStyleId: image.currentStyleId,
      },
    },
    assetIds: listAssets().map((a) => a.id),
  };

  let json = '{}';
  try {
    json = JSON.stringify(payload);
  } catch (e) {
    console.warn('[迁移] 清单序列化失败', e);
  }
  callNative('onMigrationIndex', token, json);
}

/** 单个会话（含剥离后的配图信息）。 */
function exportSession(token: string, id: string): void {
  const adventure = useAdventureStore();
  const s = (adventure.sessions || []).find((x: any) => x.id === id);
  if (!s) {
    callNative('onMigrationSession', token, id, '');
    return;
  }
  try {
    const { json, missing } = stripSession(s);
    callNative('onMigrationSession', token, id, JSON.stringify({ session: json, missingAssets: missing }));
  } catch (e) {
    console.warn('[迁移] 会话导出失败', id, e);
    callNative('onMigrationSession', token, id, '');
  }
}

/** 单个角色卡（parsecard 对象原样带走）。 */
function exportCharacter(token: string, uid: string): void {
  const assets = useAssetsStore();
  const c = (assets.characters || []).find((x: any) => x._uid === uid);
  let json = '';
  try {
    json = c ? JSON.stringify(c) : '';
  } catch (e) {
    console.warn('[迁移] 角色导出失败', uid, e);
  }
  callNative('onMigrationCharacter', token, uid, json);
}

/** 单张图片（按资产 id 取 dataUrl；没有就回空串）。 */
async function exportAsset(token: string, id: string): Promise<void> {
  let url = '';
  try {
    url = (await assetUrl(id)) || '';
  } catch (e) {
    console.warn('[迁移] 图片导出失败', id, e);
  }
  callNative('onMigrationAsset', token, id, url);
}

/** 在 app 启动时挂上。 */
export function installDataExport(): void {
  (window as any).__dhpData = {
    getIndex: () => void exportIndex(),
    index: (token: string) => void exportIndex(token),
    session: exportSession,
    character: exportCharacter,
    asset: exportAsset,
  };
}
