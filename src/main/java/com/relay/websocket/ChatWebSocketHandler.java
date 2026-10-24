package com.relay.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.relay.message.ClientMessageParser;
import com.relay.message.JoinMessage;
import com.relay.message.LeaveMessage;
import com.relay.message.SendMessage;
import com.relay.room.RoomRegistry;

/**
 * WebSocket 연결의 수명주기를 처리한다.
 *
 * <p>왜 Raw WebSocket 핸들러인가: 방 관리·브로드캐스트·세션 정리를 프레임워크(STOMP)에 맡기지 않고
 * 직접 구현해 문제를 재현하기 위해서다 (ADR-0004).
 *
 * <p>왜 TextWebSocketHandler인가: 메시지를 JSON 텍스트로 주고받을 예정이므로(P1-2) 텍스트 frame만 받는다.
 * 바이너리 frame이 오면 상위 클래스가 연결을 닫는다.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

	private final SessionRegistry registry;
	private final ClientMessageParser parser;
	private final RoomRegistry rooms;

	public ChatWebSocketHandler(SessionRegistry registry, ClientMessageParser parser, RoomRegistry rooms) {
		this.registry = registry;
		this.parser = parser;
		this.rooms = rooms;
	}

	// 이유: 서버가 세션 객체를 받는 유일한 시점이다. 여기서 보관하지 않으면 이후 이 사용자에게 보낼 방법이 없다.
	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		registry.add(session);
	}

	// 이유: 핸들러는 입출력만 맡는다. 문자열 → 메시지 변환은 파서(P1-2), 방 상태 변경은 RoomRegistry가 한다.
	// 보낸 사람은 메시지가 아니라 프레임이 들어온 세션으로 정한다 (ADR-0007).
	// 파싱 실패(InvalidMessageException)는 여기서 잡지 않는다. 에러 응답·연결 유지 여부는 P1-5에서 정한다.
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		switch (parser.parse(message.getPayload())) {
			case JoinMessage join -> rooms.join(session, join.roomId());
			case LeaveMessage leave -> rooms.leave(session, leave.roomId());
			// 같은 방 브로드캐스트는 P1-4에서 구현한다.
			case SendMessage send -> { }
		}
	}

	// 이유: 끊긴 세션이 남으면 메모리 누수·접속자 수 왜곡·이후 전송 실패가 생긴다.
	// 주의: close frame이 오지 않는 비정상 단절에서는 이 메서드가 제때 호출되지 않을 수 있다 → P1-5 heartbeat (ADR-0005).
	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		registry.remove(session);
		// 이유: 사용자는 보통 LEAVE 없이 연결을 끊는다. 여기서 정리하지 않으면 불변식 ③(끊긴 세션은 매핑에 없다)이 깨진다.
		rooms.removeSession(session);
	}

}
