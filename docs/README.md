# Relay 문서 색인

학습 중심 채팅 서버 프로젝트의 계획, 의사결정, 변경 기록을 관리한다.
프로젝트 원칙과 목적은 [`../chat-server-roadmap.md`](../chat-server-roadmap.md)를 따른다.

## 구성

| 경로 | 내용 | 작성 시점 |
|---|---|---|
| [`progress.md`](progress.md) | **현재 위치**와 다음 할 일 (항상 덮어씀) | 매 세션 |
| [`journal/`](journal/) | 일일 로그 `YYYY-MM-DD.md` | 작업한 날 |
| [`roadmap.md`](roadmap.md) | 전체 학습 로드맵 (변경 시 갱신) | 상시 |
| [`phases/`](phases/) | Phase별 `plan.md`(시작 시), `review.md`(완료 시), `experiments/`(측정 결과, 필요 시) | Phase 시작·완료 |
| [`adr/`](adr/README.md) | 기술적 의사결정 기록 (무엇을, 왜 선택했는가) | 결정 시 |
| [`changes/`](changes/README.md) | 계획 변경 기록 (계획과 무엇이 달라졌는가) | 변경 시 |

ADR과 변경 기록의 구분 기준은 [ADR-0001](adr/0001-record-architecture-decisions.md), 진행 관리 방식은 [ADR-0003](adr/0003-daily-progress-tracking.md)에 정의한다.

## Phase 진행 현황

| Phase | 상태 | 계획 | 회고 |
|---|---|---|---|
| P0 프로젝트 기반 구성 | 완료 | [plan](phases/phase-00-foundation/plan.md) | [review](phases/phase-00-foundation/review.md) |
| P1 최소 실시간 채팅 서버 | 대기 | — | — |
| P2 MySQL과 메시지 영속화 | 대기 | — | — |
| P3 트랜잭션과 동시성 | 대기 | — | — |
| P4 이벤트 기반 비동기 처리와 Kafka | 대기 | — | — |
| P5 Redis와 캐싱·동시성 | 대기 | — | — |
| P6 다중 서버와 샤딩 | 대기 | — | — |
| P7 신뢰성 및 장애 복구 | 대기 | — | — |
| P8 성능 테스트 및 최적화 | 대기 | — | — |
| P9 보안과 운영 준비 | 대기 | — | — |
| P10 최종 검증과 회고 | 대기 | — | — |
