package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * P1-3 연결: 실제 클라이언트가 보낸 JOIN·LEAVE와 연결 종료가 RoomRegistry까지 전달되는지 확인한다.
 *
 * <p>이유: RoomRegistry 단위 테스트만으로는 핸들러가 파서를 거쳐 저장소를 실제로 호출하는지 알 수 없다.
 * 같은 패키지에 두어 저장소의 스냅샷(package-private)으로 상태를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomWebSocketTest {

	@LocalServerPort
	int port;

	@Autowired
	RoomRegistry registry;

	@Test
	void JOIN하면_방에_들어가고_LEAVE하면_방이_사라진다() throws Exception {
		WebSocketSession client = connect();

		client.sendMessage(new TextMessage("{\"type\":\"JOIN\",\"roomId\":\"A\"}"));
		await(() -> memberCounts().equals(Map.of("A", 1)));

		client.sendMessage(new TextMessage("{\"type\":\"LEAVE\",\"roomId\":\"A\"}"));
		await(() -> registry.roomsSnapshot().isEmpty() && registry.roomsBySessionSnapshot().isEmpty());

		client.close();
	}

	@Test
	void 방에_들어간_채로_연결을_끊으면_모든_방에서_정리된다() throws Exception {
		WebSocketSession client = connect();
		client.sendMessage(new TextMessage("{\"type\":\"JOIN\",\"roomId\":\"A\"}"));
		client.sendMessage(new TextMessage("{\"type\":\"JOIN\",\"roomId\":\"C\"}"));
		await(() -> memberCounts().equals(Map.of("A", 1, "C", 1)));

		// LEAVE 없이 정상 종료. 비정상 단절은 P1-5에서 다룬다.
		client.close(CloseStatus.NORMAL);

		await(() -> registry.roomsSnapshot().isEmpty() && registry.roomsBySessionSnapshot().isEmpty());
	}

	private Map<String, Integer> memberCounts() {
		Map<String, Integer> counts = new HashMap<>();
		registry.roomsSnapshot().forEach((id, members) -> counts.put(id, members.size()));
		return counts;
	}

	private WebSocketSession connect() throws Exception {
		return new StandardWebSocketClient()
			.execute(new TextWebSocketHandler(), "ws://localhost:" + port + "/ws")
			.get(5, TimeUnit.SECONDS);
	}

	// 이유: 서버는 클라이언트의 send·close와 다른 스레드에서 비동기로 처리한다. 반환 직후 바로 확인하면
	// 아직 처리 전일 수 있어 제한 시간 안에서 조건이 될 때까지 기다린다 (WebSocketConnectionTest와 같은 방식).
	private void await(Supplier<Boolean> condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(condition.get())
			.as("rooms=%s, roomsBySession=%s", registry.roomsSnapshot(), registry.roomsBySessionSnapshot())
			.isTrue();
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] JOIN·LEAVE, JOIN 후 정상 종료 (2026-10-24)
 *   - 핸들러 연결 전 실행: 두 테스트 모두 실패. rooms={}, roomsBySession={} → JOIN이 저장소에 닿지 않음
 *   - ChatWebSocketHandler에 handleTextMessage(파서 → RoomRegistry)와 afterConnectionClosed의 removeSession 추가 후:
 *     두 테스트 모두 통과 (전체 테스트 42개 통과)
 */
