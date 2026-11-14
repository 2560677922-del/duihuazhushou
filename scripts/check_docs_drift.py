#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档漂移检查：拦住「README 与代码实现说法不一致」回潮。

背景：本项目曾出现三处文档各说一半真话、AI 每次读其中一处就去「纠正」
另一处，导致 README / 改动记录来回翻烧饼（离屏位图是否被删、是否依赖
androidx、APK 体积到底多少）。这类断言不该靠人肉记忆维护。

用法：
    python3 scripts/check_docs_drift.py          # 本地
    # CI: .github/workflows/docs-drift.yml

原则：**以代码为准**。断言失败时请改文档去贴合代码，不要改断言去迁就旧文档。
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent

# (相对路径, 不该再出现的片段, 原因)
FORBIDDEN = [
    ("README.md", "APK 约 4.3 MB", "APK 体积写死必然过期，三个来源曾给出三个数"),
    ("README.md", "不依赖 androidx / material", "ChatAdapter 已使用 androidx.recyclerview"),
    ("dbpg/README.md", "不引 androidx / material", "app 依赖 androidx.recyclerview + okhttp"),
    ("dbpg/README.md", "APK ≈ 5.5 MB", "体积写死必过期，且与根 README / 改动记录三个数不一致"),
    ("dbpg/README.md", "列表里卡片是**离屏位图**", "§18 之后 WebView 壳才是主路径"),
    ("dbpg/README.md", "现在是离屏画 WebP + LRU 复用", "位图已降级为 fallback"),
    ("dbpg/docs/改动记录.md", "同理保留但不再主动触发", "已勘误：else 分支仍会主动请求位图"),
    ("dbpg/app/src/main/java/com/dbpg/duihuazhushou/settings/PluginSettingsActivity.kt",
     "按预设模板补出折叠窗", "插件刻意不生成面板，这是旧实现的描述"),
]

# (相对路径, 必须保留的锚点)：防止有人把「以代码为准」的指引删掉
REQUIRED = [
    ("README.md", "实现状态以代码为准"),
    ("README.md", "ChatAdapter.useWeb"),
    ("dbpg/README.md", "ChatAdapter.useWeb"),
    ("dbpg/docs/改动记录.md", "勘误"),
]

# 代码锚点：这些符号一旦消失，说明渲染/插件主路径变了，文档锚点必须同步更新
CODE_ANCHORS = [
    ("dbpg/app/src/main/java/com/dbpg/duihuazhushou/chat/ChatAdapter.kt", "fun useWeb("),
    ("dbpg/app/src/main/java/com/dbpg/duihuazhushou/chat/render/CardRenderer.kt", "fun render("),
    ("dbpg/app/src/main/java/com/dbpg/duihuazhushou/chat/render/plugin/PluginCatalog.kt", "BUILTIN_IDS"),
]


def read(rel: str) -> str:
    p = ROOT / rel
    return p.read_text(encoding="utf-8") if p.exists() else ""


def main() -> int:
    problems = []
    for rel, phrase, why in FORBIDDEN:
        if phrase in read(rel):
            problems.append(f"[过时断言] {rel}\n      仍含「{phrase}」→ {why}")
    for rel, phrase in REQUIRED:
        if phrase not in read(rel):
            problems.append(f"[缺锚点] {rel}\n      缺少「{phrase}」，改文档时别删掉这条指引")
    for rel, symbol in CODE_ANCHORS:
        if symbol not in read(rel):
            problems.append(f"[代码变了] {rel}\n      找不到「{symbol}」——主路径可能已改，请同步文档锚点")

    if problems:
        print("文档漂移检查未通过：\n")
        for p in problems:
            print("  - " + p)
        print("\n以代码为准修正文档，不要改断言去迁就旧文档。")
        return 1
    print("文档漂移检查通过：README / 改动记录与当前实现一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
