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
 * P1-5 시나리오 3·4·5: 잘못된 입력에 오류 응답을 보내고 연결은 유지한다 (ADR-0011).
 *
 * <p>왜 실제 연결인가: 고치기 전 문제(1011로 끊김, 아무 응답 없음)는 Spring의 예외 처리 포장지와 실제 전송에서 드러났다
 * (실험 4·5). 핸들러 단위로는 그 경로를 볼 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvalidInputWebSocketTest {

	private static final JsonMapper JSON = new JsonMapper();

	@LocalServerPort
	int port;

	@Test
	void 깨진_JSON은_INVALID_MESSAGE_오류를_받고_연결이_유지된다() throws Exception {
		Client b = connect();
		b.send("{\"type\":\"JOIN\",\"roomId\":\"A\"}");

		b.send("{\"type\":\"SEND\", \"roomId\":");

		JsonNode error = b.await(m -> type(m, "ERROR"));
		assertThat(error.get("code").asString()).isEqualTo("INVALID_MESSAGE");
		assertThat(error.get("message").asString()).isNotBlank();
		// 이유: 원인이 내용이라 방·요청 종류는 담지 않기로 했다 (ADR-0011).
		assertThat(error.has("roomId")).isFalse();
		assertThat(error.has("requestType")).isFalse();

		// 실험 4에서는 여기서 이미 1011로 닫혀 이후 메시지가 버려졌다.
		b.send("{\"type\":\"SEND\",\"roomId\":\"A\",\"content\":\"이어서\"}");
		b.await(m -> isMessage(m, "이어서"));
		assertThat(b.session.isOpen()).isTrue();

		b.session.close();
	}

	@Test
	void 비멤버_SEND는_NOT_MEMBER_오류를_받고_그_방_멤버는_받지_않는다() throws Exception {
		Client b = connect();
		Client e = connect();
		e.send("{\"type\":\"JOIN\",\"roomId\":\"X\"}");
		awaitJoined(e, "X");

		b.send("{\"type\":\"SEND\",\"roomId\":\"X\",\"content\":\"몰래\"}");

		JsonNode error = b.await(m -> type(m, "ERROR"));
		assertThat(error.get("code").asString()).isEqualTo("NOT_MEMBER");
		assertThat(error.get("requestType").asString()).isEqualTo("SEND");
		assertThat(error.get("roomId").asString()).isEqualTo("X");
		assertThat(error.get("message").asString()).isNotBlank();

		// 이유: "안 왔다"는 기다림만으로 확정할 수 없다. B의 메시지는 같은 연결이라 서버가 차례로 처리하므로,
		// "몰래"가 퍼졌다면 E에게 "울타리"보다 먼저 쓰였을 것이다 (ChatBroadcastWebSocketTest와 같은 방법).
		b.send("{\"type\":\"JOIN\",\"roomId\":\"X\"}");
		b.send("{\"type\":\"SEND\",\"roomId\":\"X\",\"content\":\"울타리\"}");
		e.await(m -> isMessage(m, "울타리"));
		assertThat(e.received).noneMatch(m -> isMessage(m, "몰래"));
		assertThat(b.session.isOpen()).isTrue();

		b.session.close();
		e.session.close();
	}

	@Test
	void 비멤버_LEAVE는_NOT_MEMBER_오류를_받는다() throws Exception {
		Client b = connect();

		b.send("{\"type\":\"LEAVE\",\"roomId\":\"X\"}");

		JsonNode error = b.await(m -> type(m, "ERROR"));
		assertThat(error.get("code").asString()).isEqualTo("NOT_MEMBER");
		assertThat(error.get("requestType").asString()).isEqualTo("LEAVE");
		assertThat(error.get("roomId").asString()).isEqualTo("X");
		assertThat(b.session.isOpen()).isTrue();

		b.session.close();
	}

	private void awaitJoined(Client client, String roomId) throws Exception {
		// 이유: JOIN에는 응답이 없어, 그 방에 직접 보내 자기 메시지가 돌아오는지로 입장을 확인한다.
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
			throw new AssertionError("5초 안에 조건에 맞는 메시지가 오지 않음. 받은 메시지=" + received
					+ ", open=" + session.isOpen());
		}

	}

}
