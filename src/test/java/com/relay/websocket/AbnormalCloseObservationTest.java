package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * P1-4 시나리오 7 (관찰): close frame 없이 연결이 끊기면 서버는 언제 알아차리는가, 끊긴 세션에 보내면 어떻게 되는가.
 *
 * <p>왜 관찰인가: "전송 실패가 연결 종료로 이어져 afterConnectionClosed가 자동 호출되는가"는 설계 단계에서 답을 몰랐다 (ADR-0009).
 * 기대값을 미리 정하지 않고 실제 동작을 기록한 뒤, 관찰한 사실만 단언으로 남긴다.
 *
 * <p>왜 소켓으로 직접 handshake하는가: StandardWebSocketClient는 close()에서 항상 close frame을 보낸다.
 * close frame 없는 끊김(FIN·RST)을 만들려면 TCP 소켓을 직접 다뤄야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbnormalCloseObservationTest {

	@LocalServerPort
	int port;

	@Test
	void close_frame_없이_TCP를_닫으면_FIN() throws Exception {
		observe(false);
	}

	@Test
	void close_frame_없이_연결을_강제로_끊으면_RST() throws Exception {
		observe(true);
	}

	private void observe(boolean reset) throws Exception {
		Socket socket = handshake();
		WebSocketSession server = Observer.established.poll(5, TimeUnit.SECONDS);
		assertThat(server).isNotNull();

		if (reset) {
			// SO_LINGER 0으로 닫으면 FIN 대신 RST를 보낸다 (프로세스 강제 종료와 비슷한 끊김).
			socket.setSoLinger(true, 0);
		}
		long closedAt = System.nanoTime();
		socket.close();

		CloseStatus status = null;
		try {
			status = Observer.closed(server).get(5, TimeUnit.SECONDS);
		}
		catch (java.util.concurrent.TimeoutException e) {
			// 5초 안에 호출되지 않음도 관찰 결과다.
		}
		long noticedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt);

		boolean sent = new SessionSender().send(server, new TextMessage("끊긴 뒤 전송"));

		System.out.printf("[관찰 %s] afterConnectionClosed=%s (%s), 끊은 뒤 %dms, 이후 전송 결과=%s, isOpen=%s%n",
				reset ? "RST" : "FIN", status != null, status, noticedMs, sent, server.isOpen());

		// 관찰한 사실 (하단 변경 이력 v1, 3회 동일). 걸린 시간(0~3ms)은 환경에 따라 달라 단언하지 않는다.
		// 서버는 전송하기 전에 이미 읽기 쪽에서 끊김을 알아차리고 afterConnectionClosed를 호출했다.
		assertThat(status).as("close frame 없는 끊김도 afterConnectionClosed가 호출된다").isNotNull();
		assertThat(status.getCode()).as("close frame을 받지 못한 비정상 종료 코드").isEqualTo(1006);
		// 끊긴 세션에 보내면 SessionSender는 예외 대신 false를 돌려준다 (Tomcat: "session ... has been closed").
		assertThat(sent).isFalse();
		assertThat(server.isOpen()).isFalse();
	}

	private Socket handshake() throws Exception {
		Socket socket = new Socket("localhost", port);
		OutputStream out = socket.getOutputStream();
		out.write(("GET /ws-observe HTTP/1.1\r\n"
				+ "Host: localhost:" + port + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
				+ "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
		// 응답 헤더 끝(\r\n\r\n)까지 읽어 101 Switching Protocols인지 확인한다.
		InputStream in = socket.getInputStream();
		StringBuilder header = new StringBuilder();
		while (!header.toString().endsWith("\r\n\r\n")) {
			int b = in.read();
			if (b < 0) {
				break;
			}
			header.append((char) b);
		}
		assertThat(header.toString()).startsWith("HTTP/1.1 101");
		return socket;
	}

	// 이 테스트 컨텍스트에만 등록되는 엔드포인트. 서버 쪽 세션과 afterConnectionClosed 호출을 기록한다.
	@TestConfiguration
	static class Observer implements WebSocketConfigurer {

		static final BlockingQueue<WebSocketSession> established = new LinkedBlockingQueue<>();
		static final Map<String, CompletableFuture<CloseStatus>> closed = new ConcurrentHashMap<>();

		static CompletableFuture<CloseStatus> closed(WebSocketSession session) {
			return closed.computeIfAbsent(session.getId(), id -> new CompletableFuture<>());
		}

		@Override
		public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
			registry.addHandler(new TextWebSocketHandler() {
				@Override
				public void afterConnectionEstablished(WebSocketSession session) {
					established.add(session);
				}

				@Override
				public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
					closed(session).complete(status);
				}
			}, "/ws-observe");
		}

	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] FIN·RST로 끊은 뒤 afterConnectionClosed 호출 여부와 이후 전송 관찰 (2026-10-24, 3회 실행 모두 같은 결과)
 *   - FIN (close frame 없이 socket.close): afterConnectionClosed 호출, CloseStatus 1006 (reason 없음), 끊은 뒤 0ms 안에 알아차림
 *   - RST (SO_LINGER 0): afterConnectionClosed 호출, CloseStatus 1006 "Connection reset", 2~3ms
 *   - 이후 전송: SessionSender가 false. Tomcat 예외는 IllegalStateException "The WebSocket session [n] has been closed
 *     and no method (apart from close()) may be called on a closed session". isOpen=false
 *   - 해석: 끊김은 전송이 아니라 서버의 읽기 쪽이 FIN·RST를 받고 알아차렸다. 이번 관찰에서 "전송 실패가 종료를 일으키는" 경우는
 *     나오지 않았다. SessionSendConcurrencyTest에서는 동시 쓰기로 1,500번 넘게 전송이 실패해도 연결이 닫히지 않았다.
 *   - 한계: FIN·RST가 서버에 도착하지 않는 끊김(네트워크 단절, NAT 타임아웃)은 localhost에서 만들 수 없어 관찰하지 못했다.
 *     이 경우는 서버가 알아차릴 신호가 없으므로 P1-5 heartbeat(ADR-0005)에서 다룬다.
 */
