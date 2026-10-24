# 현재 진행 상황

- 갱신: 2026-10-24

운영 방식은 [ADR-0003](adr/0003-daily-progress-tracking.md)을 따른다.

## 현재 위치

- Phase: P1 최소 실시간 채팅 서버 (진행 중) — [plan](phases/phase-01-websocket/plan.md)
- 과제: P1-4 같은 방 브로드캐스트와 다른 방 격리
- 단계: 4. 구현·검증 (3. 설계 확정: [ADR-0009](adr/0009-broadcast-per-session-and-per-room-lock.md), [ADR-0010](adr/0010-typed-server-message-model.md) Proposed)
- 브랜치: `p1-4-broadcast`

## 다음 할 일

- [ ] 시나리오 7 (끊긴 뒤 전송 관찰), 8 (EXP-02 열쇠 획득 비용 측정)
- [ ] 5. 정리: review, ADR-0009·0010 Accepted 여부, 개념 노트 보강

## 열린 질문 / 막힌 점

- 전송 실패가 Tomcat/Spring에서 연결 종료로 이어져 `afterConnectionClosed`가 자동 호출되는가? (P1-4 재현 때 확인)
- 느린 소비자: 방마다 락은 느린 F 앞에서 Tomcat 스레드를 묶는다 → P1-8에서 재현, 후보 Decorator (ADR-0009)
- 판별된 잘못된 메시지에 어떻게 대응할까(에러 응답 형식, 연결 유지 여부), 비정상 단절 감지(ping/pong 주기·타임아웃)와 테스트 방법 (P1-5, ADR-0005)
- 전체 락 대기가 실제 서버에서 병목인가? → P1-7 k6 측정에서 확인 (ADR-0008 재검토 조건)

## Phase 과제 목록

| 과제 | 상태 |
|---|---|
| P1-1 WebSocket 연결과 종료 | 완료 |
| P1-2 JSON 메시지 모델 | 완료 |
| P1-3 채팅방 및 세션 관리 | 완료 |
| P1-4 같은 방 브로드캐스트와 다른 방 격리 | 진행 중 |
| P1-5 잘못된 입력과 비정상 종료 처리 | 대기 |
| P1-6 최소 관측 메트릭 | 대기 |
| P1-7 k6 기준 성능 측정 | 대기 |
| P1-8 (선택) 느린 소비자 실험 | 대기 |
