# 전체 학습 로드맵

- 최종 갱신: 2026-10-24
- 반영된 변경: [CHG-001](changes/001-move-baseline-perf-to-phase1.md), [CHG-002](changes/002-early-minimal-observability.md)

이 로드맵은 고정된 설계가 아니다. 변경 시 `changes/`에 기록하고 이 문서를 갱신한다.

## 진행 원칙

각 Phase는 **문제 → 재현 → 단순한 해결 → (필요할 때만) 기술 도입 → 전후 측정** 순서로 진행한다.
실패하는 테스트로 문제를 먼저 재현한 뒤 해결한다. Phase 완료 후 승인 없이 다음 Phase로 진행하지 않는다.

## 의존 관계

```
P0 기반 ─▶ P1 WebSocket ─▶ P2 MySQL 영속화 ─▶ P3 동시성/멱등성
                │                                   │
                └─ 기준 성능(k6) 측정 ───────────────┤
                                                    ▼
          P4 이벤트/Kafka ◀──────────────────────────┘
                │
                ▼
          P5 Redis ─▶ P6 다중 서버/샤딩 ─▶ P7 신뢰성/장애 복구
                                                │
                       P8 성능 최적화 ◀──────────┘
                                │
                       P9 보안/운영 ─▶ P10 회고
```

## Phase 요약

| Phase | 핵심 질문 | 의존성 | 주요 학습 후보 기술 |
|---|---|---|---|
| P0 기반 | 재현 가능한 빌드·테스트 환경이 있는가? | — | Gradle Wrapper, Spring Boot, Git |
| P1 WebSocket | 연결·방·브로드캐스트·격리가 정확한가? 기준 성능은? | P0 | WebSocket, Actuator/Micrometer, k6 |
| P2 영속화 | 메시지가 유실되지 않는가? 조회가 인덱스를 타는가? | P1 | MySQL, Spring Transaction, Testcontainers, Docker Compose, EXPLAIN |
| P3 동시성 | 중복 저장이 없는가? 방 내 순서는 무엇이 결정하는가? | P2 | DB 잠금, 격리 수준, 멱등성 키 |
| P4 비동기 | 커밋과 발행 사이 실패 시 무슨 일이 생기는가? | P3 | afterCommit, Kafka, partition key, 수동 ACK, 재시도, Outbox |
| P5 Redis | 캐시가 실제로 빠른가? 정합성 비용은? | P2~P4 | Redis, INCR+EXPIRE, Redisson, Delayed Queue, 지연 flush, multiGet |
| P6 분산 | 다른 서버의 사용자에게 메시지가 전달되는가? | P4, P5 | AbstractRoutingDataSource, AOP, ThreadLocal, Snowflake ID, ChainedTransactionManager(비판적 검토) |
| P7 신뢰성 | 구성 요소 장애 시 유실·중복 범위를 설명할 수 있는가? | P4~P6 | 재시도·백오프·타임아웃, Graceful shutdown, Toxiproxy |
| P8 성능 | 병목은 어디이며 개선을 수치로 보일 수 있는가? | P1부터 누적 | k6, p95/p99, 커넥션 풀, GC, Virtual Threads |
| P9 보안/운영 | 미인증 접근이 차단되는가? 배포가 자동화되었는가? | P1 이후 | 인증·인가, WebSocket 보안, Rate limit, Docker, GitHub Actions |
| P10 회고 | 제거해도 되는 기술은 무엇인가? | 전체 | — |

### 비판적으로 다룰 항목

- `ChainedTransactionManager`는 Spring Data에서 deprecated 되었다. 원자성을 보장하지 않는 이유를 학습하고 Outbox·보상 트랜잭션과 비교한다.
- Redisson Delayed Queue는 도입 시점에 최신 API 변경 여부를 공식 문서로 확인한다.
- 로컬 단일 머신에서는 샤딩의 성능 효과를 측정하기 어렵다. 라우팅 정확성과 샤드 키 설계 검증에 집중한다.

## 기술 도입 판단 기준

새 기술을 도입하기 전 아래 항목을 모두 채운다. 하나라도 비어 있으면 도입하지 않는다.

| 질문 | 설명 |
|---|---|
| 재현한 문제는? | 실제로 재현한 증상 |
| 재현 방법은? | 테스트 또는 스크립트 |
| 더 단순한 대안은? | 기존 도구로 해결 가능한지 |
| 이 기술만 해결하는 것은? | 대안 대비 고유한 이점 |
| 비용은? | 복잡도, 운영 부담, 성능 비용 |
| 전후 비교 지표는? | 측정 방법과 지표 |

## 단계별 완료 기준

| Phase | 완료 기준 |
|---|---|
| P0 | `./gradlew test`·`bootRun` 성공, health 확인, Git 커밋, 실행 방법 문서화 |
| P1 | 다중 클라이언트 송수신·방 격리·비정상 입력/종료 테스트 통과, k6 기준 성능 기록 |
| P2 | Testcontainers 기반 저장·조회 통합 테스트, 조회 쿼리 EXPLAIN 기록, 롤백 시나리오 검증 |
| P3 | 경쟁 조건을 실패하는 테스트로 재현 후 해결, 해결책 2개 이상 비교 |
| P4 | 커밋·발행 사이 장애 주입으로 유실·중복 확인, 전달 보장 수준 명시 |
| P5 | 캐시 도입 전후 p95/p99 비교, 캐시 불일치 시나리오 재현과 대응 |
| P6 | 서버 2대 이상 교차 전달 검증, 샤드 라우팅 테스트, ID 순서·충돌 검증 |
| P7 | 장애 시나리오별 기대 vs 실제 결과 표, 복구 절차 검증 |
| P8 | 재현 가능한 측정 스크립트와 개선 전후 수치 |
| P9 | 미인증 접근 차단 테스트, CI 동작, 컨테이너 배포 검증 |
| P10 | 기술별 효과·비용·제거 가능 여부 표, 최종 아키텍처 문서 |

## 추가 실험 후보

- 느린 소비자가 같은 방의 지연에 미치는 영향 (send buffer, `ConcurrentWebSocketSessionDecorator`)
- Virtual Threads 전후 처리량·p99·스레드 수 비교
- 동시 전송 시 DB 저장 순서·Kafka 파티션 순서·클라이언트 수신 순서 일치 여부
- 트랜잭션 내부 외부 I/O로 인한 커넥션 풀 고갈 재현
- 읽음 처리·안 읽은 수 쓰기 폭증과 Redis 지연 flush
- Kafka·Redis·MySQL 개별 중단 시 기대 vs 실제 결과
- 메시지 보관 정책(파티셔닝·아카이빙)과 조회 성능
- 동시 접속 규모 가정 기반 용량 산정과 측정치 비교
