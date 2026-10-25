package com.relay.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 방 멤버가 아닌 세션이 그 방에 SEND·LEAVE했을 때 보내는 오류 (ADR-0011).
 *
 * <p>왜 requestType·roomId를 담는가: 원인이 "그 방"이라, 클라이언트가 어느 요청이 어느 방에서 거절됐는지 알아야 고칠 수 있다
 * (예: JOIN하지 않고 SEND했다 → 먼저 JOIN). 같은 방의 SEND 두 개는 구별할 수 없다 → 메시지 id는 P3.
 */
public record NotMemberError(RequestType requestType, String roomId, String message) implements ServerMessage {

	// 이유: 문자열로 두면 "SNED" 같은 오타가 그대로 클라이언트에 나간다. 비멤버로 거절될 수 있는 요청은 이 둘뿐이다
	// (JOIN은 비멤버가 하는 요청이다).
	public enum RequestType {
		SEND, LEAVE
	}

	public NotMemberError {
		if (requestType == null) {
			throw new IllegalArgumentException("requestType은(는) 비어 있을 수 없다");
		}
		Fields.requireText(roomId, "roomId");
		Fields.requireText(message, "message");
	}

	// 이유: type은 ERROR 하나로 두고 종류는 code로 나눈다 (ADR-0011 ③). 처음 보는 code도 클라이언트가 "오류"로는 알아본다.
	// 값이 이 record마다 고정이라 생성자 인자로 받지 않는다. record 구성 요소가 아니어서 @JsonProperty로 JSON에 넣는다.
	@JsonProperty("code")
	public String code() {
		return "NOT_MEMBER";
	}

}
