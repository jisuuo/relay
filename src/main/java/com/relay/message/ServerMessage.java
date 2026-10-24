package com.relay.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 서버 → 클라이언트 메시지.
 *
 * <p>왜 클라이언트 메시지와 같은 방식(sealed interface + 타입별 record)인가: 타입마다 필드가 다르다는 사실을 타입 시스템에 담아
 * 필드 이름 오타·필수 필드 누락을 컴파일러와 생성자가 막게 한다. JSON 문자열을 직접 조립하면 내용에 따옴표 하나만 있어도
 * JSON이 깨지고, Map은 키 이름 오타를 잡지 못한다 (ADR-0010).
 */
// 이유: 클라이언트 메시지와 같은 {"type": ..., ...} 형식을 쓴다. type 값으로 클라이언트가 메시지 종류를 구분한다.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ConnectedMessage.class, name = "CONNECTED"),
		@JsonSubTypes.Type(value = ChatMessage.class, name = "MESSAGE")
})
public sealed interface ServerMessage permits ConnectedMessage, ChatMessage {
}
