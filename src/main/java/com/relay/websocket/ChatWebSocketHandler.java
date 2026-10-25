package com.relay.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.relay.message.ChatMessage;
import com.relay.message.ClientMessage;
import com.relay.message.ClientMessageParser;
import com.relay.message.ConnectedMessage;
import com.relay.message.InvalidMessageError;
import com.relay.message.InvalidMessageException;
import com.relay.message.JoinMessage;
import com.relay.message.LeaveMessage;
import com.relay.message.NotMemberError;
import com.relay.message.NotMemberError.RequestType;
import com.relay.message.SendMessage;
import com.relay.message.ServerMessage;
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
	private final Heartbeat heartbeat;

	public ChatWebSocketHandler(SessionRegistry registry, ClientMessageParser parser, RoomRegistry rooms,
			RoomBroadcaster broadcaster, SessionSender sender, ServerMessageWriter writer, Heartbeat heartbeat) {
		this.registry = registry;
		this.parser = parser;
		this.rooms = rooms;
		this.broadcaster = broadcaster;
		this.sender = sender;
		this.writer = writer;
		this.heartbeat = heartbeat;
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
	//
	// 오류 응답은 여기서 보낸다 (ADR-0011): 파서·RoomRegistry·RoomBroadcaster 세 실패가 모두 돌아오는 곳이고, 락 밖이다.
	// 비멤버 판단은 각자 락 안에서 하고 결과(boolean)만 돌려준다. 판단까지 여기로 옮기면 확인과 행동 사이에 LEAVE·끊김이 끼어든다.
	// 락 안에서 오류를 보내면, 받는 쪽 버퍼가 찼을 때(실험 3) 전체 락·방 락을 쥔 채 최대 약 20초 멈춘다.
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		ClientMessage request;
		try {
			request = parser.parse(message.getPayload());
		}
		// 이유: 클라이언트 잘못(InvalidMessageException)만 잡는다. 실수 한 번에 연결을 끊지 않고 오류만 알린다.
		// 다른 예외(서버 버그)는 잡지 않아 Spring이 1011로 닫는다 — 1011의 원래 뜻과 맞다 (UnexpectedExceptionWebSocketTest).
		catch (InvalidMessageException e) {
			reply(session, new InvalidMessageError(e.getMessage()));
			return;
		}
		switch (request) {
			case JoinMessage join -> rooms.join(session, join.roomId());
			case LeaveMessage leave -> {
				if (!rooms.leave(session, leave.roomId())) {
					reply(session, notMember(RequestType.LEAVE, leave.roomId()));
				}
			}
			// 이유: 받는 사람마다 달라지는 필드가 없어 JSON 변환은 브로드캐스트당 한 번만 하고 같은 TextMessage를 전원에게 보낸다.
			// 비멤버면 broadcast가 false를 돌려주고 아무도 받지 않는다.
			case SendMessage send -> {
				TextMessage chat = new TextMessage(
						writer.write(new ChatMessage(send.roomId(), session.getId(), send.content())));
				if (!broadcaster.broadcast(session, send.roomId(), chat)) {
					reply(session, notMember(RequestType.SEND, send.roomId()));
				}
			}
		}
	}

	private static NotMemberError notMember(RequestType requestType, String roomId) {
		return new NotMemberError(requestType, roomId, "방 " + roomId + "의 멤버가 아니다. 먼저 JOIN해야 한다");
	}

	// 이유: 오류 응답도 다른 전송과 같은 세션에 겹쳐 쓸 수 있으므로 SessionSender(세션 락)를 거친다 (ADR-0009).
	private void reply(WebSocketSession session, ServerMessage error) {
		sender.send(session, new TextMessage(writer.write(error)));
	}

	// 이유: pong은 클라이언트 라이브러리가 ping에 자동으로 보내는 대답이다. 받았다는 사실만 Heartbeat에 알린다.
	// TextWebSocketHandler는 pong을 기본으로 무시하므로, 덮어쓰지 않으면 살아 있는 클라이언트도 타임아웃으로 끊긴다.
	@Override
	protected void handlePongMessage(WebSocketSession session, PongMessage message) {
		heartbeat.pongReceived(session);
	}

	// 이유: 끊긴 세션이 남으면 메모리 누수·접속자 수 왜곡·이후 전송 실패가 생긴다.
	// FIN·RST·Close가 오지 않는 비정상 단절에서는 Spring이 이 메서드를 부르지 않는다(실험 1).
	// 그 경우는 Heartbeat가 응답 없는 세션을 닫아 이 메서드가 불리게 한다 (ADR-0005 후속).
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
 *
 * [P1-5] 오류 응답: InvalidMessageException → INVALID_MESSAGE, 비멤버 SEND·LEAVE → NOT_MEMBER (2026-10-25)
 *   - 고치기 전: InvalidInputWebSocketTest 3개 실패. 깨진 JSON → CONNECTED만 받고 1011로 닫힘(open=false),
 *               비멤버 SEND·LEAVE → CONNECTED만 받고 아무 응답 없음(open=true). 실험 4·5와 같음
 *   - 고친 후: InvalidInputWebSocketTest 3개 통과, UnexpectedExceptionWebSocketTest(1011 유지) 통과
 */
