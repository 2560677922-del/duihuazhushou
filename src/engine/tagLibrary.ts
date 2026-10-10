// NovelAI / Danbooru 标签库
// ---------------------------------------------------------------------------
// 为什么不用那套现成的：
//   参考的插件（chami_tavern-scene-plugin）里的标签数据其实是**整份 Danbooru 转储**：
//   data/ 下是 25 个 .sql 分片（tags_2025_03_31.sql 1.1MB）+ static-tags{,1,2}.json
//   （1.0MB / 1.6MB / 3.5MB），合起来 8MB 以上，整个仓库 215MB。
//   那是给"标签可视化 / 标签市场"用的，需要 SQLite 或 IndexedDB 才能查。
//   直接塞进这个应用既臃肿，对"给大模型一份参考"也没必要——几万个标签全塞进
//   提示词反而会稀释重点、拉高成本。
//
// 所以这里走两条路：
//   1. 内置一份**精选常用标签**（按场景组装成短参考块，几百 token 量级）；
//   2. 支持**导入完整标签库**（用户从那些插件仓库里拿到的 JSON 直接导进来检索）。
//
// 内置标签只负责"给模型一个准确的锚点"，比如告诉它"银发"的标准写法是
// silver hair 而不是 silver colored hair。

export interface TagEntry {
  /** Danbooru / NovelAI 标准标签（英文） */
  tag: string;
  /** 中文说明，给用户看，也帮助模型理解语义 */
  zh: string;
}

export interface TagCategory {
  id: string;
  name: string;
  /** 组装参考块时是否默认包含 */
  defaultOn: boolean;
  tags: TagEntry[];
}

export const TAG_CATEGORIES: TagCategory[] = [
  {
    id: 'quality',
    name: '质量与风格',
    defaultOn: true,
    tags: [
      { tag: 'masterpiece', zh: '杰作' },
      { tag: 'best quality', zh: '最佳质量' },
      { tag: 'very aesthetic', zh: '极具美感（V4/V4.5 推荐）' },
      { tag: 'absurdres', zh: '超高分辨率' },
      { tag: 'highres', zh: '高分辨率' },
      { tag: 'no text', zh: '不要文字' },
      { tag: 'cinematic lighting', zh: '电影感打光' },
      { tag: 'depth of field', zh: '景深' },
      { tag: 'blurry background', zh: '背景虚化' },
      { tag: 'monochrome', zh: '单色' },
      { tag: 'watercolor', zh: '水彩' },
      { tag: 'oil painting', zh: '油画' },
      { tag: 'sketch', zh: '素描' },
      { tag: 'anime style', zh: '动画风' },
      { tag: 'realistic', zh: '写实' },
      { tag: 'chibi', zh: 'Q版' },
    ],
  },
  {
    id: 'count',
    name: '人数与构图',
    defaultOn: true,
    tags: [
      { tag: '1girl', zh: '一名女性' },
      { tag: '1boy', zh: '一名男性' },
      { tag: '2girls', zh: '两名女性' },
      { tag: 'solo', zh: '单人' },
      { tag: 'multiple girls', zh: '多名女性' },
      { tag: 'no humans', zh: '没有人类（纯景物）' },
      { tag: 'upper body', zh: '上半身' },
      { tag: 'lower body', zh: '下半身' },
      { tag: 'full body', zh: '全身' },
      { tag: 'portrait', zh: '肖像/特写' },
      { tag: 'close-up', zh: '特写' },
      { tag: 'cowboy shot', zh: '七八分身（大腿以上）' },
      { tag: 'from above', zh: '俯视' },
      { tag: 'from below', zh: '仰视' },
      { tag: 'from side', zh: '侧面' },
      { tag: 'from behind', zh: '背面' },
      { tag: 'looking at viewer', zh: '看向观者' },
      { tag: 'looking away', zh: '看向别处' },
      { tag: 'wide shot', zh: '远景' },
    ],
  },
  {
    id: 'face',
    name: '面部与表情',
    defaultOn: true,
    tags: [
      { tag: 'smile', zh: '微笑' },
      { tag: 'grin', zh: '咧嘴笑' },
      { tag: 'open mouth', zh: '张嘴' },
      { tag: 'closed mouth', zh: '闭嘴' },
      { tag: 'laughing', zh: '大笑' },
      { tag: 'crying', zh: '哭泣' },
      { tag: 'tears', zh: '泪水' },
      { tag: 'angry', zh: '生气' },
      { tag: 'blush', zh: '脸红' },
      { tag: 'embarrassed', zh: '害羞' },
      { tag: 'surprised', zh: '惊讶' },
      { tag: 'serious', zh: '严肃' },
      { tag: 'expressionless', zh: '面无表情' },
      { tag: 'half-closed eyes', zh: '半睁眼' },
      { tag: 'closed eyes', zh: '闭眼' },
      { tag: 'sad', zh: '悲伤' },
      { tag: 'smug', zh: '得意' },
      { tag: 'pout', zh: '撅嘴' },
    ],
  },
  {
    id: 'hair_eyes',
    name: '发色发型与瞳色',
    defaultOn: true,
    tags: [
      { tag: 'long hair', zh: '长发' },
      { tag: 'short hair', zh: '短发' },
      { tag: 'medium hair', zh: '中长发' },
      { tag: 'very long hair', zh: '超长发' },
      { tag: 'twintails', zh: '双马尾' },
      { tag: 'ponytail', zh: '马尾' },
      { tag: 'braid', zh: '辫子' },
      { tag: 'bun', zh: '丸子头' },
      { tag: 'messy hair', zh: '凌乱头发' },
      { tag: 'wavy hair', zh: '波浪发' },
      { tag: 'straight hair', zh: '直发' },
      { tag: 'ahoge', zh: '呆毛' },
      { tag: 'bangs', zh: '刘海' },
      { tag: 'hair between eyes', zh: '刘海遮眼' },
      { tag: 'black hair', zh: '黑发' },
      { tag: 'brown hair', zh: '棕发' },
      { tag: 'blonde hair', zh: '金发' },
      { tag: 'white hair', zh: '白发' },
      { tag: 'silver hair', zh: '银发（注意不是 silver colored hair）' },
      { tag: 'grey hair', zh: '灰发' },
      { tag: 'red hair', zh: '红发' },
      { tag: 'pink hair', zh: '粉发' },
      { tag: 'purple hair', zh: '紫发' },
      { tag: 'blue hair', zh: '蓝发' },
      { tag: 'green hair', zh: '绿发' },
      { tag: 'multicolored hair', zh: '多色发' },
      { tag: 'gradient hair', zh: '渐变发色' },
      { tag: 'blue eyes', zh: '蓝眼' },
      { tag: 'red eyes', zh: '红眼' },
      { tag: 'green eyes', zh: '绿眼' },
      { tag: 'brown eyes', zh: '棕眼' },
      { tag: 'golden eyes', zh: '金眼' },
      { tag: 'purple eyes', zh: '紫眼' },
      { tag: 'grey eyes', zh: '灰眼' },
      { tag: 'heterochromia', zh: '异色瞳' },
    ],
  },
  {
    id: 'body',
    name: '体型与身体特征',
    defaultOn: false,
    tags: [
      { tag: 'slim', zh: '纤细' },
      { tag: 'petite', zh: '娇小' },
      { tag: 'tall', zh: '高挑' },
      { tag: 'muscular', zh: '肌肉发达' },
      { tag: 'curvy', zh: '丰满' },
      { tag: 'loli', zh: '幼女体型' },
      { tag: 'shota', zh: '正太体型' },
      { tag: 'mature female', zh: '成熟女性' },
      { tag: 'pointy ears', zh: '尖耳（精灵）' },
      { tag: 'animal ears', zh: '兽耳' },
      { tag: 'cat ears', zh: '猫耳' },
      { tag: 'fox ears', zh: '狐耳' },
      { tag: 'horns', zh: '角' },
      { tag: 'tail', zh: '尾巴' },
      { tag: 'wings', zh: '翅膀' },
      { tag: 'halo', zh: '光环' },
      { tag: 'fangs', zh: '尖牙' },
      { tag: 'elf', zh: '精灵' },
      { tag: 'demon girl', zh: '恶魔少女' },
      { tag: 'angel', zh: '天使' },
    ],
  },
  {
    id: 'clothing',
    name: '服装',
    defaultOn: true,
    tags: [
      { tag: 'dress', zh: '连衣裙' },
      { tag: 'long dress', zh: '长裙' },
      { tag: 'school uniform', zh: '校服' },
      { tag: 'sailor collar', zh: '水手领' },
      { tag: 'serafuku', zh: '水手服' },
      { tag: 'shirt', zh: '衬衫' },
      { tag: 'white shirt', zh: '白衬衫' },
      { tag: 't-shirt', zh: 'T恤' },
      { tag: 'sweater', zh: '毛衣' },
      { tag: 'hoodie', zh: '连帽衫' },
      { tag: 'jacket', zh: '夹克' },
      { tag: 'coat', zh: '大衣' },
      { tag: 'cloak', zh: '斗篷' },
      { tag: 'robe', zh: '长袍' },
      { tag: 'skirt', zh: '短裙' },
      { tag: 'pleated skirt', zh: '百褶裙' },
      { tag: 'pants', zh: '长裤' },
      { tag: 'shorts', zh: '短裤' },
      { tag: 'thighhighs', zh: '过膝袜' },
      { tag: 'socks', zh: '袜子' },
      { tag: 'boots', zh: '靴子' },
      { tag: 'high heels', zh: '高跟鞋' },
      { tag: 'gloves', zh: '手套' },
      { tag: 'hat', zh: '帽子' },
      { tag: 'witch hat', zh: '女巫帽' },
      { tag: 'scarf', zh: '围巾' },
      { tag: 'necktie', zh: '领带' },
      { tag: 'ribbon', zh: '缎带' },
      { tag: 'hair ribbon', zh: '发带' },
      { tag: 'hair ornament', zh: '发饰' },
      { tag: 'earrings', zh: '耳环' },
      { tag: 'necklace', zh: '项链' },
      { tag: 'glasses', zh: '眼镜' },
      { tag: 'armor', zh: '盔甲' },
      { tag: 'breastplate', zh: '胸甲' },
      { tag: 'cape', zh: '披风' },
      { tag: 'japanese clothes', zh: '和服类' },
      { tag: 'kimono', zh: '和服' },
      { tag: 'yukata', zh: '浴衣' },
      { tag: 'chinese clothes', zh: '中式服装' },
      { tag: 'hanfu', zh: '汉服' },
      { tag: 'bikini', zh: '比基尼' },
      { tag: 'swimsuit', zh: '泳装' },
      { tag: 'lingerie', zh: '内衣' },
      { tag: 'nude', zh: '裸体' },
      { tag: 'wet clothes', zh: '湿衣' },
      { tag: 'torn clothes', zh: '破损衣物' },
    ],
  },
  {
    id: 'pose',
    name: '动作与姿势',
    defaultOn: true,
    tags: [
      { tag: 'standing', zh: '站立' },
      { tag: 'sitting', zh: '坐着' },
      { tag: 'lying', zh: '躺着' },
      { tag: 'on back', zh: '仰卧' },
      { tag: 'on stomach', zh: '俯卧' },
      { tag: 'walking', zh: '行走' },
      { tag: 'running', zh: '奔跑' },
      { tag: 'jumping', zh: '跳跃' },
      { tag: 'kneeling', zh: '跪着' },
      { tag: 'crouching', zh: '蹲着' },
      { tag: 'arms up', zh: '举起双臂' },
      { tag: 'arms behind back', zh: '双手背后' },
      { tag: 'crossed arms', zh: '抱臂' },
      { tag: 'hand on hip', zh: '手叉腰' },
      { tag: 'hands on own face', zh: '手抚脸' },
      { tag: 'holding book', zh: '拿着书' },
      { tag: 'holding sword', zh: '持剑' },
      { tag: 'holding staff', zh: '持法杖' },
      { tag: 'holding cup', zh: '拿着杯子' },
      { tag: 'holding weapon', zh: '持有武器' },
      { tag: 'reaching out', zh: '伸手' },
      { tag: 'pointing', zh: '指' },
      { tag: 'hug', zh: '拥抱' },
      { tag: 'hugging', zh: '抱着' },
      { tag: 'leaning forward', zh: '身体前倾' },
      { tag: 'head tilt', zh: '歪头' },
      { tag: 'stretching', zh: '伸展' },
      { tag: 'sleeping', zh: '睡觉' },
      { tag: 'eating', zh: '吃东西' },
      { tag: 'reading', zh: '阅读' },
    ],
  },
  {
    id: 'scene',
    name: '场景与背景',
    defaultOn: true,
    tags: [
      { tag: 'indoors', zh: '室内' },
      { tag: 'outdoors', zh: '室外' },
      { tag: 'classroom', zh: '教室' },
      { tag: 'library', zh: '图书馆' },
      { tag: 'bedroom', zh: '卧室' },
      { tag: 'kitchen', zh: '厨房' },
      { tag: 'bathroom', zh: '浴室' },
      { tag: 'office', zh: '办公室' },
      { tag: 'cafe', zh: '咖啡馆' },
      { tag: 'restaurant', zh: '餐厅' },
      { tag: 'street', zh: '街道' },
      { tag: 'city', zh: '城市' },
      { tag: 'market', zh: '集市' },
      { tag: 'forest', zh: '森林' },
      { tag: 'grass', zh: '草地' },
      { tag: 'mountain', zh: '山' },
      { tag: 'beach', zh: '海滩' },
      { tag: 'ocean', zh: '海洋' },
      { tag: 'lake', zh: '湖泊' },
      { tag: 'river', zh: '河流' },
      { tag: 'waterfall', zh: '瀑布' },
      { tag: 'desert', zh: '沙漠' },
      { tag: 'snow', zh: '雪' },
      { tag: 'snowing', zh: '下雪中' },
      { tag: 'rain', zh: '雨' },
      { tag: 'raining', zh: '下雨中' },
      { tag: 'fog', zh: '雾' },
      { tag: 'cloudy sky', zh: '多云' },
      { tag: 'blue sky', zh: '蓝天' },
      { tag: 'night', zh: '夜晚' },
      { tag: 'night sky', zh: '夜空' },
      { tag: 'starry sky', zh: '星空' },
      { tag: 'sunset', zh: '日落' },
      { tag: 'sunrise', zh: '日出' },
      { tag: 'moon', zh: '月亮' },
      { tag: 'full moon', zh: '满月' },
      { tag: 'cherry blossoms', zh: '樱花' },
      { tag: 'flowers', zh: '花' },
      { tag: 'ruins', zh: '废墟' },
      { tag: 'castle', zh: '城堡' },
      { tag: 'temple', zh: '神殿' },
      { tag: 'dungeon', zh: '地牢' },
      { tag: 'cave', zh: '洞穴' },
      { tag: 'window', zh: '窗户' },
      { tag: 'curtains', zh: '窗帘' },
      { tag: 'bookshelf', zh: '书架' },
      { tag: 'bed', zh: '床' },
      { tag: 'table', zh: '桌子' },
      { tag: 'chair', zh: '椅子' },
    ],
  },
  {
    id: 'lighting',
    name: '光线与氛围',
    defaultOn: true,
    tags: [
      { tag: 'soft lighting', zh: '柔和光线' },
      { tag: 'backlighting', zh: '逆光' },
      { tag: 'rim light', zh: '轮廓光' },
      { tag: 'dramatic lighting', zh: '戏剧性打光' },
      { tag: 'candlelight', zh: '烛光' },
      { tag: 'moonlight', zh: '月光' },
      { tag: 'sunlight', zh: '阳光' },
      { tag: 'light rays', zh: '光线束' },
      { tag: 'god rays', zh: '丁达尔光' },
      { tag: 'glowing', zh: '发光' },
      { tag: 'sparkle', zh: '闪光' },
      { tag: 'lens flare', zh: '镜头光晕' },
      { tag: 'bokeh', zh: '散景' },
      { tag: 'dust', zh: '浮尘' },
      { tag: 'mist', zh: '薄雾' },
      { tag: 'fire', zh: '火' },
      { tag: 'magic', zh: '魔法' },
      { tag: 'glowing magic circle', zh: '发光魔法阵' },
      { tag: 'particles', zh: '粒子' },
      { tag: 'reflection', zh: '倒影' },
      { tag: 'silhouette', zh: '剪影' },
      { tag: 'shadow', zh: '阴影' },
    ],
  },
];

/** 用户导入的完整标签库（可选，走检索补充） */
export interface ImportedTagLibrary {
  name: string;
  /** 全量标签（可能很大，只在检索时用） */
  tags: TagEntry[];
  importedAt: number;
  source?: string;
}

export interface TagPromptOptions {
  /** 启用哪些分类 */
  enabledCategories: string[];
  /** 每个分类最多取多少个标签 */
  perCategory?: number;
  /** 额外检索到的相关标签 */
  extraTags?: TagEntry[];
  /** 总字符上限，防止把上下文撑爆 */
  maxChars?: number;
}

/**
 * 把选中的标签分类组装成一段给大模型看的参考。
 *
 * 关键取舍：不是"标签越多越好"。整份 Danbooru 有几万个标签，
 * 全塞进去会稀释重点、把 prompt 撑爆，还可能诱导模型堆砌无关标签。
 * 这里每类只取前 N 个最常见、最通用的，总量控制在几百 token。
 */
export function buildTagReference(options: TagPromptOptions): string {
  const perCategory = options.perCategory ?? 24;
  const maxChars = options.maxChars ?? 4000;
  const parts: string[] = [];

  for (const cat of TAG_CATEGORIES) {
    if (!options.enabledCategories.includes(cat.id)) continue;
    const list = cat.tags.slice(0, perCategory);
    const body = list.map((t) => t.tag).join(', ');
    parts.push(`【${cat.name}】${body}`);
  }

  if (options.extraTags?.length) {
    const extra = options.extraTags.slice(0, 60).map((t) => t.tag).join(', ');
    parts.push(`【额外可用标签】${extra}`);
  }

  let text =
    '以下是 NovelAI / Danbooru 标准标签参考。写提示词时**必须优先使用这些标准写法**，' +
    '不要自造同义词（例如「银发」要写 silver hair，而不是 silver colored hair）：\n\n' +
    parts.join('\n');

  if (text.length > maxChars) {
    text = text.slice(0, maxChars) + '\n…（标签参考已截断）';
  }
  return text;
}

/** 在导入的完整标签库里做子串检索（中英文都匹配） */
export function searchImportedTags(
  lib: ImportedTagLibrary | undefined,
  query: string,
  limit = 40,
): TagEntry[] {
  if (!lib || !query.trim()) return [];
  const q = query.trim().toLowerCase();
  const out: TagEntry[] = [];
  for (const t of lib.tags) {
    if (t.tag.toLowerCase().includes(q) || (t.zh || '').includes(q)) {
      out.push(t);
      if (out.length >= limit) break;
    }
  }
  return out;
}

/**
 * 导入标签库。兼容几种常见形状：
 *   [{ tag, zh }] / { tags: [...] } / { tags: { "tag": "分类" } }
 *   / danbooru 风格 [{ name, category, post_count }]
 */
export function parseTagLibrary(raw: unknown, sourceName = '导入的标签库'): ImportedTagLibrary {
  const out: TagEntry[] = [];
  const push = (tag: unknown, zh?: unknown) => {
    if (typeof tag !== 'string' || !tag.trim()) return;
    out.push({ tag: tag.trim(), zh: typeof zh === 'string' ? zh : '' });
  };

  const walk = (value: any, depth = 0) => {
    if (depth > 3 || value == null) return;
    if (Array.isArray(value)) {
      for (const item of value) {
        if (typeof item === 'string') push(item);
        else if (item && typeof item === 'object') {
          push(item.tag ?? item.name ?? item.id, item.zh ?? item.cn ?? item.description);
        }
      }
      return;
    }
    if (typeof value === 'object') {
      // { tags: [...] }
      if (Array.isArray(value.tags)) return walk(value.tags, depth + 1);
      if (Array.isArray(value.data)) return walk(value.data, depth + 1);
      if (Array.isArray(value.list)) return walk(value.list, depth + 1);
      // { tags: { "1girl": "count" } } 形式
      if (value.tags && typeof value.tags === 'object' && !Array.isArray(value.tags)) {
        for (const [k, v] of Object.entries(value.tags)) {
          push(k, typeof v === 'string' && v.length < 24 ? v : undefined);
        }
        return;
      }
      // 纯字典 { "1girl": "..." }
      //
      // 这里要小心：任意一个普通对象都能被当成"标签字典"，
      // 于是 { nothing: 123 } 也会被误认，静默塞进一堆垃圾标签。
      // 所以要求它看起来真的像标签表：条目够多，且值是字符串（分类名/计数）。
      const entries = Object.entries(value);
      const looksLikeTagDict =
        entries.length >= 3 &&
        entries.every(
          ([k, v]) => typeof k === 'string' && k.length < 80 && typeof v === 'string',
        );
      if (looksLikeTagDict) {
        for (const [k, v] of entries) {
          push(k, (v as string).length < 24 ? (v as string) : undefined);
        }
      }
    }
  };

  walk(raw);

  // 去重
  const seen = new Set<string>();
  const tags = out.filter((t) => {
    const k = t.tag.toLowerCase();
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });

  return { name: sourceName, tags, importedAt: Date.now() };
}
