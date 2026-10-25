package com.relay.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 서버 → 클라이언트 전송의 유일한 입구.
 *
 * <p>왜 필요한가: 전송은 받는 사람이 아니라 보낸 사람의 스레드가 한다. 같은 방의 다른 멤버, 그 세션이 들어간 다른 방,
 * 그 세션 자신의 요청에 대한 응답이 모두 같은 세션 객체 하나로 모이므로 여러 스레드가 동시에 쓸 수 있다.
 * Tomcat은 쓰는 중인 세션에 다른 스레드가 쓰면 예외로 거절하고, 그 메시지는 사라진다 (SessionSendConcurrencyTest 시도 1).
 *
 * <p>왜 한 클래스로 모으는가: 락은 모든 호출 위치가 잡을 때만 효과가 있다. 한 곳이라도 세션에 직접 sendMessage를 부르면
 * 그 호출은 다른 스레드와 겹친다. 브로드캐스트(유니캐스트의 반복)와 P1-5 에러 응답이 모두 이 메서드를 거치게 한다 (ADR-0009).
 */
@Component
public class SessionSender {

	private static final Logger log = LoggerFactory.getLogger(SessionSender.class);

	// 이유: 세션 객체가 세션마다 하나씩 이미 있으므로 그 자체를 락으로 쓴다. 락을 따로 만들거나 보관·정리할 필요가 없다.
	// 같은 세션에 보내는 스레드끼리만 기다리고, 다른 세션·다른 방은 막지 않는다. 전체 락을 쓰면 느린 전송 하나가 서버 전체를 멈춘다.
	//
	// 실패해도 예외를 던지지 않고 false만 돌려준다: 브로드캐스트는 한 명이 실패해도 다음 멤버에게 계속 보내야 한다.
	// 실패만 보고 세션을 정리하지도 않는다. 실패는 연결 종료일 수도, 다른 이유일 수도 있는 애매한 신호다
	// (시도 1에서 1,500번 넘게 실패하는 동안 세션은 계속 open이었다). 정리는 afterConnectionClosed 한 경로에서만 한다.
	//
	// 왜 TextMessage가 아니라 WebSocketMessage인가: heartbeat의 ping도 같은 세션에 쓰는 전송이다. ping만 직접 보내면
	// 채팅 전송과 겹쳐 Tomcat이 거절한다 (P1-5, ADR-0005 후속).
	public boolean send(WebSocketSession session, WebSocketMessage<?> message) {
		synchronized (session) {
			try {
				session.sendMessage(message);
				return true;
			}
			catch (Exception e) {
				log.warn("전송 실패 session={}: {}", session.getId(), e.toString());
				return false;
			}
		}
	}

	// 이유: 닫기도 Close 프레임을 쓰는 전송이라 같은 세션 락을 거친다. 실패해도 할 수 있는 일이 없어 로그만 남긴다.
	public void close(WebSocketSession session, CloseStatus status) {
		synchronized (session) {
			try {
				session.close(status);
			}
			catch (Exception e) {
				log.warn("닫기 실패 session={}: {}", session.getId(), e.toString());
			}
		}
	}

}
