package com.relay.message;

/**
 * 방에 퍼뜨리는 채팅 메시지.
 *
 * <p>왜 roomId가 필요한가: 한 세션이 여러 방에 있을 수 있어, 받는 쪽이 어느 방의 메시지인지 알아야 한다 (ADR-0006).
 * <p>왜 sessionId인가: P1에는 로그인·닉네임이 없어 "누가"를 표시할 값이 sessionId뿐이다 (ADR-0010).
 * 클라이언트가 보낸 값이 아니라 서버가 프레임이 들어온 세션에서 꺼낸 값이다.
 * <p>왜 content인가: 클라이언트 SEND의 필드 이름과 맞춘다. 같은 값이 그대로 전달된다.
 */
public record ChatMessage(String roomId, String sessionId, String content) implements ServerMessage {

	public ChatMessage {
		Fields.requireText(roomId, "roomId");
		Fields.requireText(sessionId, "sessionId");
		Fields.requireText(content, "content");
	}

}
