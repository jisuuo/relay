# ADR 목록

작성 기준과 템플릿은 [ADR-0001](0001-record-architecture-decisions.md)을 따른다.

| 번호 | 날짜 | 제목 | 상태 | 관련 Phase |
|---|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | 2026-10-24 | 문서화 방식과 ADR 작성 기준 | Accepted | 전체 |
| [0002](0002-java-and-spring-boot-version.md) | 2026-10-24 | Java·Spring Boot·빌드 스크립트 선택 | Accepted | P0 |
| [0003](0003-daily-progress-tracking.md) | 2026-10-24 | 일일 진행 관리 방식 | Accepted (② ADR-0012로 대체) | 전체 |
| [0004](0004-raw-websocket-over-stomp.md) | 2026-10-24 | Phase 1 메시징 방식: Raw WebSocket으로 시작 | Accepted | P1 |
| [0005](0005-websocket-ping-pong-heartbeat.md) | 2026-10-24 | 비정상 단절 감지: WebSocket ping/pong heartbeat | Accepted | P1 |
| [0006](0006-multi-room-per-session.md) | 2026-10-24 | 세션과 방의 관계: 한 세션이 여러 방에 참여 | Accepted | P1 |
| [0007](0007-typed-client-message-model.md) | 2026-10-24 | 클라이언트 메시지 모델: 타입별 record와 생성자 검증 | Accepted | P1 |
| [0008](0008-room-registry-global-lock.md) | 2026-10-24 | 방 관리의 동시성: 전체 락 하나로 시작 | Accepted | P1 |
| [0009](0009-broadcast-per-session-and-per-room-lock.md) | 2026-10-24 | 브로드캐스트의 동시성: 세션마다 락 + 방마다 락 | Accepted | P1 |
| [0010](0010-typed-server-message-model.md) | 2026-10-24 | 서버 메시지 모델: 타입별 record와 연결 직후 sessionId 알림 | Accepted | P1 |
| [0011](0011-error-response-format.md) | 2026-10-25 | 잘못된 입력 대응: 오류 응답 형식과 연결 유지 | Accepted | P1 |
| [0012](0012-nine-step-task-process.md) | 2026-10-26 | 과제 진행 체크리스트를 9단계로 교체 | Accepted | P1-6 이후 |
