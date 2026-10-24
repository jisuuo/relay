package com.relay.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 엔드포인트 설정.
 *
 * <p>왜 {@code @EnableWebSocket}인가: Raw WebSocket 핸들러를 등록하는 방식이다.
 * STOMP용 {@code @EnableWebSocketMessageBroker}는 사용하지 않는다 (ADR-0004).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final ChatWebSocketHandler chatWebSocketHandler;

	public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
		this.chatWebSocketHandler = chatWebSocketHandler;
	}

	// 이유: 클라이언트는 이 경로로 HTTP Upgrade 요청을 보낸다. 등록하지 않으면 404가 돌아와 handshake가 실패한다.
	// allowed origins는 기본값(같은 출처만 허용)을 유지한다. 인증·보안은 P9에서 다룬다.
	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(chatWebSocketHandler, "/ws");
	}

}
