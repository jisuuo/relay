# Phase 00. 프로젝트 기반 구성 — 계획

- 상태: 진행 중
- 시작일: 2026-10-24
- 선행 Phase: 없음

## 학습 목표

- 빌드 도구와 JDK 버전을 고정해 어느 환경에서도 같은 결과가 나오는 빌드를 만든다.
- 이후 모든 Phase의 기반이 될 실행·테스트·문서·Git 관리 방식을 정한다.

## 문제 정의

시스템 Gradle(9.6.1)과 여러 JDK(8~25)가 혼재한다. 버전이 고정되지 않으면 "내 환경에서만 되는" 빌드가 되고, 이후 성능 측정 결과도 비교할 수 없게 된다.

## 범위 / 비범위

**범위**

- Spring Boot 프로젝트 생성 (Java 21, Spring Boot 4.1.2, Gradle Wrapper)
- Git 저장소 초기화, `.gitignore`(빌드 산출물, IDE, OS, `.env`)
- 설정 파일 `application.yml` 구성, 비밀정보 관리 원칙 수립
- 자동화 테스트 실행, 애플리케이션 실행 및 health 확인
- README에 실행 방법 기록

**비범위**

- WebSocket, MySQL, Kafka, Redis, Docker Compose (각 Phase에서 도입)
- CI/CD (P2 이후 검토)

## 비밀정보 관리 원칙

- 비밀값(DB 비밀번호 등)은 저장소에 커밋하지 않고 환경변수로 주입한다.
- 로컬 편의를 위한 `.env` 파일은 `.gitignore`로 제외하고, 필요한 키 목록은 `.env.example`로 공유한다 (실제 비밀값이 생기는 P2에서 작성).
- `application.yml`에는 `${ENV_VAR}` 형태의 참조만 둔다.

## 원인 가설

해당 없음 (환경 구성 Phase)

## 검토할 해결 방법

버전 선택은 [ADR-0002](../../adr/0002-java-and-spring-boot-version.md)에서 결정했다.

## 테스트 시나리오

| # | 검증 항목 | 명령 | 기대 결과 |
|---|---|---|---|
| 1 | Wrapper 버전 고정 | `./gradlew --version` | Wrapper 지정 버전 출력 |
| 2 | 자동화 테스트 | `./gradlew test` | `contextLoads` 통과 |
| 3 | 애플리케이션 실행 | `./gradlew bootRun` | 기동 로그에 Started 출력 |
| 4 | 헬스 체크 | `curl localhost:8080/actuator/health` | `{"status":"UP"}` |
| 5 | Git 관리 | `git status` | 빌드 산출물·IDE 파일 미추적 |

## 완료 기준

- 위 테스트 시나리오 1~5를 실제로 실행해 통과
- README에 실행·테스트 방법 기록
- 문서와 코드가 분리된 커밋으로 저장소에 기록됨

## 관련 ADR / 변경 기록

- [ADR-0001](../../adr/0001-record-architecture-decisions.md) 문서화 방식과 ADR 작성 기준
- [ADR-0002](../../adr/0002-java-and-spring-boot-version.md) Java·Spring Boot·빌드 스크립트 선택
- [CHG-002](../../changes/002-early-minimal-observability.md) 최소 관측 수단 조기 도입 (Actuator 포함 근거)
