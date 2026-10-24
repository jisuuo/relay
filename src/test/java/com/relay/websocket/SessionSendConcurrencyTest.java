package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * P1-4 시나리오 1 (문제 ②): 여러 스레드가 같은 서버 쪽 세션에 동시에 보내면 클라이언트가 메시지를 온전히 받는가.
 *
 * <p>왜 실제 연결인가: 기록용 가짜 세션은 리스트에 추가만 하므로 소켓에 바이트를 쓰는 단계가 없어 섞임이 재현되지 않는다.
 * 실제 Tomcat 세션에 보내고 실제 클라이언트가 받아야 한다.
 *
 * <p>왜 테스트 전용 엔드포인트인가: 서버 쪽 세션 객체를 테스트가 직접 쥐어야 여러 스레드로 동시에 보낼 수 있다.
 * 운영 핸들러에 세션을 꺼내는 통로를 만들지 않으려고, 이 테스트 컨텍스트에만 세션을 넘겨주는 핸들러를 등록한다.
 *
 * <p>증상을 미리 모르므로(섞임 / 예외 / 빠짐) 서버 쪽 예외, 받은 개수, 형식, 중복·누락을 모두 기록한다.
 * 이 테스트는 방어 없는 구현(세션에 직접 sendMessage)에서 먼저 실패해야 의미가 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionSendConcurrencyTest {

	// 이유: 겹칠 기회를 늘리려고 스레드 여러 개가 각자 여러 번 보낸다. 메시지를 1KB 정도로 키워
	// 한 번 쓰는 시간이 길어지게 한다 (짧으면 앞의 쓰기가 끝난 뒤 다음 쓰기가 시작돼 겹치지 않는다).
	private static final int THREADS = 8;
	private static final int PER_THREAD = 200;
	private static final int TOTAL = THREADS * PER_THREAD;
	private static final String PAD = "x".repeat(1_000);
	private static final Pattern WELL_FORMED = Pattern.compile("^\\{\"n\":(\\d+),\"pad\":\"x{1000}\"}$");

	@LocalServerPort
	int port;

	// 서버 쪽 세션 하나에 메시지 하나를 보내는 방법. 방어 없는 구현과 SessionSender를 같은 테스트로 비교하려고 분리한다.
	@FunctionalInterface
	interface Sender {
		void send(WebSocketSession session, TextMessage message) throws IOException;
	}

	@Test
	void 같은_세션에_여러_스레드가_동시에_보내도_클라이언트는_모든_메시지를_온전히_받는다() throws Exception {
		Result result = sendConcurrently(WebSocketSession::sendMessage);

		assertThat(result.isClean()).as(result.toString()).isTrue();
	}

	private Result sendConcurrently(Sender sender) throws Exception {
		Queue<String> received = new ConcurrentLinkedQueue<>();
		CompletableFuture<CloseStatus> clientClosed = new CompletableFuture<>();
		WebSocketSession client = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				received.add(message.getPayload());
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
				clientClosed.complete(status);
			}
		}, "ws://localhost:" + port + "/ws-send-test").get(5, TimeUnit.SECONDS);

		WebSocketSession server = TestEndpoint.serverSession.get(5, TimeUnit.SECONDS);

		Queue<String> exceptions = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		try {
			// 이유: 모든 스레드를 출발선에 세운 뒤 동시에 출발시킨다. 없으면 먼저 만든 스레드가 다 보낸 뒤 다음 스레드가 시작될 수 있다.
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				int base = t * PER_THREAD;
				futures.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < PER_THREAD; i++) {
						int n = base + i;
						try {
							sender.send(server, new TextMessage("{\"n\":" + n + ",\"pad\":\"" + PAD + "\"}"));
						}
						catch (Exception e) {
							// 이유: 예외로 루프를 끝내면 이후 메시지는 시도조차 안 된다. 모든 시도의 결과를 기록하려고 삼키고 계속한다.
							exceptions.add(e.getClass().getSimpleName() + ": " + e.getMessage());
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		// 이유: 서버가 다 보낸 뒤에도 클라이언트는 다른 스레드에서 비동기로 받는다. 받은 개수가 더 늘지 않을 때까지 기다린다.
		awaitStable(received);
		Result result = new Result(received, exceptions, server.isOpen(), clientClosed.getNow(null));
		if (client.isOpen()) {
			client.close();
		}
		return result;
	}

	private void awaitStable(Queue<String> received) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		int last = -1;
		while (System.nanoTime() < deadline) {
			int now = received.size();
			if (now == TOTAL || now == last) {
				return;
			}
			last = now;
			Thread.sleep(200);
		}
	}

	record Result(Queue<String> received, Queue<String> exceptions, boolean serverOpen, CloseStatus clientClosed) {

		boolean isClean() {
			return exceptions.isEmpty() && received.size() == TOTAL && malformed() == 0 && duplicates() == 0;
		}

		long malformed() {
			return received.stream().filter(p -> !WELL_FORMED.matcher(p).matches()).count();
		}

		int duplicates() {
			Set<Integer> seen = new HashSet<>();
			int duplicates = 0;
			for (String payload : received) {
				Matcher m = WELL_FORMED.matcher(payload);
				if (m.matches() && !seen.add(Integer.parseInt(m.group(1)))) {
					duplicates++;
				}
			}
			return duplicates;
		}

		// 같은 예외 메시지끼리 묶어 종류별 개수를 보여 준다.
		Map<String, Long> exceptionKinds() {
			Map<String, Long> kinds = new TreeMap<>();
			exceptions.forEach(e -> kinds.merge(e, 1L, Long::sum));
			return kinds;
		}

		@Override
		public String toString() {
			String sample = received.stream()
				.filter(p -> !WELL_FORMED.matcher(p).matches())
				.findFirst()
				.map(p -> p.length() > 120 ? p.substring(0, 120) + "..." : p)
				.orElse("-");
			return "보낸 시도=" + TOTAL + ", 서버 예외=" + exceptions.size() + " " + exceptionKinds()
					+ ", 클라이언트 수신=" + received.size() + ", 형식 깨짐=" + malformed() + ", 중복=" + duplicates()
					+ ", 서버 세션 open=" + serverOpen + ", 클라이언트 종료 상태=" + clientClosed
					+ ", 깨진 메시지 예=" + sample;
		}

	}

	// 이 테스트 컨텍스트에만 등록되는 엔드포인트. 연결된 서버 쪽 세션을 테스트에 넘겨준다.
	@TestConfiguration
	static class TestEndpoint implements WebSocketConfigurer {

		static final CompletableFuture<WebSocketSession> serverSession = new CompletableFuture<>();

		@Override
		public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
			registry.addHandler(new TextWebSocketHandler() {
				@Override
				public void afterConnectionEstablished(WebSocketSession session) {
					serverSession.complete(session);
				}
			}, "/ws-send-test");
		}

	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] 방어 없음: 서버 쪽 세션에 직접 sendMessage (2026-10-24)
 *   - 의도: 문제 ② "같은 세션 동시 sendMessage → 섞이거나 덮어씌워진다"(예상)를 재현
 *   - 결과: 5회 실행 모두 실패. 1,600번 시도 중 서버 예외 1,475~1,564번, 클라이언트 수신 59~125개.
 *           예외 + 수신 = 1,600으로 항상 맞음. 형식 깨짐 0, 중복 0. 서버 세션은 계속 open, 연결도 끊기지 않음.
 *           예외는 모두 IllegalStateException: "The remote endpoint was in state [TEXT_PARTIAL_WRITING]
 *           which is an invalid state for called method"
 *   - 예상과 다른 점: 섞이지 않았다. Tomcat이 "쓰는 중" 상태를 보고 다른 스레드의 쓰기를 예외로 거절한다.
 *           증상은 섞임이 아니라 거절된 메시지의 유실이고, 보낸 스레드는 예외를 받는다.
 */
