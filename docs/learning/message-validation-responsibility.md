# 메시지 검사의 책임: 무엇을 메시지에 담고, 어디서 검사하는가

- 날짜: 2026-10-24
- 관련: [Phase 1 plan](../phases/phase-01-websocket/plan.md) P1-2, [ADR-0006](../adr/0006-multi-room-per-session.md), [ADR-0007](../adr/0007-typed-client-message-model.md)
- 상태: `ClientMessageParserTest`로 재현·해결 확인 (2026-10-24)

## 핵심 질문

클라이언트가 보낸 JSON 문자열을 서버가 믿고 처리하려면, ① 무엇을 메시지에 담아야 하고 ② 잘못된 메시지는 **어디서** 걸러야 하는가?

## 프로젝트에서의 문제

Raw WebSocket([ADR-0004](../adr/0004-raw-websocket-over-stomp.md))에서는 메시지가 핸들러에 문자열 그대로 들어온다.

```java
handleTextMessage(WebSocketSession session, TextMessage message)
```

Spring MVC의 `@RequestBody`·`@Valid` 같은 자동 바인딩·검증이 없다. 문자열을 객체로 바꾸고 잘못된 입력을 걸러내는 일을 직접 설계해야 한다.

## 1. 서버가 아는 것과 모르는 것

핸들러가 받는 것은 두 가지뿐이다.

| 받는 것 | 의미 | 믿을 수 있나 |
|---|---|---|
| `session` | **누가** 보냈는지 | 예. 서버가 연결(handshake) 때 만든 객체이고, 프레임이 어느 연결로 들어왔는지는 클라이언트가 바꿀 수 없다 |
| `message` | **무엇을** 원하는지 | 아니오. 클라이언트가 아무 내용이나 쓸 수 있다 |

### 비유: 은행 창구

alice가 신분증을 보여 준 뒤 쪽지를 낸다: "저는 bob입니다. 10만 원을 B 계좌로 보내 주세요."

- "저는 bob입니다" → 직원은 앞에 앉은 사람이 alice라는 걸 이미 안다. 쪽지의 이름은 믿지 않는다. 적을 필요도 없다.
- "10만 원을, B 계좌로" → alice 머릿속에만 있는 정보다. 쪽지에 적어야 한다.

| 은행 | WebSocket |
|---|---|
| 신분증 확인 | 연결(handshake). 서버가 `WebSocketSession`을 만듦 |
| 앞에 앉은 사람 | 프레임이 들어온 세션 |
| 쪽지 | 메시지 JSON |

쪽지는 누구나 아무거나 적을 수 있다. 화면을 거치지 않고 도구로 직접 보낼 수 있기 때문이다.

```bash
wscat -c ws://localhost:8080/ws
> {"type":"SEND","sender":"bob","roomId":"A","content":"내가 bob이야"}
```

- 내 처음 답 — 보낸 사람 정보는?: "메시지 안에 담지만 서버가 인증/인가를 처리해야합니다."
- 반례: 서버가 `sender`를 확인하려면 "이 세션의 주인은 alice"라는 정보를 이미 갖고 있어야 한다. 그렇다면 정답은 세션에 있고, 메시지의 `sender`는 필요 없다.

### 원칙

> - 서버가 이미 확실히 아는 것(**누가**)은 메시지에서 받지 않고, 받아도 믿지 않는다.
> - 클라이언트만 아는 것(**어느 방에, 무엇을**)은 메시지에 담는다.

## 2. 다중 방과 roomId

연결(`/ws` 접속)과 방 입장은 다른 일이다. 입장도 메시지(`JOIN`)로 요청한다.

- 내 처음 답 — `SEND`에 방 정보가 필요한가?: "서버가 방정보를 알고 있기 때문에, 서버가 기억합니다."
- 반례: 서버가 기억할 수 있는 건 **한 세션이 한 방에만 있을 때**뿐이다. 그래서 이 답은 사실이 아니라 "1세션 1방"이라는 설계 선택이다.

| 모델 | 서버 상태 | `SEND "안녕"`의 방 조회 결과 |
|---|---|---|
| 1세션 1방 | 세션 → 방 (1:1) | `A` 하나 → 정해짐 |
| 다중 방 | 세션 → {방들} (1:N) | `{A, B}` → **어느 쪽인지 모름** |

- 내 결정: "전 다중 방에 메시지를 보내고 참여시키고 싶습니다." → 모든 메시지에 `roomId` ([ADR-0006](../adr/0006-multi-room-per-session.md))

| type | 필드 | 서버가 확인할 것 |
|---|---|---|
| `JOIN` | `roomId` | 방 존재 여부(또는 생성) |
| `LEAVE` | `roomId` | 그 방 멤버인지 |
| `SEND` | `roomId`, `content` | 그 방 멤버인지 |

`roomId`도 쪽지에 적힌 값이다. 방 A에만 있는 alice가 `roomId: "B"`로 보내면?

- 내 답: "안 됩니다, 세션이 방 B 멤버인지 확인해야 합니다."

메시지는 요청일 뿐이고, 허락할지는 서버 상태로 결정한다.

## 3. 판별 단계와 책임 경계

잘못된 메시지에도 종류가 있다.

| # | 예 | 단계 | 알기 위해 봐야 하는 것 |
|---|---|---|---|
| 1 | `{"type":"SEND", "roomId":"A"` | JSON 파싱 | 메시지만 |
| 2 | `{"type":"DANCE", ...}` | 타입 결정 | 메시지만 |
| 3 | `{"type":"SEND", "content":"hi"}` | 필수 필드 검증 | 메시지만 |
| 4 | 형식은 완벽한데 B 멤버가 아님 | 상태 확인 | **서버 상태** |

- 내 답: "1,2,3은 P1-2 읽기 단계, 4는 P1-3·4 상태 확인입니다."

"잘못된 입력 처리"는 두 동작으로 나뉜다.

| 동작 | 질문 | 담당 |
|---|---|---|
| 판별 | 올바른가? 아니면 왜 틀렸나? | P1-2 (1~3), P1-3·4 (4) |
| 대응 | 틀렸으면 클라이언트에게 무엇을 하나? | P1-5 |

**검사의 책임 위치는 "무엇을 봐야 알 수 있는가"로 정해진다.**

## 4. 표현 방식: 단일 클래스 vs sealed + record

- 내 처음 답: "클래스 하나로 하려고 하는데, 좀 더 나은 방법이 있다면 알려주세요."

```java
class ChatMessage { String type; String roomId; String content; }
```

| 상황 | 결과 |
|---|---|
| `JOIN`에 `content`가 붙어 옴 | 아무 오류 없이 통과 |
| `SEND`에 `content` 없음 | `null`인 채로 핸들러까지 도착 |
| 필수 필드 검증 | "SEND일 때만 content 필수" 같은 조건부 if문 |
| 새 타입 추가 | 처리 분기를 빠뜨려도 컴파일러가 모름 |

원인: "이 타입엔 이 필드가 있다"는 사실이 타입 시스템 밖(if문, 주석)에 있다.

### record

값을 담는 불변 클래스를 한 줄로 선언한다. 생성자·getter(`roomId()`)·`equals`·`hashCode`·`toString`을 컴파일러가 만든다.

```java
public record SendMessage(String roomId, String content) implements ClientMessage {
    public SendMessage {                       // compact constructor
        Fields.requireText(roomId, "roomId");  // 필드 대입 전에 실행된다
        Fields.requireText(content, "content");
    }
}
```

### sealed interface

구현체를 `permits`에 적은 것으로 **고정**한다. 다른 곳에서 구현체를 추가할 수 없다.

```java
public sealed interface ClientMessage permits JoinMessage, LeaveMessage, SendMessage { ... }
```

구현체가 고정되어 있으므로 컴파일러가 `switch`에서 모든 경우를 처리했는지 확인할 수 있다. `TYPING`을 추가하고 분기를 빠뜨리면 **컴파일 에러**가 난다.

```java
switch (msg) {
    case JoinMessage j  -> ...;
    case LeaveMessage l -> ...;
    case SendMessage s  -> ...;
}
```

JSON 형식은 그대로 두고, Jackson `@JsonTypeInfo(property = "type")`·`@JsonSubTypes`가 `type` 값으로 어떤 record를 만들지 고른다.

### "controller 단에서 잡는다"에 대한 반박

- 내 처음 답: "이건 요청이 들어오는 controller단에서 잡습니다. 아니면 반박해주세요."
- 반박 ①: Raw WebSocket에는 controller가 없다. 메시지는 `handleTextMessage`로 들어온다.
- 반박 ②: Spring MVC에서도 `@Valid @RequestBody`는 controller 메서드가 **호출되기 전** 바인딩 단계에서 검증한다. controller는 이미 올바른 객체만 받는다.
- 그래서: 핸들러(입출력) → **파서**(`String` → `ClientMessage`) → 처리 로직. 파서를 분리하면 WebSocket 없이 단위 테스트할 수 있다.

## 5. Jackson이 record를 만드는 과정

`{"type":"SEND","roomId":"A"}`가 들어오면:

```
① JSON 파싱      문법 OK
② 클래스 선택     type = "SEND" → SendMessage
③ 생성자 찾기     SendMessage(String roomId, String content)
④ 인자 채우기     roomId ← "A",  content ← JSON에 없음 → ???
⑤ 생성자 호출     new SendMessage("A", ???)
```

Jackson이 record에서 아는 것은 **이름과 타입뿐**이다. "필수"라는 정보는 없다. 그래서 ④에서 예외를 던지지 않고 진행한다.

**걸러진다 = 객체가 생기기 전에 누군가 예외를 던진다.** ⑤에서 생성자가 호출되므로, 생성자에 검사가 있으면 Jackson 경로에서도 거기서 걸린다.

- 내 처음 예상: "자동으로 걸러지지 않고, record가 만들어지지 않습니다."
- 정리: 두 문장은 동시에 참일 수 없다. 걸러지지 않으면 객체가 만들어지고, 객체가 안 만들어지면 걸러진 것이다.

### "없다"에도 종류가 있다

| JSON | 생성자에 들어오는 `content` |
|---|---|
| `{"roomId":"A"}` (키 없음) | 누락 |
| `{"roomId":"A","content":null}` | `null` |
| `{"roomId":"A","content":""}` | `""` |
| `{"roomId":"A","content":"   "}` | `"   "` |

Jackson의 `@JsonProperty(required = true)`는 "키가 있는지"만 본다. 빈 문자열을 허용할지는 Jackson이 아니라 **우리가 정할 규칙**이다.

- 내 결정: "빈 공백도 보내지 않습니다. roomId도 같은 규칙입니다." → `null`·`""`·공백만 거절, `" 안녕 "`은 보존

## 6. 검사 위치: 생성자 vs 파서

| | (가) record 생성자 | (나) 파서 |
|---|---|---|
| 검사 위치 | 객체 자신 | 객체 바깥의 한 경로 |
| `new SendMessage("A", null)` 직접 호출 | 즉시 예외 | 그냥 만들어짐 |
| 받는 쪽 | `SendMessage`면 항상 유효하다고 믿음 | 다시 확인해야 안심 |

- 내 답: "(가) record 생성자에서 검사합니다", content 없는 `SendMessage`가 존재해도 되나 → "안됩니다."

### 우회는 어떻게 일어나나

해킹이 아니라 **파서 밖에서 `new SendMessage(...)`를 호출하는 모든 코드**가 우회 경로다.

| 경로 | 언제 |
|---|---|
| 테스트 코드에서 직접 생성 | P1-4 |
| DB에서 읽어 복원 | P2 |
| Kafka에서 읽어 다른 `ObjectMapper`로 역직렬화 | P6 |
| 다른 개발자(또는 미래의 나)의 편의 코드 | 언제든 |

P6의 Kafka 경로는 설계 시점에 존재하지 않던 경로다. 파서 검사로는 미리 막을 수 없고, 생성자 검사는 막는다.

- 내 정리: "결론은 sendMessage 도메인 안에서 만들어야하고, 파서에서 만들면 우회해서 만들 수 있는 위험이 존재한다."

**객체가 존재한다 = 유효하다.**

## 7. 모르는 필드: 거절 vs 무시

`{"type":"SEND", ..., "sender":"bob"}`나 `JOIN`에 붙은 `content`는 어떻게 할까?

- 내 처음 답: "거절합니다. sender는 믿지 않으니까요."
- 반례 ①: 무시해도 `sender`는 믿지 않게 된다. record에 `sender` 필드가 없으므로 값이 서버 로직에 닿지 않는다. "믿지 않는다"는 두 선택을 구분하는 근거가 아니다.
- 반례 ②: 클라이언트 v2가 선택 필드 `clientMsgId`를 추가했는데 서버는 v1이면?

| 정책 | 서버 v1 | 사용자 체감 |
|---|---|---|
| 무시 | 새 필드를 버리고 정상 처리 | 새 기능만 아직 안 됨 |
| 거절 | **모든 SEND 실패** | v2 사용자는 채팅이 전부 안 됨 → 서버를 항상 먼저 배포해야 함 |

### 실무에서는

**Tolerant Reader**(관대한 수신자): 모르는 필드는 무시하고, 처리에 필요한 규칙은 엄격하게 검사한다. Protocol Buffers는 하위 호환을 위해 처음부터 무시하도록 설계됐고, Spring Boot가 구성하는 Jackson도 무시하도록 설정한다.

| 변경 종류 | 예 | 처리 |
|---|---|---|
| 선택 필드 추가 | `clientMsgId` | 호환됨. 구버전은 무시 |
| 깨지는 변경 | `content` → `text` 이름 변경 | 새 `type`(`SEND_V2`), `version` 필드, WebSocket subprotocol(`Sec-WebSocket-Protocol`) |
| 필수 필드 누락 | `content: ""` | 항상 거절 |

**무시가 위험해지는 경우 — mass assignment:** 받는 객체에 클라이언트가 건드리면 안 되는 필드(예: `sender`)가 **있으면**, 무시가 아니라 그대로 대입된다. 우리 record에는 그런 필드가 아예 없어서 안전하다. 1절의 원칙이 무시 정책을 안전하게 만든다.

- 내 최종 답: "처리에 문제가 없다면 무시로 하겠습니다. 일관성은 서버의 기준으로 해야하는 엄격성을 얘기한거였습니다."
- 정리: 엄격성은 서버가 처리하는 데 필요한 규칙(알려진 `type`, 필수 필드, 빈 값 금지)에 적용하고, 처리에 영향 없는 모르는 필드는 무시한다.

## 8. 실험 결과 (2026-10-24, 실제 실행)

| 단계 | 결과 |
|---|---|
| 생성자 검사 없는 record | `ClientMessageParserTest` 27개 중 **17개 실패** — 시나리오 4(12개)·5(4개)·8(1개). 필수 필드가 누락·`null`·`""`·공백만이어도 예외 없이 객체 반환. `new SendMessage("A", null)`도 생성됨 |
| 같은 실행, 모르는 필드 | `JsonMapper.builder().build()` 기본값으로 통과 → Jackson 3.1.7 기본은 무시. 그래도 `FAIL_ON_UNKNOWN_PROPERTIES`를 명시적으로 꺼 정책을 코드에 드러냄 |
| compact constructor 검사 추가 | 27개 모두 통과, 전체 31개 통과 |
| 예외 감싸기 (`SendMessage` content 누락) | `IllegalArgumentException` → `ValueInstantiationException`(Jackson) → `InvalidMessageException`(파서) |

## 정리

- 배운 것 (내 표현): "요청이 들어오면 어디서 검사를 해야 하는지에 대한 책임을 아는 것", "sealed, record에 대해서도 알게 되었다"

| 알기 위해 봐야 하는 것 | 책임 위치 |
|---|---|
| 메시지만 | 파서(JSON 문법·`type`) + record 생성자(필수 필드) |
| 서버 상태 (방 멤버인지) | P1-3·4 |
| 실패 후 대응 | P1-5 |
