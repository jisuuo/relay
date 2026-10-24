package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * P1-1 테스트 ②·③: 실제 WebSocket 클라이언트로 연결·종료했을 때 저장소가 바뀌는지 확인한다.
 *
 * <p>이유: 저장소 단위 테스트(①)만으로는 핸들러가 연결·종료 시점에 저장소를 실제로 호출하는지 알 수 없다.
 * 서버를 실제 포트로 띄우고 handshake부터 close frame까지 진짜 프로토콜을 거쳐야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketConnectionTest {

	@LocalServerPort
	int port;

	@Autowired
	SessionRegistry registry;

	@Test
	void 클라이언트가_연결하면_세션이_등록된다() throws Exception {
		WebSocketSession client = connect();

		awaitSize(1);

		// 이유: 같은 컨텍스트(저장소)를 다른 테스트와 공유하므로 남은 연결이 다음 테스트 결과를 오염시키지 않게 정리한다.
		client.close();
		awaitSize(0);
	}

	@Test
	void 클라이언트가_정상_종료하면_세션이_제거된다() throws Exception {
		WebSocketSession client = connect();
		awaitSize(1);

		// close()는 클라이언트가 close frame을 보내는 정상 종료다. 비정상 단절은 P1-5에서 다룬다.
		client.close(CloseStatus.NORMAL);

		awaitSize(0);
	}

	private WebSocketSession connect() throws Exception {
		return new StandardWebSocketClient()
			.execute(new TextWebSocketHandler(), "ws://localhost:" + port + "/ws")
			.get(5, TimeUnit.SECONDS);
	}

	// 이유: 서버의 afterConnectionEstablished/afterConnectionClosed는 클라이언트 쪽 호출과 다른 스레드에서
	// 비동기로 실행된다. 클라이언트의 connect·close가 반환된 직후 바로 확인하면 서버가 아직 처리하지 않았을 수 있어
	// 제한 시간 안에서 기대값이 될 때까지 기다린다.
	private void awaitSize(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (registry.size() != expected && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(registry.size()).isEqualTo(expected);
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] 연결 시 등록(②), 정상 종료 시 제거(③) (2026-10-24)
 *   - 구현 전 실행: 두 테스트 모두 실패. "HTTP response from the server [404] did not permit the HTTP upgrade
 *     to WebSocket" → /ws 엔드포인트가 없어 handshake에서 101 대신 404가 돌아옴
 *   - ChatWebSocketHandler, WebSocketConfig 구현 후 실행: 두 테스트 모두 통과 (전체 테스트 4개 통과)
 */
