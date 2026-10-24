# ADR-0002. Java·Spring Boot·빌드 스크립트 선택

- 상태: Accepted
- 날짜: 2026-10-24
- 관련 Phase: P0

## 배경

로컬에는 JDK 8, 11, 17, 21, 25가 설치되어 있고 기본 JDK는 21.0.11이다.
start.spring.io(2026-10-24 조회) 기준 Spring Boot GA 버전은 4.1.2(기본값), 4.0.9이며 4.2.0은 RC 단계다.
이후 Kafka, Redis(Redisson), MySQL 드라이버, Testcontainers 등을 순차 도입하므로 의존성 호환성이 중요하다.

## 검토한 대안

### Java

| 대안 | 장점 | 단점 |
|---|---|---|
| 17 LTS | 호환성 가장 넓음 | Virtual Threads 없음 → P8 실험 불가 |
| **21 LTS** | Virtual Threads, 기본 JDK와 일치, 생태계 지원 성숙 | 25 대비 최신 기능 일부 부재 |
| 25 LTS | 최신 LTS | 서드파티 의존성 호환성을 도입 시마다 확인해야 함 |

### Spring Boot

| 대안 | 판단 |
|---|---|
| **4.1.2** | 최신 GA, Initializr 기본값 |
| 4.0.9 | 이전 마이너 라인, 선택 이유 없음 |
| 4.2.0-RC1 | RC 단계, 안정성 우선 원칙에 맞지 않음 |

### 빌드 스크립트

| 대안 | 장점 | 단점 |
|---|---|---|
| **Groovy DSL** | Initializr 기본값, 공식 문서·예제가 가장 많음 | 타입 안전성 없음 |
| Kotlin DSL | IDE 자동완성·타입 안전성 | 학습 목표와 무관한 추가 문법 |

## 결정

- Java **21** (Gradle toolchain으로 고정)
- Spring Boot **4.1.2**
- Gradle **Groovy DSL**, Gradle Wrapper(Initializr 생성 버전 9.8.2)로 빌드 도구 버전 고정
- 패키지: `com.relay`
- 설정 파일: `application.yml`

## 근거

- 안정성과 Virtual Threads 실험(P8)을 모두 만족하는 최소 선택이 Java 21이다.
- Wrapper와 toolchain으로 고정하면 시스템 Gradle(9.6.1)이나 기본 JDK가 바뀌어도 빌드가 재현된다.
- DSL 선택은 학습 목표와 무관하므로 기본값을 따른다(사용자가 별도 지정하지 않음).

## 결과와 트레이드오프

- Spring Boot 4.x는 3.x와 스타터 이름이 일부 다르다(예: `spring-boot-starter-webmvc`). 3.x 기준 자료를 참고할 때 주의한다.
- Java 25 전용 기능은 사용하지 않는다.

## 재검토 조건

- 도입할 의존성이 Spring Boot 4.1 또는 Java 21과 호환되지 않을 때
- Spring Boot 4.1 라인의 지원 종료가 다가올 때
