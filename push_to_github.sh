#!/usr/bin/env bash
# GenUI → GitHub 一键推送（代码 + APK Release）
#
# 本脚本在安全沙箱内无法直连 GitHub，故需在本机（可访问 GitHub 的环境）执行。
# 用法：
#   export GH_TOKEN=ghp_你的GitHub个人访问令牌
#   cd /workspace/GenUI-project
#   ./push_to_github.sh [仓库名=GenUI] [public|private=public]
#
# 说明：
#   - 令牌只从环境变量 GH_TOKEN 读取，绝不写入仓库文件，避免泄露。
#   - 仓库若不存在则创建；已存在则直接推送当前 main 分支。
#   - 推送完成后创建一个 Release v0.11.11，并把 apk/GenUI-v0.11.11-debug.apk 作为资产上传。
set -euo pipefail

: "${GH_TOKEN:?请先 export GH_TOKEN=你的GitHub个人访问令牌（ghp_...）}"

TOKEN="$GH_TOKEN"
REPO="${1:-GenUI}"
VIS="${2:-public}"

echo "$TOKEN" | gh auth login --with-token
gh auth setup-git

OWNER=$(gh api user --jq .login)
echo "已登录为: $OWNER"

# 创建仓库并推送；若已存在则退回到手动加远端并推送
gh repo create "$REPO" --"$VIS" --source . --remote origin --push 2>/dev/null \
  || { git remote add origin "https://github.com/$OWNER/$REPO.git" 2>/dev/null || true; git push -u origin main; }

# Release + APK 资产
gh release create "v0.11.11" "apk/GenUI-v0.11.11-debug.apk" \
  --title "GenUI v0.11.11" \
  --notes "修复画布顶部出现 AI 说明文字（决策轮备注回注 + 渲染轮加 HTML 起始检测 + 提示词收紧）；新增 GenUI 生成界面 ↔ 标准 Agent 对话模式切换（输入框胶囊点开对话框切换，多轮对话+工具+流式气泡，共享上下文）；强化 web_search（百度置首、Bing 走 cn、通用兜底抽取、空结果归因）" || true

echo "完成：https://github.com/$OWNER/$REPO"
