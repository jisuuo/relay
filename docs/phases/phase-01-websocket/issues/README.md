# Phase 01 문제 기록

과제 중 실제로 재현한 문제를 "문제 발생 → 원인 분석 → 해결 방안과 해결" 순서로 기록한다. 실행하지 않은 결과는 적지 않는다.

| 번호 | 과제 | 문제 |
|---|---|---|
| [ISSUE-01](01-session-loss-on-concurrent-register.md) | P1-1 | 동시 접속 시 세션이 예외 없이 사라짐 (`HashSet`) |
| [ISSUE-02](02-jackson-creates-message-without-required-fields.md) | P1-2 | 필수 필드가 비어 있어도 메시지 객체가 만들어짐 (Jackson) |
| [ISSUE-03](03-join-into-deleted-room.md) | P1-3 | 빈 방 삭제와 `JOIN`이 겹치면 삭제된 방에 들어감 (예외 없음) |
