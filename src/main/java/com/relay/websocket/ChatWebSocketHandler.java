package com.relay.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.relay.message.ChatMessage;
import com.relay.message.ClientMessageParser;
import com.relay.message.ConnectedMessage;
import com.relay.message.JoinMessage;
import com.relay.message.LeaveMessage;
import com.relay.message.SendMessage;
import com.relay.message.ServerMessageWriter;
import com.relay.room.RoomBroadcaster;
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
	private final RoomBroadcaster broadcaster;
	private final SessionSender sender;
	private final ServerMessageWriter writer;

	public ChatWebSocketHandler(SessionRegistry registry, ClientMessageParser parser, RoomRegistry rooms,
			RoomBroadcaster broadcaster, SessionSender sender, ServerMessageWriter writer) {
		this.registry = registry;
		this.parser = parser;
		this.rooms = rooms;
		this.broadcaster = broadcaster;
		this.sender = sender;
		this.writer = writer;
	}

	// 이유: 서버가 세션 객체를 받는 유일한 시점이다. 여기서 보관하지 않으면 이후 이 사용자에게 보낼 방법이 없다.
	// 클라이언트가 자기 메시지를 구분할 수 있게 sessionId를 첫 메시지로 알려 준다 (ADR-0010).
	// Spring은 이 메서드가 끝난 뒤 이 세션의 메시지를 처리하고, 브로드캐스트를 받으려면 먼저 JOIN해야 하므로 CONNECTED가 항상 첫 메시지다.
	// 직접 sendMessage를 부르지 않고 SessionSender를 거친다: 모든 전송이 세션 락을 지나야 동시 쓰기 유실이 없다 (ADR-0009).
	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		registry.add(session);
		sender.send(session, new TextMessage(writer.write(new ConnectedMessage(session.getId()))));
	}

	// 이유: 핸들러는 입출력만 맡는다. 문자열 → 메시지 변환은 파서(P1-2), 방 상태 변경은 RoomRegistry가 한다.
	// 보낸 사람은 메시지가 아니라 프레임이 들어온 세션으로 정한다 (ADR-0007).
	// 파싱 실패(InvalidMessageException)는 여기서 잡지 않는다. 에러 응답·연결 유지 여부는 P1-5에서 정한다.
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		switch (parser.parse(message.getPayload())) {
			case JoinMessage join -> rooms.join(session, join.roomId());
			case LeaveMessage leave -> rooms.leave(session, leave.roomId());
			// 이유: 받는 사람마다 달라지는 필드가 없어 JSON 변환은 브로드캐스트당 한 번만 하고 같은 TextMessage를 전원에게 보낸다.
			// 비멤버면 broadcast가 false를 돌려주고 아무도 받지 않는다. 클라이언트에 알리는 방법은 P1-5에서 정한다.
			case SendMessage send -> broadcaster.broadcast(session, send.roomId(),
					new TextMessage(writer.write(new ChatMessage(send.roomId(), session.getId(), send.content()))));
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

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [P1-4] 연결 직후 CONNECTED 전송, SEND → RoomBroadcaster (2026-10-24)
 *   - 연결 전: ChatBroadcastWebSocketTest 두 테스트 모두 "받은 메시지=[]"로 실패 (서버 → 클라이언트 전송이 한 곳도 없었음)
 *   - 연결 후: 5회 모두 통과 (테스트 쪽 메시지 선택 실수를 고친 뒤). 전체 테스트 53개 통과
 */
