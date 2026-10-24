# ISSUE-02. 필수 필드가 비어 있어도 메시지 객체가 만들어짐

- 날짜: 2026-10-24
- 과제: P1-2 JSON 메시지 모델
- 관련: [ADR-0007](../../../adr/0007-typed-client-message-model.md), [개념 노트](../../../learning/message-validation-responsibility.md), `ClientMessageParser`, `ClientMessageParserTest`

## 1. 문제 발생

- 상황: 클라이언트 JSON을 `ClientMessage`(sealed interface) 아래의 `JoinMessage`·`LeaveMessage`·`SendMessage` record로 역직렬화한다. 처음 record에는 생성자 검사가 없었다.
- 재현: `{"type":"SEND","roomId":"A"}`처럼 `roomId`·`content`가 누락·`null`·`""`·공백만인 입력을 넣었다. 파서를 거치지 않고 `new SendMessage("A", null)`도 직접 호출했다.
- 결과 (실제 실행): `ClientMessageParserTest` **27개 중 17개 실패.**
  - 시나리오 4: `roomId`가 누락·`null`·`""`·공백만인 경우, 세 타입 각각 (12개)
  - 시나리오 5: `SEND`의 `content`가 누락·`null`·`""`·공백만인 경우 (4개)
  - 시나리오 8: `new SendMessage("A", null)` 직접 생성 (1개)
- 특징: Jackson은 **예외 없이** 필드가 `null`이거나 빈 record를 반환했다. 이대로면 잘못된 메시지가 처리 로직까지 그대로 도착한다.

## 2. 원인 분석

- Jackson이 record를 만드는 순서는 다음과 같다.

  ```
  ① JSON 파싱      문법 OK
  ② 클래스 선택     type = "SEND" → SendMessage
  ③ 생성자 찾기     SendMessage(String roomId, String content)
  ④ 인자 채우기     roomId ← "A",  content ← JSON에 없음 → null
  ⑤ 생성자 호출     new SendMessage("A", null)
  ```

- Jackson이 record에 대해 아는 것은 필드의 **이름과 타입뿐**이다. "필수"라는 정보는 없다. 그래서 ④에서 멈추지 않는다.
- `""`·공백만인 값을 거절할지는 Jackson이 아니라 **우리가 정해야 하는 규칙**이다. `@JsonProperty(required = true)`도 키가 있는지만 본다.
- 처음 예상했던 "자동으로 걸러져 record가 만들어지지 않는다"는 틀렸다. "걸러진다"는 말은 곧 **객체가 생기기 전에 누군가 예외를 던진다**는 뜻이고, 그 일을 하는 코드가 없었다.

## 3. 해결 방안과 해결

### 검토한 방안 (검사 위치)

| 방안 | 장점 | 단점 |
|---|---|---|
| **가. record 생성자 (compact constructor)** | 어떤 경로로 만들어도 검사를 피할 수 없음 → 객체가 존재하면 곧 유효함 | 테스트에서 잘못된 객체를 일부러 만들 수 없음 (의도된 제약) |
| 나. 파서에서 생성 후 확인 | 검사 로직이 한 곳에 모임 | 파서를 거치지 않는 경로(테스트, P2 DB 복원, P4 Kafka 역직렬화, 내부 코드)는 검사를 우회함 |
| (참고) Bean Validation `@NotBlank` | 선언적 | 새 의존성 필요. Raw WebSocket에는 `@Valid`가 없어 직접 호출해야 함 |

### 해결

- 각 record의 compact constructor에서 `Fields.requireText`로 검사한다. `null`·`""`·공백만인 값은 거절하고, `" 안녕 "`처럼 내용이 있는 값은 자르지 않고 그대로 둔다.
- 생성자 예외는 `IllegalArgumentException` → Jackson `ValueInstantiationException` → 파서의 `InvalidMessageException` 순서로 감싸진다. 실패는 파서 밖으로 예외 타입 하나로만 나간다.
- 결과 (실제 실행): `ClientMessageParserTest` **27개 모두 통과**, `./gradlew test` 전체 31개 통과.

### 함께 확인한 것

- 모르는 필드(`"sender":"bob"`, `JOIN`에 붙은 `content`)는 같은 실행에서 기본 설정(`JsonMapper.builder().build()`)만으로 통과했다. Jackson 3.1.7의 기본값은 무시다. 정책이 코드에 드러나도록 `FAIL_ON_UNKNOWN_PROPERTIES`를 명시적으로 껐다.

### 남은 것

- 서버 상태를 봐야 알 수 있는 검사(그 방의 멤버인지)는 P1-3·4에서 한다.
- 판별한 실패에 어떻게 대응할지(에러 응답 형식, 연결 유지 여부)는 P1-5에서 정한다.
