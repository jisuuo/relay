package com.relay.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 클라이언트 → 서버 메시지.
 *
 * <p>왜 sealed interface + 타입별 record인가: 타입마다 필요한 필드가 다르다.
 * 클래스 하나에 모든 필드를 두면 "SEND일 때만 content 필수" 같은 규칙이 if문으로 흩어지고,
 * 새 타입의 처리 분기를 빠뜨려도 컴파일러가 알려 주지 않는다.
 * sealed로 구현체를 고정하면 {@code switch} 패턴 매칭에서 분기 누락이 컴파일 에러가 된다 (ADR-0007).
 *
 * <p>왜 보낸 사람 필드가 없는가: 누가 보냈는지는 서버가 세션으로 이미 안다.
 * 클라이언트가 JSON에 적은 값은 누구나 조작할 수 있으므로 받지 않는다.
 * 필드가 아예 없으니 클라이언트가 {@code "sender"}를 보내도 서버 로직에 닿지 않는다.
 */
// 이유: JSON 형식은 {"type":"SEND", ...} 그대로 두고, type 값으로 역직렬화할 record를 고른다.
// 봉투 형식({type, payload})은 와이어 형식이 바뀌고 지금 필요 없어서 선택하지 않았다.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = JoinMessage.class, name = "JOIN"),
		@JsonSubTypes.Type(value = LeaveMessage.class, name = "LEAVE"),
		@JsonSubTypes.Type(value = SendMessage.class, name = "SEND")
})
public sealed interface ClientMessage permits JoinMessage, LeaveMessage, SendMessage {

	String roomId();

}
