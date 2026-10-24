package com.relay.message;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * ServerMessage → JSON 문자열.
 *
 * <p>왜 파서와 나누는가: 읽기(ClientMessageParser)는 모르는 필드를 무시하는 설정이 필요하고, 쓰기는 필요 없다.
 * 방향마다 한 가지 일만 한다.
 */
@Component
public class ServerMessageWriter {

	private final JsonMapper mapper = new JsonMapper();

	// 이유: 구체 record가 아니라 ServerMessage 기준으로 쓴다. 기준 타입의 @JsonTypeInfo가 적용돼 "type" 필드가 항상 붙는다.
	public String write(ServerMessage message) {
		return mapper.writerFor(ServerMessage.class).writeValueAsString(message);
	}

}
