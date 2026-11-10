#!/usr/bin/env bash
# 把某个已发布的版本同步到 LSPosed 模块库仓库。
#
#   ./scripts/sync-lsposed.sh                    # 用 build.gradle.kts 里的版本，APK 自动找
#   ./scripts/sync-lsposed.sh 1.9.12             # 指定版本
#   ./scripts/sync-lsposed.sh 1.9.12 --apk /path/to.apk
#   ./scripts/sync-lsposed.sh 1.9.12 --force     # 已存在时替换资产
#   ./scripts/sync-lsposed.sh 1.9.12 --dry-run   # 只打印要做什么，不写远端
#
# 需要 token：LSP_REPO_TOKEN 或 GH_TOKEN，对模块仓库有 push 权限即可（public_repo 够）。
# 主仓库必须已经有 v<版本> 的 Release，正文会整段复制过去（去掉开头的 ## 版本号）。
#
# 模块库的 tag 格式是 [versionCode]-[versionName]，和主仓库的 v1.2.3 不一样。
# 以前这一步纯手工，1.9.9 之后连漏了三个版本，所以单独成脚本。

set -euo pipefail

cd "$(dirname "$0")/.."

MODULE_REPO="Xposed-Modules-Repo/io.github.yaoiwi.vpnautoswitch"
MAIN_REPO="yaoiwi/VpnAutoSwitch"
TOKEN="${LSP_REPO_TOKEN:-${GH_TOKEN:-}}"

VER=""
APK=""
CODE_ARG=""
FORCE=0
DRY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="${2:-}"; shift ;;
        --code) CODE_ARG="${2:-}"; shift ;;
        --force) FORCE=1 ;;
        --dry-run) DRY=1 ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "未知参数：$1" >&2; exit 1 ;;
        *) VER="$1" ;;
    esac
    shift
done

if [[ -z "$TOKEN" ]]; then
    echo "缺少 token：设置 LSP_REPO_TOKEN 或 GH_TOKEN" >&2
    exit 1
fi

APP_VER=$(grep -oP 'val appVersionName = "\K[0-9.]+' app/build.gradle.kts)
CODE=$(grep -oP 'val appVersionCode = \K[0-9]+' app/build.gradle.kts)
[[ -n "$VER" ]] || VER="$APP_VER"
[[ -n "$CODE_ARG" ]] && CODE="$CODE_ARG"

if [[ -z "$VER" || -z "$CODE" ]]; then
    echo "读不到版本号，检查 app/build.gradle.kts" >&2
    exit 1
fi

# 补发旧版本时，源码里的 versionCode 已经往前走了，tag 会打错。
# 这种情况必须显式给 --code，别让脚本猜。
if [[ "$VER" != "$APP_VER" && -z "$CODE_ARG" ]]; then
    echo "要同步 $VER，但当前源码是 $APP_VER（versionCode $CODE）。" >&2
    echo "补发旧版本请显式指定 --code <该版本的 versionCode>，否则 tag 会打错。" >&2
    exit 1
fi

TAG="$CODE-$VER"

if [[ "$VER" != "$APP_VER" ]]; then
    echo "注意：同步的是旧版本 $VER（versionCode $CODE），当前源码是 $APP_VER。"
fi

echo "◦ 目标：$MODULE_REPO"
echo "◦ 版本：$VER，tag $TAG"

# 带 Accept: application/octet-stream 的下载不能走 api()，否则头部会被默认值覆盖
api() {
    curl -sS -m 60 -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" "$@"
}
download_asset() {
    curl -sSL -m 300 -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/octet-stream" "$1"
}

json_get() {
    python3 -c "
import json,sys
try:
    d=json.loads(sys.argv[1])
except Exception:
    d={}
print(d.get(sys.argv[2]) or '')
" "$1" "$2"
}

# ---- 1. 主仓库的 release 必须先存在，正文从它复制
main_release=$(api "https://api.github.com/repos/$MAIN_REPO/releases/tags/v$VER" || true)
if [[ -z "$(json_get "$main_release" tag_name)" ]]; then
    echo "主仓库还没有 v$VER 的 Release，先发主仓库再同步模块库" >&2
    exit 1
fi

BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT
python3 - "$main_release" > "$BODY_FILE" <<'PY'
import json, re, sys
d = json.loads(sys.argv[1])
body = d.get("body") or ""
body = re.sub(r"^##\s*[\d.]+\s*\n+", "", body)
print(body, end="")
PY
echo "◦ 正文：$(wc -c < "$BODY_FILE") 字节，从 v$VER 复制"

# ---- 2. 找 APK，并确认它真是这个版本（构建产物可能是上一次留下的）
apk_version() {
    local aapt
    aapt=$(command -v aapt2 || true)
    if [[ -z "$aapt" ]]; then
        local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        [[ -n "$sdk" ]] && aapt=$(find "$sdk/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -n 1)
    fi
    [[ -n "$aapt" && -x "$aapt" ]] || return 0
    "$aapt" dump badging "$1" 2>/dev/null | head -1 | sed -n "s/.*versionName='\([^']*\)'.*/\1/p"
}

pick_apk() {
    local c v
    for c in \
        "app/build/outputs/apk/release/VpnAutoSwitch-$VER.apk" \
        "../VpnAutoSwitch-$VER-release.apk" \
        "app/build/outputs/apk/release/app-release.apk"; do
        [[ -f "$c" ]] || continue
        v=$(apk_version "$c")
        if [[ -n "$v" && "$v" != "$VER" ]]; then
            echo "  跳过 $c（里面是 $v）" >&2
            continue
        fi
        echo "$c"
        return 0
    done
    return 1
}

if [[ -z "$APK" ]]; then
    APK=$(pick_apk) || {
        echo "找不到 $VER 的 APK，用 --apk 指定，或先跑一次 assembleRelease" >&2
        exit 1
    }
fi

if [[ ! -f "$APK" ]]; then
    echo "APK 不存在：$APK" >&2
    exit 1
fi

APK_VER=$(apk_version "$APK")
if [[ -n "$APK_VER" && "$APK_VER" != "$VER" ]]; then
    echo "$APK 的版本是 $APK_VER，不是 $VER" >&2
    exit 1
fi

echo "◦ APK：$APK（$(stat -c%s "$APK") 字节）"

# ---- 3. tag 已存在就不重复发
existing=$(api "https://api.github.com/repos/$MODULE_REPO/releases/tags/$TAG" || true)
rel_id=$(json_get "$existing" id)

if [[ -n "$rel_id" && $FORCE -eq 0 ]]; then
    echo "◦ tag $TAG 已存在（release $rel_id），跳过。要替换资产加 --force"
    exit 0
fi

# ---- 4. 和主仓库的 apk-sha256.txt 对一下，防止传错包
sha_id=$(python3 -c "
import json,sys
d=json.loads(sys.argv[1])
print(next((a['id'] for a in d.get('assets',[]) if a['name'].endswith('.txt')), ''))
" "$main_release")
if [[ -n "$sha_id" ]]; then
    expected=$(download_asset "https://api.github.com/repos/$MAIN_REPO/releases/assets/$sha_id" | awk '{print $1}')
    actual=$(sha256sum "$APK" | awk '{print $1}')
    if [[ -n "$expected" && "$expected" != "$actual" ]]; then
        echo "APK 与主仓库 v$VER 发布的不是同一个文件" >&2
        echo "  本地：$actual" >&2
        echo "  线上：$expected" >&2
        exit 1
    fi
    echo "◦ 校验：${actual:0:12}… 与 v$VER 一致"
fi

if [[ $DRY -eq 1 ]]; then
    if [[ -n "$rel_id" ]]; then
        echo "  [dry-run] 会删除 release $rel_id 的旧资产并重新上传"
    else
        echo "  [dry-run] 会创建 release $TAG 并上传"
    fi
    exit 0
fi

# ---- 5. 创建或复用 release
if [[ -n "$rel_id" ]]; then
    echo "◦ 复用 release $rel_id，先清掉旧资产"
    api "https://api.github.com/repos/$MODULE_REPO/releases/$rel_id/assets" \
        | python3 -c "
import json,sys
for a in json.load(sys.stdin):
    print(a['id'], a['name'])
" | while read -r aid name; do
        echo "  删除 $name"
        api -X DELETE "https://api.github.com/repos/$MODULE_REPO/releases/assets/$aid" > /dev/null
    done
else
    payload=$(mktemp)
    trap 'rm -f "$BODY_FILE" "$payload"' EXIT
    python3 - "$TAG" "$VER" "$BODY_FILE" > "$payload" <<'PY'
import json, sys
tag, ver, path = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "tag_name": tag,
    "target_commitish": "main",
    "name": ver,
    "body": open(path, encoding="utf-8").read(),
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY
    created=$(api -X POST -H "Content-Type: application/json" --data-binary @"$payload" \
        "https://api.github.com/repos/$MODULE_REPO/releases")
    rel_id=$(json_get "$created" id)
    if [[ -z "$rel_id" ]]; then
        echo "创建 release 失败：$created" >&2
        exit 1
    fi
    echo "◦ 已创建 release $rel_id"
fi

# ---- 6. 上传
up=$(curl -sS -m 600 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" \
    "https://uploads.github.com/repos/$MODULE_REPO/releases/$rel_id/assets?name=VpnAutoSwitch-$VER.apk" || true)

if [[ "$(json_get "$up" state)" != "uploaded" ]]; then
    echo "上传失败：$up" >&2
    exit 1
fi

echo "✔ $TAG 已同步（$(json_get "$up" size) 字节），模块库会在 5-60 分钟内刷新"
