# ADR-0011. 잘못된 입력 대응: 오류 응답 형식과 연결 유지

- 상태: Accepted
- 날짜: 2026-10-25
- 관련 Phase: P1 (P1-5)

## 배경

P1-5 정의 단계에서 실험 4·5로 확인한 문제 (`docs/phases/phase-01-websocket/plan.md` P1-5):

- 문장 2: 깨진 JSON 한 번이 들어오면 연결 유지와 정확한 오류 정보가 둘 다 깨진다 → B는 1011(서버 내부 오류), reason 빈 값으로 끊기고, 이후 보낸 정상 메시지는 예외 없이 버려진다. 원인: 파서 예외를 핸들러가 잡지 않아 Spring `ExceptionWebSocketHandlerDecorator`가 `close(SERVER_ERROR)`를 부른다.
- 문장 3: 방 멤버가 아닌 세션이 그 방에 `SEND`하면 정확한 오류 정보가 깨진다 → 아무 응답도, 서버 로그도 없다. 비멤버 `LEAVE`도 같다.

[ADR-0010](0010-typed-server-message-model.md)은 "P1-5의 에러 응답도 이 모델에 type 하나를 추가한다"고 남겨 두었다.

## 검토한 대안

### ① 어디서 보내나

| 대안 | 문제 |
|---|---|
| A. 비멤버를 판단하는 곳(`RoomRegistry`·`RoomBroadcaster`)에서 바로 전송 | `RoomRegistry.leave`는 전체 락 안 → 받는 쪽 버퍼가 차 있으면(실험 3) 서버 전체가 최대 약 20초 멈춤. 매핑·퍼뜨리기 클래스가 JSON·전송까지 맡음. 실패 지점마다 오류 형식 코드가 흩어짐 |
| **B. 판단은 그 자리(락 안), 응답은 `handleTextMessage`(락 밖)** | 세 실패(파서·`RoomRegistry`·`RoomBroadcaster`)가 모두 돌아오는 곳. 판단을 핸들러로 옮기면 확인과 행동 사이에 `LEAVE`·끊김이 끼어들므로 판단은 옮기지 않는다 |

### ② Java 표현

| 대안 | 문제 |
|---|---|
| A. record 하나 + 없는 칸은 `null` | 오류마다 `null` 칸이 달라 조건부 검사 필요 (ADR-0007의 단일 클래스 반례와 같음) |
| **B. 오류 종류마다 record** | 각 생성자가 자기 필수 필드를 검사 |

### ③ JSON 형식

| 대안 | 문제 |
|---|---|
| A. 오류마다 다른 `type` (`{"type":"NOT_MEMBER"}`) | 옛 클라이언트는 처음 보는 오류를 오류인지도 모른다 |
| **B. `type`은 `ERROR` 하나, 종류는 `code`** | 처음 보는 `code`도 "오류"로는 알아보고 `message`를 보여 줄 수 있다 |

### ④ 깨진 JSON에 연결을 유지하나

| 대안 | 근거 |
|---|---|
| **A. 유지** | 실수 한 번에 끊기면 안 된다. 메시지 경계는 Tomcat이 프레임으로 나눠 두므로 다음 프레임은 읽을 수 있다 |
| B. 맞는 코드(1007 등)와 reason으로 끊기 | 계속 쓰레기를 보내는 클라이언트를 막지만, 그 책임은 Rate limit에 있다 |

## 결정

- 응답 위치: 판단은 `RoomRegistry`·`RoomBroadcaster`가 락 안에서 하고 `boolean`을 돌려준다. `handleTextMessage`가 그 결과와 `InvalidMessageException`을 받아 `SessionSender`로 오류를 보낸다. 실패 이유가 둘 이상 생기면 `boolean` 대신 이유를 담은 값으로 바꾼다.
- Java: 오류 종류마다 record (`ServerMessage` 아래).
- JSON:

  ```json
  {"type": "ERROR", "code": "NOT_MEMBER", "requestType": "SEND", "roomId": "X", "message": "..."}
  {"type": "ERROR", "code": "INVALID_MESSAGE", "message": "..."}
  ```

  - `NOT_MEMBER`: 방이 원인이므로 `requestType`(`SEND`·`LEAVE`)과 `roomId`를 담는다.
  - `INVALID_MESSAGE`: 원인은 내용이므로 요청 종류·방을 담지 않고 원인을 `message`에 적는다. 파서는 바꾸지 않는다.
- 잡는 예외: `InvalidMessageException`(클라이언트 잘못)만 잡는다. 예상 못 한 예외(서버 버그)는 지금처럼 Spring이 1011로 닫는다 — 1011의 원래 뜻과 맞다.
- 연결: 두 오류 모두 연결을 유지한다.

## 근거

- 사용자 판단 (P1-5 고민·설계): "판단은 저기서 하지만 이 return 값을 결국 맨 위 handleText에서 받아서 돌려주는 입출 창구", "타입별 record", "A 유지입니다. 실수 한 번에 끊기면 안 되니까요", "A입니다. 서버 버그는 1011이 맞으니까요".
- 오류 응답에는 "이걸 고치려면 무엇을 알아야 하나"의 답만 담는다.

## 결과와 트레이드오프

- 클라이언트가 보낸 메시지 하나하나와 오류를 짝지을 수 없다(같은 방에 `SEND` 두 개가 실패하면 구별 불가). 클라이언트가 만든 메시지 id가 필요하며 P3(멱등성 키)에서 재전송·중복 문제가 재현될 때 도입한다.
- 계속 쓰레기를 보내는 클라이언트도 연결이 유지되고, 서버는 매번 파싱·오류 응답 비용을 쓴다 → P9 Rate limit.

## 재검토 조건

- 실패 이유가 둘 이상 생길 때 → `boolean` 반환을 이유 값으로
- 화면·재전송이 생기거나 P3에서 중복 문제가 재현될 때 → 클라이언트 메시지 id
- 반복 잘못된 입력이 측정·관찰될 때 → P9 Rate limit 또는 연결 종료 정책
