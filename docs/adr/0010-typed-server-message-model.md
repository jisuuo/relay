# ADR-0010. 서버 메시지 모델: 타입별 record와 연결 직후 sessionId 알림

- 상태: Accepted
- 날짜: 2026-10-24
- 관련 Phase: P1 (P1-4, P1-5)

## 배경

P1-4에서 서버가 처음으로 클라이언트에게 메시지를 보낸다. 지금까지 서버 → 클라이언트 전송은 한 곳도 없었다 (`sendMessage` 호출 0곳).

- 브로드캐스트를 받는 사람의 화면에는 누가, 어느 방에서, 무엇을 말했는지가 보여야 한다. 한 세션이 여러 방에 있으므로 방도 필요하다 ([ADR-0006](0006-multi-room-per-session.md)).
- 보낸 사람 본인에게도 보내기로 했다 ([ADR-0009](0009-broadcast-per-session-and-per-room-lock.md)). 그러면 A는 돌아온 메시지 중 무엇이 자기 것인지 알아야 한다. 그런데 P1에는 로그인·닉네임이 없어 "누가"를 표시할 값은 sessionId뿐이고, 서버가 A에게 A의 sessionId를 알려 주는 곳이 없다.

[ADR-0007](0007-typed-client-message-model.md) 재검토 조건: "서버 → 클라이언트 메시지 모델을 설계할 때 같은 방식을 쓸지 (P1-4)".

## 검토한 대안

### ① 표현 방식

| 대안 | 따옴표·줄바꿈 | 필드 이름 오타 | type별 필수 필드 |
|---|---|---|---|
| A. JSON 문자열 직접 조립 | 직접 이스케이프해야 한다. `text`에 `"`가 있으면 JSON이 깨진다 | 못 잡음 | 못 잡음 |
| B. `Map`에 담아 Jackson으로 변환 | Jackson이 처리 | 못 잡음 | 못 잡음 |
| **C. 타입별 record + sealed interface** (ADR-0007과 같은 방식) | Jackson이 처리 | 컴파일러가 잡음 | 생성자에서 강제 |

### ② 본인 메시지 구분

| 대안 | 설명 |
|---|---|
| **가. 연결 직후 서버가 sessionId를 알려 줌** | `afterConnectionEstablished`에서 `CONNECTED` 메시지 전송 |
| 나. 클라이언트가 `SEND`에 임시 ID를 담고 서버가 되돌려 줌 | 클라이언트 → 서버 형식(ADR-0007)이 바뀐다 |

## 결정

- ① C. `ServerMessage` sealed interface 아래 타입별 record를 둔다. JSON은 클라이언트 메시지와 같은 `{"type": ..., ...}` 형식이다.

  ```json
  {"type": "CONNECTED", "sessionId": "..."}
  {"type": "MESSAGE",   "roomId": "R", "sessionId": "...", "content": "안녕"}
  ```

- ② 가. 연결 직후 `CONNECTED`를 보낸다. 브로드캐스트를 받으려면 먼저 `JOIN`해야 하고, Spring은 `afterConnectionEstablished`가 끝난 뒤 그 세션의 메시지를 처리하므로 `CONNECTED`가 항상 첫 메시지다 (통합 테스트로 확인).
- 모든 서버 → 클라이언트 전송은 `SessionSender`(세션 락, ADR-0009)를 거친다. `CONNECTED`가 첫 유니캐스트 사용처다.
- `MESSAGE`에는 받는 사람마다 달라지는 필드가 없으므로, JSON 변환은 브로드캐스트당 한 번만 하고 같은 `TextMessage`를 멤버 전원에게 보낸다.

## 근거

- 사용자 선택 근거: "ADR-0007처럼 record + sealed interface, 일관성 때문에", "연결 직후 서버가 sessionId를 알려준다".
- A는 사용자 입력의 따옴표 하나로 메시지가 깨진다. B는 키 이름 오타와 필수 필드 누락을 막지 못한다.
- 필드 이름 `content`는 클라이언트 `SEND`의 필드 이름과 맞춘다 (같은 값이 그대로 전달됨).

## 결과와 트레이드오프

- sessionId(Spring이 정한 세션 ID)가 다른 사용자에게 보인다. P1에서는 "누가"를 표시할 다른 값이 없어 받아들인다. 사용자 식별이 생기면(P1 범위 밖) 다시 본다.
- P1-5의 에러 응답도 이 모델에 type 하나(`ERROR` 등)를 추가하는 방식으로 넣는다.

## 재검토 조건

- 사용자 식별(로그인·닉네임)이 도입될 때 → `sessionId` 대신 사용자 식별자를 쓸지
- 받는 사람마다 다른 필드가 필요해질 때 → 브로드캐스트당 한 번 변환 규칙을 다시 본다
