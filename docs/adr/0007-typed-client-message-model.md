# ADR-0007. 클라이언트 메시지 모델: 타입별 record와 생성자 검증

- 상태: Accepted
- 날짜: 2026-10-24
- 관련 Phase: P1 (P1-2, P1-5)

## 배경

클라이언트 → 서버 메시지는 세 종류이고, 종류마다 필요한 필드가 다르다 ([ADR-0006](0006-multi-room-per-session.md)).

```json
{"type":"JOIN",  "roomId":"A"}
{"type":"LEAVE", "roomId":"A"}
{"type":"SEND",  "roomId":"A", "content":"hi"}
```

Raw WebSocket([ADR-0004](0004-raw-websocket-over-stomp.md))에는 controller·`@RequestBody`·`@Valid` 같은 자동 바인딩과 검증이 없다. 메시지는 `handleTextMessage`에 문자열로 들어오므로, 문자열을 Java 객체로 바꾸고 잘못된 입력을 판별하는 일을 직접 설계해야 한다.

결정할 것은 세 가지다: ① 메시지를 표현하는 방식 ② 필수 필드 검사 위치 ③ 정의에 없는 필드의 처리.

## 검토한 대안

### ① 표현 방식

| 대안 | 장점 | 단점 |
|---|---|---|
| A. 단일 클래스 (`type`·`roomId`·`content`) | 가장 단순 | `JOIN`에 붙은 `content`도 통과. "SEND일 때만 content 필수" 같은 조건부 검증을 if문으로 작성. 새 타입의 처리 분기를 빠뜨려도 컴파일러가 알려 주지 않음 |
| **B. 타입별 record + sealed interface** | 타입마다 자기 필드만 가짐. `switch` 패턴 매칭으로 분기 누락이 컴파일 에러. JSON 형식은 그대로 (Jackson `@JsonTypeInfo`가 `type`으로 클래스 선택) | 어노테이션 몇 줄 |
| C. 봉투 `{type, payload:{...}}` | 공통 메타데이터를 붙이기 쉬움 | 와이어 형식 변경, 파싱 2단계. 현재 필요 없음 |

### ② 필수 필드 검사 위치

| 대안 | 장점 | 단점 |
|---|---|---|
| **가. record 생성자 (compact constructor)** | 어떤 경로로 만들어도 검사를 피할 수 없음 → 객체가 존재하면 유효함 | 테스트에서 잘못된 객체를 일부러 만들 수 없음 (의도된 제약) |
| 나. 파서 안에서 생성 후 확인 | 검사 로직이 한 곳에 모임 | 파서를 거치지 않는 경로(테스트, P2 DB 복원, P6 Kafka 역직렬화, 내부 코드)는 검사를 우회함. 받는 쪽이 다시 확인해야 안심할 수 있음 |

### ③ 정의에 없는 필드 (예: `"sender":"bob"`, `JOIN`의 `content`)

| 대안 | 장점 | 단점 |
|---|---|---|
| 거절 | 잘못된 클라이언트를 바로 발견 | 클라이언트가 선택 필드를 추가하면 구버전 서버가 모든 메시지를 거절 → 서버 선배포 제약 |
| **무시** | 버전이 어긋나도 동작 (Tolerant Reader, protobuf·공개 REST API의 일반 관행) | 잘못 보낸 선택 필드가 조용히 사라짐 |

## 결정

- ① B. `ClientMessage` sealed interface 아래 `JoinMessage(roomId)`, `LeaveMessage(roomId)`, `SendMessage(roomId, content)` record를 두고, `type` 값으로 역직렬화할 클래스를 고른다.
- ② 가. 각 record의 compact constructor에서 필드를 검사한다. `roomId`·`content` 모두 `null`, 빈 문자열, 공백만 있는 문자열을 거절한다. 내용이 있는 문자열(`" 안녕 "`)은 자르지 않고 보존한다.
- ③ 무시. 처리에 필요 없는 필드는 버린다.
- 핸들러(입출력)와 분리된 파서가 `String` → `ClientMessage`를 담당하고, 메시지만 보고 알 수 있는 실패(JSON 문법 오류, 모르는 `type`, 필수 필드 누락)를 판별한다. 실패에 대한 대응(에러 응답, 연결 유지 여부)은 P1-5에서 정한다.

## 근거

- 타입마다 필드가 다르다는 사실을 if문·주석이 아니라 타입 시스템에 담으면, 잘못된 조합을 만들 수 없고 분기 누락을 컴파일러가 잡는다.
- 검사를 객체 바깥의 한 경로에 두면, 설계 시점에 존재하지 않던 경로(P6 Kafka 등)를 막을 수 없다. 생성자에 두면 모든 경로를 막는다.
- 엄격성은 서버가 처리하는 데 필요한 규칙(알려진 `type`, 필수 필드, 빈 값 금지)에 적용한다. 모르는 필드는 처리에 영향이 없으므로 거절할 이유가 약하다.
- 무시 정책이 안전한 이유: record에 `sender` 같은 필드가 아예 없으므로, 클라이언트가 보낸 값이 서버 로직에 닿지 않는다 (mass assignment 위험 없음). 보낸 사람은 항상 세션에서 꺼낸다.
- 새 의존성이 없다. Jackson 3.1.7은 이미 `spring-boot-starter-jackson`으로 포함되어 있다. Bean Validation(`@NotBlank`)은 새 의존성과 수동 호출이 필요하며, 생성자 검사로 충분하다.

## 결과와 트레이드오프

- 서버 기준으로 메시지 계약이 정해지므로, 깨지는 변경(필드 이름·의미 변경)은 새 `type`, `version` 필드, WebSocket subprotocol 중 하나로 다뤄야 한다. 아직 정하지 않는다.
- P1-2 구현 단계에서 테스트로 확인한 것 (2026-10-24, [plan](../phases/phase-01-websocket/plan.md) P1-2):
  - 생성자 검사가 없으면 필수 필드가 누락·`null`·빈 값이어도 Jackson은 예외 없이 record를 만든다.
  - 생성자에서 던진 `IllegalArgumentException`은 Jackson `ValueInstantiationException`으로 감싸져 나온다.
  - Jackson 3.1.7의 모르는 필드 기본 동작은 무시다. 그래도 정책을 코드에 드러내려고 `FAIL_ON_UNKNOWN_PROPERTIES`를 명시적으로 끈다.

## 재검토 조건

- 서버와 독립적으로 배포되는 외부 클라이언트가 생길 때 → 버전 관리 방식 결정
- 서버 → 클라이언트 메시지 모델을 설계할 때 같은 방식을 쓸지 (P1-4)
