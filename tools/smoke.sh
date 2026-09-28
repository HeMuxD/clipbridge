#!/usr/bin/env bash
# ClipBridge 端到端冒烟测试
#
# 用法：
#   ./tests/smoke.sh https://clip.example.com
#
# 覆盖：健康检查 → 配对 → 上传 → 鉴权失败路径

set -uo pipefail

BASE="${1:-}"
if [[ -z "$BASE" ]]; then
  echo "用法：$0 <服务端地址>"
  echo "例如：$0 https://clip.example.com"
  exit 1
fi

BASE="${BASE%/}"
PASS=0
FAIL=0

ok()   { echo "  [通过] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [失败] $1"; FAIL=$((FAIL+1)); }
info() { echo "==> $1"; }

# ---------------- 1. 健康检查 ----------------
info "1. 健康检查 GET /healthz"
HEALTH=$(curl -sS --max-time 10 "${BASE}/healthz" 2>&1)
if echo "$HEALTH" | grep -q '"status":"ok"'; then
  ok "服务端存活"
  echo "     $HEALTH"
else
  bad "无法访问服务端：$HEALTH"
  echo
  echo "服务端不可达，后续测试无法继续。请检查："
  echo "  · 服务是否已启动：systemctl status clipbridge"
  echo "  · Nginx 是否正常：nginx -t && systemctl status nginx"
  echo "  · 域名解析与证书是否有效"
  exit 1
fi

# ---------------- 2. 鉴权失败路径 ----------------
info "2. 无 Token 访问受保护接口，应返回 401"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "${BASE}/api/history")
if [[ "$CODE" == "401" ]]; then
  ok "未授权访问被正确拒绝（401）"
else
  bad "预期 401，实际 $CODE"
fi

info "3. 伪造 Token 访问，应返回 401"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
  -H "Authorization: Bearer forged-token-12345" \
  "${BASE}/api/history")
if [[ "$CODE" == "401" ]]; then
  ok "伪造 Token 被拒绝（401）"
else
  bad "预期 401，实际 $CODE"
fi

# ---------------- 4. 配对 ----------------
info "4. 配对 POST /api/pair"
if [[ -z "${PAIR_CODE:-}" ]]; then
  echo "     未提供 PAIR_CODE 环境变量，跳过配对相关测试"
  echo "     如需测试，请用：PAIR_CODE=123456 $0 $BASE"
else
  DEVICE_ID="smoke-$(date +%s)"
  RESP=$(curl -sS --max-time 10 -X POST "${BASE}/api/pair" \
    -H "Content-Type: application/json" \
    -d "{\"pairCode\":\"${PAIR_CODE}\",\"deviceId\":\"${DEVICE_ID}\",\"deviceName\":\"冒烟测试\",\"platform\":\"windows\"}" 2>&1)

  if echo "$RESP" | grep -q '"token"'; then
    ok "配对成功"
    TOKEN=$(echo "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  else
    bad "配对失败：$RESP"
    TOKEN=""
  fi

  # 配对码是一次性的，第二次使用必须失败
  info "5. 重复使用配对码，应被拒绝（配对码为一次性）"
  RESP2=$(curl -sS --max-time 10 -X POST "${BASE}/api/pair" \
    -H "Content-Type: application/json" \
    -d "{\"pairCode\":\"${PAIR_CODE}\",\"deviceId\":\"${DEVICE_ID}-dup\",\"deviceName\":\"重复\"}" 2>&1)
  if echo "$RESP2" | grep -q '"token"'; then
    bad "配对码被重复使用 —— 存在安全隐患"
  else
    ok "配对码已作废，无法重复使用"
  fi

  # ---------------- 6. 带 Token 访问 ----------------
  if [[ -n "$TOKEN" ]]; then
    info "6. 带有效 Token 查询历史记录"
    CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
      -H "Authorization: Bearer ${TOKEN}" "${BASE}/api/history")
    if [[ "$CODE" == "200" ]]; then
      ok "鉴权通过（200）"
    else
      bad "预期 200，实际 $CODE"
    fi

    # ---------------- 7. 上传 ----------------
    info "7. 上传图片 POST /api/upload"
    # 造一张 1x1 的合法 PNG
    TMP_PNG=$(mktemp /tmp/clipbridge-smoke-XXXXXX.png)
    printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\x18\xdd\x8d\xb0\x00\x00\x00\x00IEND\xaeB`\x82' > "$TMP_PNG"

    RESP=$(curl -sS --max-time 20 -X POST "${BASE}/api/upload" \
      -H "Authorization: Bearer ${TOKEN}" \
      -F "file=@${TMP_PNG};type=image/png" 2>&1)
    rm -f "$TMP_PNG"

    if echo "$RESP" | grep -q '"fileId"'; then
      ok "图片上传成功"
      echo "     $RESP"
      FILE_URL=$(echo "$RESP" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')

      # ---------------- 8. 下载 ----------------
      if [[ -n "$FILE_URL" ]]; then
        info "8. 下载刚上传的图片"
        CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$FILE_URL")
        if [[ "$CODE" == "200" ]]; then
          ok "图片可下载（200）"
        else
          bad "预期 200，实际 $CODE —— 检查 Nginx 的 /f/ 静态映射"
        fi
      fi
    else
      bad "上传失败：$RESP"
    fi

    # ---------------- 9. 非法文件类型 ----------------
    info "9. 上传非图片文件，应被拒绝"
    TMP_TXT=$(mktemp /tmp/clipbridge-smoke-XXXXXX.txt)
    echo "这不是图片" > "$TMP_TXT"
    RESP=$(curl -sS --max-time 15 -X POST "${BASE}/api/upload" \
      -H "Authorization: Bearer ${TOKEN}" \
      -F "file=@${TMP_TXT};type=image/png" 2>&1)
    rm -f "$TMP_TXT"

    if echo "$RESP" | grep -q '"fileId"'; then
      bad "伪装成图片的文本被接受了 —— 服务端魔数校验失效"
    else
      ok "非图片文件被正确拒绝"
    fi
  fi
fi

# ---------------- 汇总 ----------------
echo
echo "================================"
echo "  通过 $PASS 项 / 失败 $FAIL 项"
echo "================================"
[[ $FAIL -eq 0 ]] && exit 0 || exit 1
