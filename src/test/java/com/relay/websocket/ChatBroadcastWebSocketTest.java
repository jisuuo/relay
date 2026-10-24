package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P1-4 시나리오 6: 실제 클라이언트끼리 연결 → CONNECTED → JOIN → SEND 전체 흐름.
 *
 * <p>이유: RoomBroadcasterTest는 가짜 세션으로 브로드캐스트만 본다. 핸들러가 SEND를 브로드캐스트로 넘기는지,
 * 서버 메시지가 약속한 JSON 형식(ADR-0010)으로 나가는지는 실제 연결로만 확인할 수 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatBroadcastWebSocketTest {

	private static final JsonMapper JSON = new JsonMapper();

	@LocalServerPort
	int port;

	@Test
	void 연결하면_첫_메시지로_자기_sessionId를_받는다() throws Exception {
		Client a = connect();

		JsonNode first = a.await(m -> true);
		assertThat(first.get("type").asString()).isEqualTo("CONNECTED");
		assertThat(first.get("sessionId").asString()).isNotBlank();

		a.session.close();
	}

	@Test
	void 같은_방_멤버는_본인을_포함해_받고_다른_방은_받지_않는다() throws Exception {
		Client a = connect();
		Client b = connect();
		Client c = connect();
		String aId = a.await(m -> type(m, "CONNECTED")).get("sessionId").asString();
		a.send("{\"type\":\"JOIN\",\"roomId\":\"R\"}");
		b.send("{\"type\":\"JOIN\",\"roomId\":\"R\"}");
		c.send("{\"type\":\"JOIN\",\"roomId\":\"Q\"}");
		// 이유: A는 Q에도 들어가 "울타리" 메시지를 보낸다 (아래 참고).
		a.send("{\"type\":\"JOIN\",\"roomId\":\"Q\"}");
		// 이유: JOIN에는 응답이 없어 처리됐는지 알 수 없다. 같은 연결의 메시지는 서버가 차례로 처리하므로
		// 클라이언트마다 JOIN 다음 메시지를 보내는 시점에는 앞의 JOIN이 처리된 뒤다. B의 JOIN만 다른 연결이라 기다린다.
		awaitJoined(b, "R");

		a.send("{\"type\":\"SEND\",\"roomId\":\"R\",\"content\":\"안녕\"}");

		// 이유: B·A는 awaitJoined의 "join-check"(B가 보낸 MESSAGE)도 받았으므로 내용으로 골라야 한다.
		JsonNode toB = b.await(m -> isMessage(m, "안녕"));
		assertThat(toB.get("roomId").asString()).isEqualTo("R");
		assertThat(toB.get("sessionId").asString()).isEqualTo(aId);
		assertThat(toB.get("content").asString()).isEqualTo("안녕");
		JsonNode toA = a.await(m -> isMessage(m, "안녕"));
		assertThat(toA.get("sessionId").asString()).as("본인도 같은 메시지를 받는다").isEqualTo(aId);

		// 다른 방 격리: 실제 연결은 비동기라 "안 왔다"를 기다림만으로는 확정할 수 없다.
		// 이유: A의 메시지는 같은 연결이라 서버가 차례로 처리한다. R 브로드캐스트가 끝난 뒤에야 Q의 "울타리"를 보낸다.
		// 만약 C가 R의 메시지를 잘못 받는다면 C의 세션에는 "안녕"이 "울타리"보다 먼저 쓰인다.
		// 그래서 C가 "울타리"를 받은 시점에 "안녕"이 없으면 받지 않은 것으로 확정한다.
		a.send("{\"type\":\"SEND\",\"roomId\":\"Q\",\"content\":\"울타리\"}");
		c.await(m -> isMessage(m, "울타리"));
		assertThat(c.received).noneMatch(m -> isMessage(m, "안녕"));

		a.session.close();
		b.session.close();
		c.session.close();
	}

	private void awaitJoined(Client client, String roomId) throws Exception {
		// 이유: B가 R에 들어갔는지 확인하는 응답이 없으므로, B가 R에 직접 보내 자기 메시지가 돌아오는지로 확인한다.
		client.send("{\"type\":\"SEND\",\"roomId\":\"" + roomId + "\",\"content\":\"join-check\"}");
		client.await(m -> isMessage(m, "join-check"));
	}

	private static boolean type(JsonNode message, String type) {
		return type.equals(message.path("type").asString());
	}

	private static boolean isMessage(JsonNode message, String content) {
		return type(message, "MESSAGE") && content.equals(message.path("content").asString());
	}

	private Client connect() throws Exception {
		Client client = new Client();
		client.session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				client.received.add(JSON.readTree(message.getPayload()));
			}
		}, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
		return client;
	}

	static class Client {

		final List<JsonNode> received = new CopyOnWriteArrayList<>();
		WebSocketSession session;

		void send(String json) throws Exception {
			session.sendMessage(new TextMessage(json));
		}

		// 이유: 서버 메시지는 다른 스레드에서 비동기로 도착한다. 조건에 맞는 메시지가 올 때까지 제한 시간 안에서 기다린다.
		JsonNode await(Predicate<JsonNode> condition) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				for (JsonNode m : received) {
					if (condition.test(m)) {
						return m;
					}
				}
				Thread.sleep(10);
			}
			throw new AssertionError("5초 안에 조건에 맞는 메시지가 오지 않음. 받은 메시지=" + received);
		}

	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] CONNECTED 첫 메시지, 같은 방 수신(본인 포함), 다른 방 격리(울타리 메시지) (2026-10-24)
 *   - 구현 전 실행: 두 테스트 모두 실패. "5초 안에 조건에 맞는 메시지가 오지 않음. 받은 메시지=[]" → 서버가 아무것도 보내지 않음
 *   - ServerMessage·ServerMessageWriter 추가, 핸들러 연결 후: 첫 테스트 통과, 두 번째는 5회 모두 실패
 *     → 원인은 테스트: B의 JOIN 확인용 "join-check"도 MESSAGE라 "첫 MESSAGE"를 꺼내면 A의 "안녕" 대신 그것을 집음
 *       (sessionId가 B로 나옴). 내용으로 골라 꺼내도록 수정
 *   - 수정 후 5회 모두 통과. 전체 테스트 53개 통과
 *   - 격리 검사가 버그를 잡는지 확인: membersFor가 모든 방의 멤버를 돌려주게 일부러 바꾸자 두 번째 테스트 실패
 *     (C가 R의 "안녕"을 받음) → 원복
 */
