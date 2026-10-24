# CHG-002. 최소 관측 수단을 P0~P1부터 도입

- 날짜: 2026-10-24
- 영향 Phase: P0, P1

## 기존 계획

모니터링과 관측 도구는 필요한 시점에 후보로 제안한다 (원 계획 3절, 6절).

## 변경 내용

- P0에서 Spring Boot Actuator를 포함한다 (health 엔드포인트로 실행 검증).
- P1에서 Micrometer 기반 커스텀 메트릭(활성 세션 수, 메시지 처리량)을 추가한다.
- Prometheus, Grafana, Datadog 등 외부 도구는 기존 원칙대로 필요해지는 시점(P4~P5 예상)에 검토한다.

## 변경 이유

CHG-001로 P1부터 부하 테스트를 수행하는데, 서버 측 지표 없이 클라이언트 측 수치만으로는 결과의 원인을 설명할 수 없다.
Actuator와 Micrometer는 Spring Boot 기본 구성 요소라 도입 비용이 거의 없다.

## 영향

- P0 의존성에 `spring-boot-starter-actuator`가 추가된다.
- 외부 모니터링 도구 도입 원칙은 변경하지 않는다.

## 관련 ADR

없음
