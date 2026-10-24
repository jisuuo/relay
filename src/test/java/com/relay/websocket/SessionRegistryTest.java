package com.relay.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * P1-1 테스트 ①: 동시 등록.
 *
 * <p>이 테스트는 HashSet 저장소에서 먼저 실패해야 의미가 있다.
 * 스레드 안전한 구현과 그렇지 않은 구현 모두에서 통과하면 둘을 구분하지 못하므로 아무것도 검증하지 않는다.
 */
class SessionRegistryTest {

	// 이유: 경쟁 조건은 확률적으로 발생한다. 스레드 수와 스레드당 작업량을 키워 단계가 겹칠 기회를 늘린다.
	// 또한 원소가 많아야 HashSet 내부의 resize가 여러 번 일어나 유실이 생길 구간이 많아진다.
	private static final int THREADS = 100;
	private static final int SESSIONS_PER_THREAD = 100;

	@Test
	void 여러_스레드가_동시에_등록해도_세션이_사라지지_않는다() throws InterruptedException {
		SessionRegistry registry = new SessionRegistry();

		// 이유: 세션(mock) 생성은 add보다 훨씬 느리다. 출발 전에 미리 만들어 두어야
		// 출발 후에는 스레드들이 add만 하며 실제로 겹친다.
		// mock은 객체마다 서로 다른 identity hashCode를 가지므로 Set에서 각각 다른 원소로 취급된다.
		List<List<WebSocketSession>> sessionsByThread = new ArrayList<>();
		for (int t = 0; t < THREADS; t++) {
			List<WebSocketSession> sessions = new ArrayList<>();
			for (int i = 0; i < SESSIONS_PER_THREAD; i++) {
				sessions.add(mock(WebSocketSession.class));
			}
			sessionsByThread.add(sessions);
		}

		// 이유: 스레드는 생성·시작에 시간이 걸린다. 출발 신호 없이 시작하면 앞 스레드가 끝난 뒤
		// 다음 스레드가 출발해 사실상 순차 실행이 되므로, 모두 출발선에서 기다리게 한 뒤 동시에 출발시킨다.
		CountDownLatch startSignal = new CountDownLatch(1);
		// 이유: 모든 스레드가 끝나기 전에 size를 세면 아직 넣지 않은 세션까지 빠져 잘못된 실패가 난다.
		CountDownLatch done = new CountDownLatch(THREADS);
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		for (List<WebSocketSession> sessions : sessionsByThread) {
			executor.submit(() -> {
				try {
					startSignal.await();
					sessions.forEach(registry::add);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					// 이유: add 중 예외가 나도 done을 줄여야 메인 스레드가 무한히 기다리지 않는다.
					done.countDown();
				}
			});
		}

		startSignal.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		executor.shutdown();

		assertThat(registry.size()).isEqualTo(THREADS * SESSIONS_PER_THREAD);
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] 100 스레드 × 100 세션, CountDownLatch 출발 신호 (2026-10-24)
 *   - HashSet 저장소(SessionRegistry 시도 1): 10회 모두 실패, 확인한 6회는 6611~8546개만 남음 → 재현 성공
 *   - ConcurrentHashMap 저장소(시도 2): 10회 모두 통과
 *   - 판단: 테스트를 바꾸지 않고 구현만 바꿨을 때 실패 → 통과로 바뀌었으므로, 이 테스트는 두 구현을 구분한다.
 */
