package com.relay.message;

/**
 * 연결 직후 서버가 보내는 첫 메시지. 클라이언트에게 자기 sessionId를 알려 준다.
 *
 * <p>왜 필요한가: 보낸 사람 본인에게도 MESSAGE를 돌려보내므로(ADR-0009), 클라이언트는 돌아온 메시지 중 무엇이 자기 것인지
 * sessionId로 구분해야 한다. 로그인이 없는 P1에서 서버가 이 값을 알려 주는 곳은 여기뿐이다 (ADR-0010).
 */
public record ConnectedMessage(String sessionId) implements ServerMessage {

	// 이유: 서버가 만드는 메시지도 생성자에서 검사한다. 빈 sessionId가 나가면 클라이언트가 자기 메시지를 구분하지 못한다.
	public ConnectedMessage {
		Fields.requireText(sessionId, "sessionId");
	}

}
