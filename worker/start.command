#!/bin/zsh
set -eu
LAB_ROOT="${0:A:h}"
cd "$LAB_ROOT"
if command -v node >/dev/null 2>&1; then
  LAB_NODE="$(command -v node)"
else
  LAB_NODE=""
  for candidate in "$HOME"/.nvm/versions/node/v24*/bin/node(N) /opt/homebrew/bin/node /usr/local/bin/node; do
    if [[ -x "$candidate" ]]; then LAB_NODE="$candidate"; break; fi
  done
fi
if [[ -z "$LAB_NODE" ]] || ! "$LAB_NODE" -e 'if(Number(process.versions.node.split(".")[0])<24)process.exit(1)' ; then
  print 'Node.js 24 이상이 필요합니다. 기존 설치를 변경하지 않았습니다.'
  read -r '?Enter를 눌러 닫기: '
  exit 1
fi
print '표현친구 AI Agent 실험실 — 모의 전용, 유료 호출 없음'
print '브라우저 주소: http://127.0.0.1:5197'
print '이 창을 유지해 주세요. 종료하려면 Control+C를 누르세요.'
exec "$LAB_NODE" src/server.mjs
