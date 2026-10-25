package com.relay.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 파싱할 수 없는 메시지(깨진 JSON, 모르는 type, 필수 필드 누락)에 보내는 오류 (ADR-0011).
 *
 * <p>왜 requestType·roomId가 없는가: 원인이 내용 자체라 요청 종류·방을 믿을 수 없거나 아예 읽지 못했다.
 * 원인은 파서 예외 메시지를 그대로 message에 담는다.
 */
public record InvalidMessageError(String message) implements ServerMessage {

	public InvalidMessageError {
		Fields.requireText(message, "message");
	}

	// 이유: NotMemberError와 같다. type은 ERROR, 종류는 code.
	@JsonProperty("code")
	public String code() {
		return "INVALID_MESSAGE";
	}

}
