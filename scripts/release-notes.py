#!/usr/bin/env python3
"""把 CHANGELOG.md 里某个版本的段落写成 GitHub Release 正文。

以前 workflow 用的是 generate_release_notes: true，但 tag 直接推上去时没有
关联的 pull request，GitHub 没有东西可汇总，只能填一行 compare 链接，release
页面等于空的。说明本来就是发版时写在 CHANGELOG 里的，直接从那儿取。

用法：scripts/release-notes.py 1.9.14 > notes.md
找不到对应段落也返回 0，输出一段兜底文字 —— 发版不该因为少写一节说明就失败。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"

FOOTER = (
    "**升级方式**：覆盖安装后**重启手机** —— 模块代码跑在 system_server 里，"
    "只重启应用不会重新加载。\n"
    "\n"
    "**完整更新日志**：https://github.com/yaoiwi/VpnAutoSwitch/blob/main/CHANGELOG.md"
)


def section(text: str, version: str) -> str:
    """取 `## <version>` 到下一个 `## ` 之间的正文，去掉标题行。"""
    m = re.search(rf"^## {re.escape(version)}\b.*?(?=^## |\Z)", text, re.S | re.M)
    if not m:
        return ""
    return "\n".join(m.group(0).strip().split("\n")[1:]).strip()


def main() -> int:
    if len(sys.argv) != 2:
        print("用法：release-notes.py <版本号>", file=sys.stderr)
        return 2
    version = sys.argv[1].lstrip("v")

    try:
        text = CHANGELOG.read_text(encoding="utf-8")
    except OSError:
        text = ""

    body = section(text, version)
    if not body:
        print(f"CHANGELOG.md 里没有 {version} 的段落", file=sys.stderr)
        body = (
            f"{version} 的说明还没写进 CHANGELOG，"
            "先看[更新日志](https://github.com/yaoiwi/VpnAutoSwitch/blob/main/CHANGELOG.md)。"
        )

    sys.stdout.write(f"{body}\n\n{FOOTER}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
