/*
 * 重建标签库：public/tags/novelai-tags.json
 * ---------------------------------------------------------------------------
 * 为什么需要重建（用户在标签页看到的三个问题）：
 *
 * ① 「有的重复」
 *    当前库是两份数据拼起来的：
 *      · 旧库 4,090 条 —— 手翻，标签用**空格**写法（long hair），子类是中文名（发型/帽子…）
 *      · 新库 18,526 条 —— 社区标签库机翻，标签用**下划线**写法（long_hair），子类是英文代号（hair/headwear…）
 *    merge-tags.mjs 当年只按"完全相同的字符串"去重，于是
 *    `long hair` 和 `long_hair` 被当成两条不同的标签同时留下 —— 实测这样的重复对 **1,430 组**。
 *    这就是"同一个东西出现两遍"的来源。
 *
 * ② 「有的分类不对」
 *    同一个概念在库里有两套子类，用户看到的就是"上装"和"top"、"头饰"和"headwear"、
 *    "配饰"和"accents"各来一份，而且同一条标签常常落在错的那一份里。
 *    这里把所有子类**统一成一套英文代号**（界面上再用 SUB_ZH 显示中文），
 *    并把中文子类逐条映射进去。
 *
 * ③ 「常用的应该放上边」
 *    ★ 根因：`tag-translate-source.json` 里的 items 数组**本身是按 Danbooru 热度降序**的
 *      （实测每个子类的前几条都是最通用的那些：服装/top 头几条是 long_sleeves、white_shirt…）。
 *      但 merge-tags.mjs 输出时按中文释义 `localeCompare` 排了一遍序，
 *      **把热度顺序彻底打散了** —— 于是界面上第一个格子是 zun_hat、第二个是 zhouzi_jin，
 *      真正常用的 long hair / blue eyes 沉在几千条后面。
 *    这里恢复热度排序：新库用源数组下标当热度，旧库独有标签用一份手工"常用置顶"表。
 *
 * 附带修掉的：
 *   · 标签写法统一成**空格**（NovelAI 的标准写法；engine/tagLibrary.ts 的解析器本来也是这么归一化的），
 *     下划线原写法存进 alt，检索仍然搜得到。
 *   · 头发拆成「发型 hair_style」+「发色 hair_color」；眼睛拆成「瞳色 eye_color」+「眼睛特征 eye_features」。
 *   · 清掉 3 条 <lora:...> 提示词串（唐风/宋风/明风，它们不是标签，是一整段提示词）。
 *   · 清掉 zh = 'NULL' 的占位。
 *
 * 用法：node scripts/rebuild-tags.mjs [--dry]
 * 输出：public/tags/novelai-tags.json + public/tags/meta.json
 * 回退：git checkout public/tags/
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'E:/duihuazhushou';
/**
 * 输入固定指向"重建前"的原始库，输出才是 public/tags/novelai-tags.json。
 * 这样脚本可以反复运行而结果一致（否则第二次会把已经清理过的库再处理一遍）。
 * 原始库：scripts/tag-library-before-rebuild.json（22,616 条，merge-tags.mjs 的产物）
 */
const LIB = path.join(ROOT, 'public/tags/novelai-tags.json');
const IN = path.join(ROOT, 'scripts/tag-library-before-rebuild.json');
const SRC = path.join(ROOT, 'scripts/tag-translate-source.json');
const DRY = process.argv.includes('--dry');

const cur = JSON.parse(fs.readFileSync(fs.existsSync(IN) ? IN : LIB, 'utf8'));
const src = JSON.parse(fs.readFileSync(SRC, 'utf8'));

/** 统一成 NovelAI 的标准写法：下划线 → 空格，压缩空白 */
const norm = (s) => String(s ?? '').replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
const key = (s) => norm(s).toLowerCase();

/* ==========================================================================
   1) 热度排名：源数组下标就是 Danbooru 热度降序
   ========================================================================== */
const rankOf = new Map();
src.items.forEach((it, i) => {
  const k = key(it.tag);
  if (!rankOf.has(k)) rankOf.set(k, i);
});

/* ==========================================================================
   2) 旧的中文子类 → 统一的英文子类
   ========================================================================== */
const SUB_MAP = {
  服装: {
    头饰: 'headwear', 发饰: 'headwear', 帽子: 'headwear', 面具: 'headwear',
    上衣: 'top', 短衫: 'top', 长衫: 'top', 上杉: 'top', 长上杉: 'top', 宋抹: 'top',
    手臂: 'top', 外套: 'top', 领子: 'top',
    裤子: 'bottom', 裙子: 'bottom', 百褶裙: 'bottom', 齐胸褶裙: 'bottom', 齐胸破裙: 'bottom',
    泳装: 'outfit', 制服: 'outfit', 运动服: 'outfit', 正装: 'outfit', 休闲装: 'outfit',
    风格: 'outfit', 其他: 'outfit', 盔甲: 'outfit',
    靴子: 'footwear', 鞋子: 'footwear', 鞋底: 'footwear',
    袜子: 'legwear',
    眼镜: 'eyewear', 内衣: 'underwear',
    领口: 'accents', 围巾: 'accents', 手套: 'accents', 披帛: 'accents', 系带: 'accents',
    装饰: 'accents', 小装饰: 'accents', 首饰: 'accents', 耳饰: 'accents', 手: 'accents',
    腰部: 'accents',
    花纹: 'pattern', 材质: 'material',
    // 与衣物互动的动作，本来就该归到「动作」
    与袜子互动: '→动作/interaction',
    与裙子互动: '→动作/interaction',
    与裤子互动: '→动作/interaction',
    // 这三条是整段 <lora:...> 提示词，不是标签
    '唐风:': null, '宋风:': null, '明风:': null,
  },
  外表: {
    // hair / eyes 走专门的拆分逻辑（见 splitSub）
    头发: 'SPLIT_HAIR', hair: 'SPLIT_HAIR',
    眼睛: 'eye_features', 瞳孔: 'eye_features', eyes: 'SPLIT_EYES',
    面部: 'face', 嘴巴: 'face', 鼻子: 'face', 牙齿: 'face', 舌头: 'face', 脸型: 'face', 眉毛: 'face',
    耳朵: 'ears',
    皮肤: 'skin',
    指甲: 'body', 身材: 'body', 胸部: 'body', 腹部: 'body', 肩部: 'body', 腰部: 'body',
    翅膀: 'wings',
    身份: 'identity', 二次元角色: 'character', 对象: 'demographics', 年龄: 'demographics',
  },
  动作: {
    posture: 'pose', 基础动作: 'pose', 其他动作: 'pose', 主角动作: 'pose', 腿部动作: 'pose',
    gesture: 'gesture', 手部动作: 'gesture',
    '手部动作(抓着某物)': 'gesture',
    '手部动作(放在某地)': 'gesture',
    '手部动作(拿着某物)': 'holding',
    interaction: 'interaction',
    expression: 'expression', 其他表情: 'expression', 笑: 'expression', 哭: 'expression',
    生气: 'expression', 不开心: 'expression', 蔑视: 'expression',
    camera: 'camera', 特写镜头: 'camera', 镜头: 'camera', 镜头角度: 'camera', 其他构图: 'camera',
    kinetic: 'motion',
    combat: 'combat',
    physiological: 'physiological',
    性感姿势: 'lewd', 挑逗: 'lewd',
    效果: 'effect',
  },
  场景: {
    setting: 'setting', 室内: 'setting', 浴室: 'setting', 城市: 'setting', 地板: 'setting',
    props: 'props', 床上用品: 'props', 其它物品: 'props', 数码设备: 'props',
    餐具: 'props', 乐器: 'props', 学习用品: 'props',
    atmosphere: 'atmosphere', 氛围: 'atmosphere',
    食物: 'food',
    家具: 'furniture',
    大自然: 'nature', 植物: 'nature', 水: 'nature', 季节: 'nature', 动物: 'nature',
    云: 'sky', 天空: 'sky',
    天气: 'weather',
    室外: 'outdoor',
    武器: 'weapon',
  },
  风格: {
    layout: 'layout',
    style: 'style', 艺术风格: 'style', 艺术派系: 'style', 艺术类型: 'style',
    写实: 'style', 素描: 'style', 艺术家风格: 'style',
    背景: 'background', 颜色: 'color', 光照: 'lighting',
    画质: 'quality', 画笔: 'medium',
    // 这两类是整段提示词模板，单独放一类，免得和真正的标签混在一起
    '魔法1.0': 'preset', '魔法1.5': 'preset',
  },
  NSFW: {
    骑乘: 'pose', 性交姿势: 'pose', 后入: 'pose',
    口交: 'physiological', 乳交: 'physiological', 足交: 'physiological', 精液: 'physiological',
    性器官: 'anatomy',
    性玩具: 'holding',
    兽人: 'demographics',
    触手: 'creature',
    全裸: 'outfit', 半裸: 'outfit', 局部裸: 'outfit', 半脱衣: 'outfit',
    脱衣: 'outfit', 透视: 'outfit', 半窄: 'outfit', 局部窄: 'outfit',
  },
};

/* 新库（英文子类）里几个代号统一到本表用的那套 */
const NEW_SUB_ALIAS = {
  posture: 'pose',
  kinetic: 'motion',
  handheld: 'holding',
  eye_features: 'eye_features',
};

/* ==========================================================================
   3) 发色 / 瞳色 的识别
   ========================================================================== */
const COLOR_WORDS = new Set([
  'black', 'brown', 'blonde', 'blond', 'white', 'silver', 'grey', 'gray', 'red', 'pink',
  'purple', 'blue', 'green', 'yellow', 'orange', 'aqua', 'violet', 'teal', 'turquoise',
  'indigo', 'magenta', 'cyan', 'lime', 'navy', 'olive', 'maroon', 'beige', 'cream',
  'gold', 'golden', 'platinum', 'auburn', 'ginger', 'rainbow', 'multicolor', 'multicolored',
  'colorful', 'two-tone', 'twotone', 'streaked', 'gradient', 'split', 'ombre',
  'chestnut', 'hazel', 'amber', 'crimson', 'scarlet', 'emerald', 'sapphire', 'rose',
  'lavender', 'peach', 'mint', 'coral', 'ivory', 'ebony', 'chocolate', 'caramel',
]);
/** 这些只是修饰词，单独出现时不算"颜色"（否则 colored sclera、bright pupils 会被误判成瞳色） */
const COLOR_MODS = new Set(['light', 'dark', 'pale', 'bright', 'deep', 'ash', 'colored', 'highlighted']);
const SUBJECT_TAIL = /^(.*?)\s*(hair|haired|eyes|eyed|pupils|pupil|sclera|irises|iris)$/i;

/**
 * 判断一条标签是不是"颜色 + 部位"的纯颜色词。
 *   black hair / light brown hair / two-tone hair → true
 *   hair between eyes / long hair / ahoge       → false
 */
function isColorTag(tag) {
  const m = SUBJECT_TAIL.exec(norm(tag).toLowerCase());
  if (!m) return false;
  const head = (m[1] || '').trim();
  if (!head) return false;
  const words = head.split(/[\s-]+/).filter(Boolean);
  if (!words.length) return false;
  // 必须至少含一个真正的颜色名；只有 light/dark/colored 这类修饰词不算
  if (!words.some((w) => COLOR_WORDS.has(w))) return false;
  return words.every((w) => COLOR_WORDS.has(w) || COLOR_MODS.has(w));
}

/** 把 hair / eyes 类拆成 颜色 与 造型/特征 两堆 */
function splitSub(tag, sub) {
  const t = norm(tag).toLowerCase();
  // 胡须类虽然带 hair，但长在脸上，归「面部」更符合直觉
  if (/\b(facial hair|beard|mustache|moustache|sideburns|goatee)\b/.test(t)) return 'face';
  const color = isColorTag(tag);
  if (sub === 'hair') return color ? 'hair_color' : 'hair_style';
  if (sub === 'eyes') return color ? 'eye_color' : 'eye_features';
  return sub;
}

/** anatomy 再按部位拆细：耳朵 / 尾巴 / 翅膀 / 角 / 皮肤 */
function splitAnatomy(tag) {
  const t = norm(tag).toLowerCase();
  if (/(^|[\s(])ears?$/.test(t)) return 'ears';
  if (/(^|[\s(])tails?$/.test(t) && !/ponytail/.test(t)) return 'tail';
  if (/(^|[\s(])wings?$/.test(t)) return 'wings';
  if (/(^|[\s(])horns?$/.test(t)) return 'horns';
  if (/skin$/.test(t)) return 'skin';
  return 'body';
}

/**
 * 镜头/构图术语：它们描述的是画面怎么取的，不是"穿了什么"。
 * 旧库把 full body 放进了服装/outfit、把 upper body 放进外表/anatomy，
 * 用户翻服装时看到"全身"会莫名其妙。
 */
const CAMERA_TERMS = new Set([
  'full body', 'upper body', 'lower body', 'portrait', 'close-up', 'cowboy shot',
  'from above', 'from below', 'from side', 'from behind', 'from outside',
  'looking at viewer', 'looking away', 'looking back', 'looking down', 'looking up',
  'solo focus', 'wide shot', 'medium shot', 'feet out of frame', 'head out of frame',
  'partially visible', 'faceless', 'faceless male', 'faceless female',
  'dutch angle', 'foreshortening', 'perspective', 'depth of field', 'blurry',
  'blurry background', 'bokeh', 'motion blur', 'chromatic aberration', 'wide angle',
]);
/** 这些词描述体型，放"身体"比放"画风"合理 */
const BODY_TERMS = new Set([
  'chibi', 'chibi only', 'petite', 'tall', 'short', 'muscular', 'curvy', 'fat', 'slim',
  'wide hips', 'thick thighs', 'narrow waist',
]);

/** 统一入口：先看子类，再看几个通用的纠偏表 */
function fixSub(tag, group, sub) {
  const t = norm(tag).toLowerCase();
  if (group === '服装' && CAMERA_TERMS.has(t)) return { group: '动作', sub: 'camera' };
  if (group === '外表' && CAMERA_TERMS.has(t)) return { group: '动作', sub: 'camera' };
  if (group === '风格' && BODY_TERMS.has(t)) return { group: '外表', sub: 'body' };
  return { group, sub };
}

const NSFW_RULES = [
  [/clamp|clamps|chain|leash|collar|cuff|shibari|bondage|restraint|gag|blindfold|ball gag/i, 'SM/BDSM'],
  [/condom|dildo|vibrator|sex toy|onahole|artificial vagina|inconspicuous sex toy|anal beads|plug\b/i, 'holding'],
  [/grabbing|grab|holding|hand|finger|rubbing|touching|licking|suck|kiss|massage|guiding/i, 'gesture'],
  [/between|touching|against|on another|another's|two|multiple|with (a|an) /i, 'interaction'],
  [/on (penis|pussy|crotch|breasts|nipples|testicles|chest)|in (mouth|pussy|anus|penis)|covered|covering|presenting|exposed|peek|slip|cutout|only|naked|nude|clothed|see-through|transparent/i, 'outfit'],
  [/penis|pussy|vagina|clitoris|labia|nipple|testicle|breast|anus|anal|pubic|urethra|semen|cum|erection|areola/i, 'anatomy'],
  [/ejacul|orgasm|aroused|wet|precum|squirt|cumshot|erect/i, 'physiological'],
];
function classifyNsfw(tag, zh) {
  const t = norm(tag).toLowerCase() + ' ' + norm(zh);
  for (const [re, sub] of NSFW_RULES) if (re.test(t)) return sub;
  return 'anatomy';
}

/* ==========================================================================
   4) 常用置顶表
   --------------------------------------------------------------------------
   新库的标签有源数组下标当热度；旧库独有的大约 2,700 条没有下标，
   其中却包含 1girl / solo / smile / dress 这类**最常用**的。
   下面这份表把它们钉到各自子类的最前面，顺序就是显示顺序。
   ========================================================================== */
const COMMON_TOP = [
  '1girl', '1boy', 'solo', 'multiple girls', '2girls', 'no humans',
  'masterpiece', 'best quality',
  'long hair', 'short hair', 'very long hair', 'twintails', 'ponytail', 'ahoge', 'bangs',
  'black hair', 'brown hair', 'blonde hair', 'white hair', 'silver hair', 'blue hair',
  'red hair', 'pink hair', 'purple hair', 'green hair',
  'blue eyes', 'red eyes', 'green eyes', 'brown eyes', 'purple eyes', 'yellow eyes',
  'grey eyes', 'heterochromia',
  'smile', 'open mouth', 'closed mouth', 'blush', 'crying', 'angry', 'surprised',
  'looking at viewer', 'looking away', 'from above', 'from below', 'from side', 'from behind',
  'upper body', 'lower body', 'full body', 'portrait', 'close-up', 'cowboy shot',
  'standing', 'sitting', 'lying', 'walking', 'running', 'jumping', 'crossed arms',
  'hand on hip', 'arms up', 'head tilt', 'holding sword', 'holding book',
  'dress', 'skirt', 'pleated skirt', 'school uniform', 'serafuku', 'shirt', 'white shirt',
  't-shirt', 'sweater', 'hoodie', 'jacket', 'coat', 'cloak', 'kimono', 'yukata',
  'thighhighs', 'socks', 'boots', 'high heels', 'gloves', 'hat', 'glasses', 'ribbon',
  'hair ribbon', 'hair ornament', 'necktie', 'scarf', 'armor', 'swimsuit', 'bikini',
  'cat ears', 'animal ears', 'fox ears', 'horns', 'tail', 'wings', 'halo', 'fangs',
  'pointy ears', 'elf', 'demon girl', 'angel',
  'breasts', 'large breasts', 'small breasts', 'cleavage', 'navel', 'collarbone',
  'indoors', 'outdoors', 'classroom', 'bedroom', 'street', 'forest', 'beach',
  'night', 'night sky', 'starry sky', 'blue sky', 'sunset', 'cherry blossoms', 'flowers',
  'rain', 'snow', 'window', 'bed', 'chair', 'table',
  'soft lighting', 'backlighting', 'rim light', 'candlelight', 'moonlight', 'sunlight',
  'depth of field', 'blurry background', 'cinematic lighting', 'bokeh',
  'monochrome', 'watercolor', 'oil painting', 'sketch', 'anime style', 'realistic', 'chibi',
];
const pinOf = new Map(COMMON_TOP.map((t, i) => [key(t), i]));

/**
 * 释义修正表。
 * 机翻/手翻都留下了一些明显不对或缺失的条目，这里逐条订正。
 * 键是归一化后的英文标签，值是最终显示的中文。
 */
const ZH_FIX = {
  'full body': '全身构图',
  'reflective eyes': '反光眼睛',
  'one side up': '单侧上梳',
  'x hair ornament': '×形发饰',
  'brown footwear': '棕色鞋',
  'brown scarf': '棕色围巾',
  'brown gloves': '棕色手套',
  'brown sweater': '棕色毛衣',
  'brown headwear': '棕色头饰',
  'brown vest': '棕色背心',
  'brown-framed eyewear': '棕色镜框眼镜',
  'slingshot swimsuit': '弹弓式泳衣',
  'domino mask': '多米诺面具',
  'twintails': '双马尾',
  'chibi': 'Q版',
  'hug': '拥抱',
  'blush stickers': '脸红贴纸',
  'colored sclera': '彩色眼白',
  'bright pupils': '明亮瞳孔',
  'silver hair': '银发',
  'ponytail': '马尾',
  'hair between eyes': '刘海遮眼',
  'ahoge': '呆毛',
  'sidelocks': '鬓发',
  'blunt bangs': '齐刘海',
  'parted bangs': '中分刘海',
  'medium hair': '中长发',
  'very long hair': '超长发',
  'hair bun': '发髻',
  'two-tone hair': '双色发',
  'multicolored hair': '多色发',
  'heterochromia': '异色瞳',
  'closed eyes': '闭眼',
  'half-closed eyes': '半睁眼',
};

/* ==========================================================================
   5) 合并 + 去重
   --------------------------------------------------------------------------
   同名标签（含写法差异）只留一条。**关键是 group 与 sub 必须成对决定** ——
   之前两者各自"先到先得"，会出现 `服装 / 动物` 这种根本不存在的组合
   （同一个 tag 先以 服装/帽子 出现、又以 场景/动物 出现，组留了前者、子类留了后者）。

   取舍顺序：
     ① 角色扮演/cosplay —— 旧库显式把 cosplay 类抽出来过，这是用户看得懂的分类，优先保留
     ② 新库（英文子类）—— 社区库的分类体系，一条 tag 只有一个规范位置
     ③ 旧库（中文子类）—— 按 SUB_MAP 折算
   ========================================================================== */
const out = new Map(); // key → { tag, group, sub, zh, alt:Set, also:Set }
const dropped = { prompt: 0, nullZh: 0 };

/** 把一条原始记录折算成规范的 (group, sub)；返回 null 表示这条该丢弃 */
function canonicalize(t) {
  const raw = String(t.tag || '').trim();
  if (!raw) return null;
  // 提示词串（含逗号 / <lora:...>）不是标签
  if (/<lora:/i.test(raw) || raw.includes(',')) return null;

  const tag = norm(raw);
  const isNewLib = !/[\u4e00-\u9fff]/.test(t.sub || '');
  let group = t.group || '其他';
  let sub = t.sub || '';

  const map = SUB_MAP[group];
  if (map && Object.prototype.hasOwnProperty.call(map, sub)) {
    const mapped = map[sub];
    if (mapped === null) return null; // 明确丢弃
    if (mapped.startsWith('→')) {
      // 跨组迁移：例如 服装/与裙子互动 → 动作/interaction
      group = mapped.slice(1).split('/')[0];
      sub = mapped.slice(1).split('/')[1];
    } else {
      sub = mapped;
    }
  } else if (NEW_SUB_ALIAS[sub]) {
    sub = NEW_SUB_ALIAS[sub];
  }

  if (sub === 'SPLIT_HAIR') sub = splitSub(tag, 'hair');
  else if (sub === 'SPLIT_EYES') sub = splitSub(tag, 'eyes');
  else if (sub === 'hair' || sub === 'eyes') sub = splitSub(tag, sub);
  else if (sub === 'anatomy') sub = splitAnatomy(tag);

  // NSFW 的老标签没有子类，按关键词归位，别留空白格子
  if (group === 'NSFW' && !sub) sub = classifyNsfw(tag, t.zh);

  // NSFW 沿用旧库的细子类，但个别和别的组叫法不同，统一一下
  if (group === 'NSFW') {
    if (sub === 'body' || sub === 'skin') sub = 'anatomy';
    if (sub === 'hair_style' || sub === 'hair_color') sub = 'hair';
  }

  // 通用纠偏：镜头词不该待在服装/外表，体型词不该待在画风
  ({ group, sub } = fixSub(tag, group, sub));

  return { tag, group, sub, isNewLib, zh: norm(t.zh), raw, alt: t.alt || [] };
}

// 先按 key 收集所有候选
const cands = new Map();
for (const t of cur) {
  const c = canonicalize(t);
  if (!c) {
    dropped.prompt++;
    continue;
  }
  const k = key(c.tag);
  if (!cands.has(k)) cands.set(k, []);
  cands.get(k).push(c);
}

for (const [k, list] of cands) {
  // 选主记录：角色扮演 > 新库 > 旧库
  const cosplay = list.find((c) => c.group === '角色扮演');
  const newLib = list.find((c) => c.isNewLib);
  const main = cosplay || newLib || list[0];

  const e = { tag: main.tag, group: main.group, sub: main.sub, zh: '', alt: new Set(), also: new Set() };

  // 释义：新库优先（术语一致），旧库兜底；两边的都收进 also 供检索
  const newZh = list.filter((c) => c.isNewLib && c.zh && c.zh !== 'NULL').map((c) => c.zh);
  const oldZh = list.filter((c) => !c.isNewLib && c.zh && c.zh !== 'NULL').map((c) => c.zh);
  const primary = (newZh[0] || oldZh[0] || '').trim();
  if (!primary) dropped.nullZh++;
  e.zh = ZH_FIX[k] || primary;
  for (const z of [...newZh, ...oldZh]) if (z && z !== primary) e.also.add(z);

  for (const c of list) if (c.raw !== c.tag) e.alt.add(c.raw);
  // 保留输入里已有的备用写法，保证脚本可以重复运行（幂等）
  for (const c of list) for (const a of c.alt || []) if (norm(a) !== e.tag) e.alt.add(norm(a));

  out.set(k, e);
}


/* ==========================================================================
   6) 兜底：把还没映射到的中文子类归到 misc，避免出现中英两套
   ========================================================================== */
const leftover = new Map();
for (const e of out.values()) {
  if (/[\u4e00-\u9fff]/.test(e.sub)) {
    leftover.set(e.group + '/' + e.sub, (leftover.get(e.group + '/' + e.sub) || 0) + 1);
    e.sub = 'misc';
  }
}
if (leftover.size) {
  console.log('⚠ 下面这些中文子类没有映射，已归到 misc：');
  for (const [k, v] of [...leftover].sort((a, b) => b[1] - a[1])) console.log(`   ${k} (${v})`);
}

/* ==========================================================================
   7) 排序：组顺序固定，子类按"常用程度"排，标签按 置顶 > 热度 > 名字
   ========================================================================== */
const GROUP_ORDER = ['服装', '角色扮演', '外表', '动作', '场景', '动物', '风格', 'NSFW'];
const SUB_ORDER = {
  服装: ['outfit', 'top', 'bottom', 'headwear', 'accents', 'legwear', 'footwear', 'eyewear', 'underwear', 'material', 'pattern', 'misc'],
  角色扮演: ['cosplay'],
  外表: ['hair_style', 'hair_color', 'eye_color', 'eye_features', 'face', 'body', 'skin', 'ears', 'tail', 'wings', 'horns', 'demographics', 'identity', 'character', 'misc'],
  动作: ['pose', 'gesture', 'interaction', 'expression', 'camera', 'motion', 'combat', 'lewd', 'physiological', 'holding', 'effect', 'misc'],
  场景: ['setting', 'indoor', 'outdoor', 'nature', 'props', 'furniture', 'food', 'weapon', 'sky', 'weather', 'atmosphere', 'misc'],
  动物: ['animal'],
  风格: ['quality', 'style', 'layout', 'color', 'lighting', 'background', 'medium', 'preset', 'misc'],
  NSFW: ['anatomy', 'physiological', 'pose', 'interaction', 'gesture', 'outfit', 'underwear', 'top', 'bottom', 'legwear', 'footwear', 'headwear', 'accents', 'eyewear', 'hair', 'demographics', 'holding', 'props', 'weapon', 'camera', 'combat', 'animal', 'creature', 'setting', 'layout', 'atmosphere', 'style', 'SM/BDSM', 'misc'],
};

const SUB_ZH = {
  outfit: '整装', top: '上装', bottom: '下装', headwear: '头饰', accents: '配饰',
  legwear: '腿袜', footwear: '鞋履', eyewear: '眼镜', underwear: '内衣',
  material: '材质', pattern: '花纹',
  cosplay: 'cosplay',
  hair_style: '发型', hair_color: '发色', eye_color: '瞳色', eye_features: '眼睛',
  face: '面部', body: '身体', skin: '皮肤', ears: '耳朵', tail: '尾巴',
  wings: '翅膀', horns: '角', demographics: '人口属性', identity: '身份', character: '角色',
  anatomy: '身体', hair: '毛发', eyes: '眼睛',
  pose: '姿态', gesture: '手势', interaction: '互动', expression: '表情',
  camera: '镜头', motion: '动态', combat: '战斗', lewd: '性感', physiological: '生理反应',
  holding: '手持物', effect: '画面效果',
  setting: '场景', indoor: '室内', outdoor: '室外', nature: '自然', props: '道具',
  furniture: '家具', food: '食物', weapon: '武器', sky: '天空', weather: '天气',
  atmosphere: '氛围',
  animal: '动物', creature: '生物',
  quality: '画质', style: '画风', layout: '构图', color: '颜色', lighting: '光照',
  background: '背景', medium: '媒介', preset: '提示词预设',
  'SM/BDSM': 'SM/BDSM',
  misc: '其它',
};

const byGroup = new Map();
for (const e of out.values()) {
  if (!byGroup.has(e.group)) byGroup.set(e.group, []);
  byGroup.get(e.group).push(e);
}

const final = [];
for (const g of GROUP_ORDER) {
  const list = byGroup.get(g);
  if (!list) continue;
  const subRank = new Map((SUB_ORDER[g] || []).map((s, i) => [s, i]));
  list.sort((a, b) => {
    const sa = subRank.has(a.sub) ? subRank.get(a.sub) : 999;
    const sb = subRank.has(b.sub) ? subRank.get(b.sub) : 999;
    if (sa !== sb) return sa - sb;
    if (a.sub !== b.sub) return a.sub.localeCompare(b.sub);
    const pa = pinOf.has(key(a.tag)) ? pinOf.get(key(a.tag)) : 1e9;
    const pb = pinOf.has(key(b.tag)) ? pinOf.get(key(b.tag)) : 1e9;
    if (pa !== pb) return pa - pb;
    const ra = rankOf.has(key(a.tag)) ? rankOf.get(key(a.tag)) : 1e9;
    const rb = rankOf.has(key(b.tag)) ? rankOf.get(key(b.tag)) : 1e9;
    if (ra !== rb) return ra - rb;
    return a.tag.localeCompare(b.tag);
  });
  for (const e of list) {
    const o = { tag: e.tag, zh: e.zh, group: e.group, sub: e.sub };
    if (e.alt.size) o.alt = [...e.alt];
    if (e.also.size) o.also = [...e.also];
    final.push(o);
  }
}

/* ==========================================================================
   8) 统计 + 写出
   ========================================================================== */
console.log(`原库 ${cur.length} 条 → 去重后 ${final.length} 条（去掉 ${cur.length - final.length} 条重复）`);
console.log(`其中丢弃提示词串 ${dropped.prompt} 条、空释义 ${dropped.nullZh} 条`);
console.log('');
const gStat = {};
const sStat = {};
for (const t of final) {
  gStat[t.group] = (gStat[t.group] || 0) + 1;
  const k = t.group + ' › ' + t.sub;
  sStat[k] = (sStat[k] || 0) + 1;
}
console.log('一级分类：');
for (const g of GROUP_ORDER) if (gStat[g]) console.log(`  ${g.padEnd(6)} ${gStat[g]}`);
console.log('\n二级分类：');
for (const g of GROUP_ORDER) {
  const subs = Object.entries(sStat).filter(([k]) => k.startsWith(g + ' › '));
  if (!subs.length) continue;
  console.log(`  【${g}】`);
  for (const [k, v] of subs) console.log(`     ${(SUB_ZH[k.split(' › ')[1]] || k.split(' › ')[1]).padEnd(8)} ${String(v).padStart(5)}  ${k.split(' › ')[1]}`);
}

if (DRY) {
  console.log('\n--dry：没有写文件。');
  process.exit(0);
}

fs.writeFileSync(LIB, JSON.stringify(final));
fs.writeFileSync(
  path.join(ROOT, 'public/tags/meta.json'),
  JSON.stringify({ groups: GROUP_ORDER.filter((g) => gStat[g]), subZh: SUB_ZH, counts: gStat }),
);
console.log(`\n写入 public/tags/novelai-tags.json  ${(fs.statSync(LIB).size / 1048576).toFixed(2)} MB`);
console.log('写入 public/tags/meta.json');
