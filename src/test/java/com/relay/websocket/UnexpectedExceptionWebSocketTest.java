package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.relay.room.RoomRegistry;

/**
 * P1-5 시나리오 6: 예상 못 한 예외(서버 버그)는 지금처럼 1011로 닫힌다 (ADR-0011).
 *
 * <p>왜 필요한가: 핸들러가 클라이언트 잘못(InvalidMessageException)만 잡기로 했다. 범위를 넓혀 모든 예외를 잡으면
 * 서버 버그도 클라이언트 잘못처럼 보인다 — 문장 2의 문제가 방향만 바뀌어 다시 생긴다. 이 테스트가 그 경계를 지킨다.
 *
 * <p>왜 spy인가: 실제 버그를 만들 수 없으므로 RoomRegistry.join이 특정 방에서만 예외를 던지게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnexpectedExceptionWebSocketTest {

	@LocalServerPort
	int port;

	@MockitoSpyBean
	RoomRegistry rooms;

	@Test
	void 서버_버그로_예외가_나면_1011로_닫힌다() throws Exception {
		doThrow(new IllegalStateException("일부러 낸 서버 버그")).when(rooms).join(any(), eq("boom"));
		CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
		WebSocketSession client = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
				closed.complete(status);
			}
		}, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);

		client.sendMessage(new TextMessage("{\"type\":\"JOIN\",\"roomId\":\"boom\"}"));

		assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
	}

}
