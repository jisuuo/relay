# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 성격

학습 중심의 Java/Spring 채팅 서버다. 기능 완성이 아니라 학습자가 문제 정의·흐름 분석·질문 생성·설계·결과 해석을 직접 하고, 구현은 AI에 위임해 검증하는 엔지니어링 판단 훈련이 목적이다. 전체 원칙은 `chat-server-roadmap.md`(마스터 프롬프트)에 있으며 모든 작업의 기준이다.

핵심 작업 규칙 (`chat-server-roadmap.md` 4~6절):

- 사용자가 문제나 과제를 제시하면 정답·완성 설계를 바로 주지 말고 사용자가 먼저 정의·흐름·질문을 작성하도록 유도한다. 완성된 흐름도나 질문 목록을 먼저 제공하지 않고, 사용자가 쓴 것을 코드와 비교해 교정·평가한다. 질문은 한 번에 하나의 핵심 문제에 집중한다.
- 구현은 6단계에서 사용자가 승인한 설계와 구현 지시대로 Claude가 맡는다. 승인되지 않은 설계 변경이 필요하면 먼저 보고한다.
- 사용자가 개념·흐름을 모르면 이해 모드로 먼저 설명하고, 이미 이해한 부분은 반복 질문하지 않는다 (마스터 프롬프트 5절 모드 전환 기준).
- 사용자의 접근이 틀렸으면 동의하지 말고 반례와 근거로 설명한다.
- Phase 완료 후 다음 Phase로 자동 진행하지 않는다. 사용자 승인을 기다린다.
- 새 기술은 재현된 문제가 있고 더 단순한 대안을 검토한 뒤에만 도입한다 (판단 기준표: `docs/roadmap.md`).
- 실제로 실행하지 않은 테스트·측정 결과를 문서에 기록하지 않는다.

## 일일 진행 관리 (`docs/adr/0003-*.md`)

매일 이어서 진행하는 프로젝트다.

- **세션 시작 시:** SessionStart hook(`.claude/hooks/session-brief.sh`)이 `docs/progress.md`와 최신 journal을 컨텍스트에 주입한다. 첫 응답에서는 사용자 메시지 내용과 관계없이 이를 바탕으로(hook 주입이 없으면 직접 읽고) ① 현재 위치(Phase·과제·단계 중 어디) ② 오늘 할 일 1개 ③ 이어서 고민할 질문을 짧게 브리핑한다.
- **작업 중:** 해당 Phase `plan.md`의 과제별 체크리스트를 갱신한다. P1-6부터는 9단계(문제 정의 → 흐름 분석 → 질문 생성 → 요구사항·위험 → 설계 결정 → AI 구현·테스트 → 결과 해석·코드 검토 → 장애·운영 실험 → 회고, `docs/adr/0012-*.md`)다. 6단계를 뺀 모든 단계는 사용자가 자신의 답을 적은 뒤에만 체크하고, Claude는 답을 먼저 나열하지 않고 질문을 하나씩 던진다. 6단계는 구현·테스트를 실제로 실행하고 보고한 뒤 체크한다. (P1-1~P1-5는 기존 5단계 기록 유지)
- **세션 종료 시 ("오늘 마무리" 등):** `docs/journal/YYYY-MM-DD.md`(5줄 내외: 한 일 / 배운 것 / 막힌 것·질문 / 다음) 작성, `docs/progress.md` 덮어쓰기, `docs:` 커밋 후 push. "배운 것"은 임의로 채우지 말고 사용자에게 묻거나 사용자 표현을 기록한다.

## 명령어

```bash
./gradlew test                                              # 전체 테스트
./gradlew test --tests 'com.relay.RelayApplicationTests'    # 단일 테스트 클래스
./gradlew test --tests 'com.relay.SomeTest.methodName'      # 단일 테스트 메서드
./gradlew bootRun                                           # 실행 (포트 8080)
curl localhost:8080/actuator/health                         # 기동 확인
./gradlew build                                             # 빌드 (테스트 포함)
```

시스템 Gradle 대신 항상 `./gradlew`를 사용한다. 린터·포매터는 아직 없다.

## 기술 스택과 주의점

- Java 21 (Gradle toolchain으로 고정), Spring Boot 4.1.2, Gradle Wrapper 9.8.2, Groovy DSL. 선택 근거는 `docs/adr/0002-*.md`.
- Spring Boot 4.x는 3.x와 스타터 이름이 다르다 (예: `spring-boot-starter-webmvc`, 테스트용 `spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test`). 3.x 기준 자료의 의존성 이름을 그대로 쓰지 않는다.
- 설정은 `src/main/resources/application.yml`. 비밀값은 커밋하지 않고 환경변수(`${ENV_VAR}`)로 주입하며 `.env`는 gitignore 대상이다.
- 현재 코드는 Phase 0 상태(Initializr 기본 골격 + Actuator)다. WebSocket·MySQL·Kafka·Redis는 각 Phase에서 순차 도입한다 (`docs/roadmap.md`).

## 문서화 규칙 (`docs/adr/0001-*.md`)

- Phase 시작 시 `docs/phases/phase-XX-<slug>/plan.md`를 작성하고(과제 목록과 과제별 9단계 체크리스트 포함, 템플릿은 ADR-0012) 승인 후 구현한다. 완료 시 같은 폴더에 `review.md`를 작성하고 `plan.md` 상태와 `docs/README.md` 진행 현황표를 갱신한다. 측정 결과는 필요할 때만 `experiments/`에 둔다.
- 기술·설계 선택(대안 2개 이상 비교, 되돌리기 어려운 결정, 학습 후보 기술의 미도입·대체) → `docs/adr/NNNN-<slug>.md` (번호 4자리, `Proposed` → `Accepted`, 번복 시 새 ADR로 Superseded).
- 계획 대비 변경(Phase 순서·범위·완료 기준) → `docs/changes/NNN-<slug>.md` + 같은 커밋에서 `docs/roadmap.md` 갱신.
- 새 ADR·변경 기록은 각 폴더의 `README.md` 색인에도 추가한다. 템플릿은 ADR-0001에 있다.
- 문서 커밋과 코드 커밋은 분리한다 (`docs: ...`, `docs(adr): ...`, `docs(changes): ...`, `chore: ...` 등).

## 저장소

- 원격: https://github.com/jisuuo/relay (public). 기본 브랜치 `main`.
- 브랜치: 과제(예: P1-4)마다 `main`에서 `pX-Y-<slug>` 브랜치를 만들고, 그 과제의 작업은 모두 그 브랜치에 작업 단위별 커밋으로 쌓는다(코드·문서 커밋 분리 규칙 유지). 세션 종료 시 그 브랜치를 push한다. `main` 병합은 사용자 확인 후 로컬에서 병합(PR 없음)하고 `main`을 push한다.
