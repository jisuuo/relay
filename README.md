# Relay

학습 중심의 Java/Spring 기반 채팅 서버 프로젝트.
목적과 진행 원칙은 [`chat-server-roadmap.md`](chat-server-roadmap.md), 문서 색인은 [`docs/README.md`](docs/README.md)를 참고한다.

## 요구 환경

- JDK 21 (Gradle toolchain이 21을 찾아 사용한다)
- 별도 Gradle 설치 불필요 (Gradle Wrapper 사용)

## 실행

```bash
./gradlew bootRun
curl localhost:8080/actuator/health   # {"status":"UP"}
```

## 테스트

```bash
./gradlew test
```

## 구조

```
src/main/java/com/relay/   애플리케이션 코드
src/test/java/com/relay/   테스트
docs/                      로드맵, Phase 기록, ADR, 계획 변경 기록
```
