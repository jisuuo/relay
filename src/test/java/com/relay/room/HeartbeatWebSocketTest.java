package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * P1-5 시나리오 1·2: FIN·RST 없이 사라진 클라이언트(유령)는 heartbeat가 정리하고, pong하는 클라이언트는 끊지 않는다 (ADR-0005 후속).
 *
 * <p>왜 TCP 소켓으로 직접 연결하나: pong은 앱이 아니라 WebSocket 라이브러리가 자동으로 보낸다. 소켓으로 handshake만 하면
 * ping을 읽고 pong으로 바꿔 줄 코드가 없어 응답하지 않는다. FIN·RST도 보내지 않으므로 실험 1의 일시 정지된 C와 같은 유령이다.
 *
 * <p>왜 주기·타임아웃을 짧게 덮어쓰나: 운영 값(30초/90초)을 기다리면 테스트 하나가 2분 가까이 걸린다.
 * 같은 패키지에 두어 RoomRegistry의 스냅샷(package-private)으로 매핑을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"relay.heartbeat.interval=200ms",
		"relay.heartbeat.timeout=600ms"
})
class HeartbeatWebSocketTest {

	@LocalServerPort
	int port;

	@Autowired
	RoomRegistry registry;

	@Test
	void pong하지_않는_유령은_타임아웃_뒤_방에서_정리된다() throws Exception {
		Socket ghost = handshake();
		sendText(ghost, "{\"type\":\"JOIN\",\"roomId\":\"ghost-room\"}");
		await(() -> registry.roomsSnapshot().containsKey("ghost-room"), 5, "유령이 방에 들어감");

		// 아무것도 하지 않는다: 읽지도, pong하지도, 닫지도 않는다 (실험 1).
		// 타임아웃 600ms + 확인 간격 200ms보다 넉넉히 기다린다.
		await(() -> !registry.roomsSnapshot().containsKey("ghost-room")
				&& registry.roomsBySessionSnapshot().isEmpty(), 3, "유령이 정리됨");

		ghost.close();
	}

	@Test
	void pong하는_클라이언트는_조용해도_끊기지_않는다() throws Exception {
		WebSocketSession client = new StandardWebSocketClient().execute(new TextWebSocketHandler(),
				"ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
		client.sendMessage(new TextMessage("{\"type\":\"JOIN\",\"roomId\":\"quiet-room\"}"));
		await(() -> registry.roomsSnapshot().containsKey("quiet-room"), 5, "방에 들어감");

		// 이유: 터널·D 반례 — 살아 있는 사용자를 끊는 것이 늦게 찾는 것보다 나쁘다. 타임아웃의 5배를 아무 메시지 없이 보낸다.
		Thread.sleep(3_000);

		assertThat(client.isOpen()).isTrue();
		assertThat(registry.roomsSnapshot()).containsKey("quiet-room");

		client.close();
	}

	private Socket handshake() throws Exception {
		Socket socket = new Socket("localhost", port);
		OutputStream out = socket.getOutputStream();
		out.write(("GET /ws HTTP/1.1\r\n"
				+ "Host: localhost:" + port + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
				+ "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
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

	// 이유: 클라이언트 → 서버 프레임은 반드시 마스킹해야 한다(RFC 6455). 마스킹하지 않으면 서버가 프로토콜 오류로 닫는다.
	// 짧은 텍스트(125바이트 이하) 한 프레임만 보내면 되므로 길이 확장은 다루지 않는다.
	private void sendText(Socket socket, String text) throws Exception {
		byte[] payload = text.getBytes(StandardCharsets.UTF_8);
		byte[] mask = {1, 2, 3, 4};
		byte[] frame = new byte[2 + 4 + payload.length];
		frame[0] = (byte) 0x81; // FIN + text
		frame[1] = (byte) (0x80 | payload.length); // MASK + 길이
		System.arraycopy(mask, 0, frame, 2, 4);
		for (int i = 0; i < payload.length; i++) {
			frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
		}
		socket.getOutputStream().write(frame);
		socket.getOutputStream().flush();
	}

	private void await(Supplier<Boolean> condition, int seconds, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < deadline) {
			if (condition.get()) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError(seconds + "초 안에 \"" + what + "\"이 되지 않음. rooms=" + registry.roomsSnapshot()
				+ ", roomsBySession=" + registry.roomsBySessionSnapshot());
	}

}
