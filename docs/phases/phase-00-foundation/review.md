# Phase 00. 프로젝트 기반 구성 — 회고

- 완료일: 2026-10-24

## 구현 내용과 주요 코드의 역할

| 파일 | 역할 |
|---|---|
| `build.gradle` | Spring Boot 4.1.2 플러그인, Java 21 toolchain, `starter-webmvc`·`starter-actuator` 의존성 |
| `gradle/wrapper/*`, `gradlew` | Gradle 9.8.2로 빌드 도구 버전 고정 |
| `src/main/resources/application.yml` | 애플리케이션 이름만 설정 (비밀값 없음) |
| `.gitignore` | Initializr 기본값 + `.DS_Store`, `.env`(`.env.example` 예외) 추가 |
| `RelayApplication`, `RelayApplicationTests` | Initializr 생성 그대로 (진입점, 컨텍스트 로딩 테스트) |

프로젝트는 start.spring.io API로 생성했다 (Spring Boot 4.1.2, Java 21, Groovy DSL, 의존성 `web`, `actuator`).

## 테스트 결과

2026-10-24 로컬(macOS arm64, JDK 21.0.11)에서 실제로 실행한 결과다.

| # | 명령 | 결과 |
|---|---|---|
| 1 | `./gradlew --version` | Gradle 9.8.2 출력 (Wrapper 배포본 다운로드 후) |
| 2 | `./gradlew test` | `BUILD SUCCESSFUL`, `RelayApplicationTests` tests=1, failures=0, errors=0 |
| 3 | `./gradlew bootRun` | `Starting RelayApplication using Java 21.0.11`, `Tomcat started on port 8080`, `Started RelayApplication in 0.91 seconds` |
| 4 | `curl localhost:8080/actuator/health` | `{"groups":["liveness","readiness"],"status":"UP"}` |
| 5 | `git status` (코드 커밋 직전) | `build/`, `.gradle/` 미추적 확인 |

## 성능 측정 결과

해당 없음

## 발생한 문제와 해결

- 특이 문제 없음.
- 참고: Initializr의 `web` 의존성이 Spring Boot 4.x에서는 `spring-boot-starter-webmvc`로 생성된다 (3.x의 `starter-web`과 이름이 다름). ADR-0002에 기록했다.
- 참고: 테스트 실행 시 `Sharing is only supported for boot loader classes...` JVM 경고가 출력된다. 테스트 결과에는 영향이 없다.

## 완료 기준 점검표

| 항목 | 결과 |
|---|---|
| 요구사항 구현 | 완료 (생성, Git, 설정, 실행, 테스트) |
| 자동화 테스트 통과 | 통과 (1/1) |
| 주요 실패 상황 검증 | 해당 없음 (환경 구성 Phase) |
| 데이터 정합성·전달 보장 설명 | 해당 없음 |
| 기술 도입 이유 | ADR-0002, CHG-002 |
| 한계·위험 기록 | 아래 절 |
| 실행·검증 방법 문서화 | `README.md`, 이 문서 |

## 한계와 후속 과제

- 원격 저장소와 CI가 없다. 테스트가 늘어나는 P2 이후 GitHub Actions 도입을 검토한다.
- `.env.example`은 실제 비밀값이 생기는 P2에서 작성한다.
- 테스트 시 출력되는 JVM 경고의 원인은 추적하지 않았다. 테스트 실행 시간 분석이 필요해지면 확인한다.
