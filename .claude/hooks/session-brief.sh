#!/usr/bin/env bash
# SessionStart: docs/progress.md와 최신 journal로 오늘의 시작 지점을 보여주고 컨텍스트에 주입한다. (ADR-0003)
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

progress=docs/progress.md
[ -f "$progress" ] || exit 0

journal=$(ls docs/journal/*.md 2>/dev/null | sort | tail -1 || true)

# "## 현재 위치"와 "## 다음 할 일" 섹션만 사용자에게 표시
brief=$(awk '
  /^## / { show = ($0 ~ /^## (현재 위치|다음 할 일)/) }
  show
' "$progress")

context="[세션 시작 브리핑 자료 — ADR-0003]
첫 응답에서 사용자의 메시지 내용과 관계없이 먼저 ① 현재 위치 ② 오늘 할 일 1개 ③ 이어서 고민할 질문을 짧게 브리핑하라. 필요하면 해당 Phase plan.md의 체크리스트를 읽어 다음 단계를 구체화하라.

=== $progress ===
$(cat "$progress")"

if [ -n "$journal" ]; then
  context="$context

=== $journal ===
$(cat "$journal")"
fi

jq -n --arg msg "📍 Relay 오늘의 시작 지점
$brief" --arg ctx "$context" '{
  systemMessage: $msg,
  hookSpecificOutput: { hookEventName: "SessionStart", additionalContext: $ctx }
}'
