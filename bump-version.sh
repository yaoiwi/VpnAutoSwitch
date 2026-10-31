#!/usr/bin/env bash
# 版本号自动升级：改一处就够了，README / CHANGELOG 一起同步。
#
#   ./bump-version.sh --patch --note "修复：……"    功能不变，只修 bug
#   ./bump-version.sh --minor --note "新增：……"    加功能
#   ./bump-version.sh --major                       不兼容的大改
#   ./bump-version.sh --code                        版本名不变，只把 versionCode +1
#
# 每次都会做的事：
#   1. app/build.gradle.kts 的 appVersionCode +1（必做，LSPosed 靠它判断有没有新版本）
#   2. appVersionName 按 --patch/--minor/--major 递增
#   3. README.md / README_EN.md 的版本徽章与 APK 文件名
#   4. CHANGELOG.md 新开一节（--code 时不新开）
#
# 生成完还会提醒两件事：应用内更新日志首条（MainScreen.kt 的 CHANGELOG_TIPS），
# 以及两个仓库各自的 tag 格式。CHANGELOG_TIPS 没跟上时 assembleRelease 会直接失败。

set -euo pipefail

cd "$(dirname "$0")"

PART=""
NOTE=""
NEW_SECTION=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --patch) PART="patch" ;;
        --minor) PART="minor" ;;
        --major) PART="major" ;;
        --code)  PART="code"; NEW_SECTION=0 ;;
        --note)  NOTE="${2:-}"; shift ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "未知参数：$1（用 --help 看用法）" >&2
            exit 1 ;;
    esac
    shift
done

if [[ -z "$PART" ]]; then
    echo "请指定 --patch / --minor / --major / --code 之一" >&2
    exit 1
fi

CODE=$(grep -oP 'val appVersionCode = \K[0-9]+' app/build.gradle.kts)
NAME=$(grep -oP 'val appVersionName = "\K[0-9.]+' app/build.gradle.kts)

IFS='.' read -r MAJOR MINOR PATCH <<< "$NAME"
NEW_CODE=$((CODE + 1))

case "$PART" in
    code)  NEW_NAME="$NAME" ;;
    patch) NEW_NAME="$MAJOR.$MINOR.$((PATCH + 1))" ;;
    minor) NEW_NAME="$MAJOR.$((MINOR + 1)).0" ;;
    major) NEW_NAME="$((MAJOR + 1)).0.0" ;;
esac

if [[ "$NEW_NAME" == "$NAME" && "$PART" == "code" ]]; then
    echo "◦ 版本名保持 $NAME，versionCode $CODE -> $NEW_CODE"
else
    echo "◦ 版本 $NAME ($CODE) -> $NEW_NAME ($NEW_CODE)"
fi

python3 - "$CODE" "$NEW_CODE" "$NAME" "$NEW_NAME" "$NEW_SECTION" "$NOTE" <<'PY'
import datetime
import re
import sys

old_code, new_code, old_name, new_name, new_section, note = sys.argv[1:7]
new_section = new_section == "1"

def edit(path, func):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    out = func(src)
    if out != src:
        with open(path, "w", encoding="utf-8") as f:
            f.write(out)

# 1) 版本号本体
def bump(src):
    src = re.sub(r"val appVersionCode = \d+", f"val appVersionCode = {new_code}", src, count=1)
    return re.sub(
        r'val appVersionName = "[0-9.]+"',
        f'val appVersionName = "{new_name}"',
        src,
        count=1,
    )

edit("app/build.gradle.kts", bump)

# 2) README 的徽章与下载文件名
def readme(src):
    src = src.replace(f"Release-{old_name}", f"Release-{new_name}")
    return src.replace(f"VpnAutoSwitch-{old_name}.apk", f"VpnAutoSwitch-{new_name}.apk")

for path in ("README.md", "README_EN.md"):
    edit(path, readme)

# 3) CHANGELOG 新开一节，插在第一条 ## 之前
if new_section:
    def changelog(src):
        if f"## {new_name} " in src or f"## {new_name}—" in src:
            return src
        today = datetime.date.today().isoformat()
        body = f"## {new_name} — {today}\n\n"
        body += note if note else "- "
        body += "\n\n"
        lines = src.split("\n")
        for i, line in enumerate(lines):
            if line.startswith("## "):
                lines.insert(i, body.rstrip("\n"))
                lines.insert(i + 1, "")
                return "\n".join(lines)
        return src + "\n" + body

    edit("CHANGELOG.md", changelog)
PY

echo
echo "✔ 已更新 app/build.gradle.kts、README.md、README_EN.md、CHANGELOG.md"
if [[ -n "$NOTE" ]]; then
    echo "  CHANGELOG 首条：$NOTE"
fi
echo
echo "还需要手改一处（改漏了 assembleRelease 会直接报错）："
echo "  app/src/main/java/com/vpnautoswitch/module/ui/MainScreen.kt"
echo "    CHANGELOG_TIPS 首条改成 \"$NEW_NAME：……\"，只保留最近 3 条"
echo
echo "发版用的两个 tag："
echo "  主仓库 GitHub  : v$NEW_NAME"
echo "  LSPosed 模块库 : $NEW_CODE-$NEW_NAME"
