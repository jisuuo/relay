package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * P1-3 문제 문장: 마지막 1명의 LEAVE(빈 방 삭제) 직전에 다른 세션의 JOIN이 방을 쥐면 불변식 ①이 깨진다.
 * LEAVE와 끊김 처리 모두 "빈 방 확인 → 삭제" 2단계를 가지므로 두 경로를 각각 검사한다.
 *
 * <p>이 테스트는 방어 없는 구현에서 먼저 실패해야 의미가 있다 (SessionRegistryTest와 같은 원칙).
 */
class RoomRegistryConcurrencyTest {

	// 이유: 깨지는 틈("방을 꺼낸 뒤 · 추가하기 전")이 매우 좁다. 방 하나로 한 번 시도해서는 거의 겹치지 않으므로,
	// 시도마다 "1명만 있는 방"을 많이 만들어 두 스레드가 같은 순서로 훑게 해 겹칠 기회를 늘리고, 그 시도를 반복한다.
	private static final int ROOMS = 1_000;
	private static final int TRIALS = 200;

	@Test
	void 마지막_1명의_LEAVE와_JOIN이_겹쳐도_불변식이_지켜진다() throws Exception {
		assertNoViolation((registry, leavers, joiners) -> new Runnable[] {
				() -> {
					for (int i = 0; i < ROOMS; i++) {
						registry.leave(leavers.get(i), room(i));
					}
				},
				() -> {
					for (int i = 0; i < ROOMS; i++) {
						registry.join(joiners.get(i), room(i));
					}
				}
		});
	}

	@Test
	void 연결_끊김_정리와_JOIN이_겹쳐도_불변식이_지켜진다() throws Exception {
		assertNoViolation((registry, leavers, joiners) -> new Runnable[] {
				// 끊김 경로: 세션마다 removeSession. 각 세션은 자기 방 하나에만 있으므로 그 방이 빈 방이 되어 삭제된다.
				() -> leavers.forEach(registry::removeSession),
				() -> {
					for (int i = 0; i < ROOMS; i++) {
						registry.join(joiners.get(i), room(i));
					}
				}
		});
	}

	private interface Scenario {
		Runnable[] tasks(RoomRegistry registry, List<WebSocketSession> leavers, List<WebSocketSession> joiners);
	}

	private void assertNoViolation(Scenario scenario) throws Exception {
		// 이유: mock 생성은 join보다 훨씬 느리다. 출발 전에 만들어 두어야 출발 후 스레드들이 실제로 겹친다.
		List<WebSocketSession> leavers = mocks(ROOMS);
		List<WebSocketSession> joiners = mocks(ROOMS);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<String> violations = new ArrayList<>();
		try {
			for (int trial = 0; trial < TRIALS; trial++) {
				// 이유: 시도마다 "방 i에 leavers[i] 혼자 있는" 같은 출발 상태로 되돌린다.
				RoomRegistry registry = new RoomRegistry();
				for (int i = 0; i < ROOMS; i++) {
					registry.join(leavers.get(i), room(i));
				}

				// 이유: 두 스레드를 출발선에 세운 뒤 동시에 출발시킨다. 없으면 한쪽이 끝난 뒤 다른 쪽이 시작될 수 있다.
				CountDownLatch start = new CountDownLatch(1);
				List<Future<?>> futures = new ArrayList<>();
				for (Runnable task : scenario.tasks(registry, leavers, joiners)) {
					futures.add(executor.submit(() -> {
						start.await();
						task.run();
						return null;
					}));
				}
				start.countDown();
				// 이유: 작업 중 예외(NPE 등)도 실패로 드러나게 get()으로 꺼낸다.
				for (Future<?> f : futures) {
					f.get(10, TimeUnit.SECONDS);
				}

				List<String> found = invariantViolations(registry.roomsSnapshot(), registry.roomsBySessionSnapshot());
				if (!found.isEmpty()) {
					violations.add("시도 " + trial + ": 위반 " + found.size() + "건, 예) " + found.get(0));
				}
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(violations).as("불변식 위반이 생긴 시도 (%d회 중 %d회)", TRIALS, violations.size()).isEmpty();
	}

	// 이유: ①은 양방향으로 훑어야 한다. 이 경쟁에서는 맵에 방이 없어 "방 → 세션" 방향은 검사할 게 없고,
	// "세션 → 방" 방향(T의 목록에 A가 있는데 맵에 A가 없음)에서만 드러난다.
	// RoomLockExperimentTest도 같은 검사를 쓴다.
	static List<String> invariantViolations(Map<String, Set<WebSocketSession>> rooms,
			Map<WebSocketSession, Set<String>> roomsBySession) {
		List<String> found = new ArrayList<>();
		rooms.forEach((roomId, members) -> {
			if (members.isEmpty()) {
				found.add("② 빈 방 " + roomId);
			}
			for (WebSocketSession member : members) {
				if (!roomsBySession.getOrDefault(member, Set.of()).contains(roomId)) {
					found.add("① 방 " + roomId + "에는 있는데 세션 목록에 " + roomId + " 없음");
				}
			}
		});
		roomsBySession.forEach((session, joined) -> {
			if (joined.isEmpty()) {
				found.add("② 빈 세션 항목");
			}
			for (String roomId : joined) {
				if (!rooms.getOrDefault(roomId, Set.of()).contains(session)) {
					found.add("① 세션 목록에는 " + roomId + "이 있는데 방 " + roomId + "에 세션 없음");
				}
			}
		});
		return found;
	}

	private static String room(int i) {
		return "room-" + i;
	}

	private static List<WebSocketSession> mocks(int count) {
		List<WebSocketSession> sessions = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			sessions.add(mock(WebSocketSession.class));
		}
		return sessions;
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] 방 1,000개 × 시도 200회, 스레드 2개(LEAVE 또는 끊김 / JOIN), CountDownLatch 출발 신호 (2026-10-24)
 *   - 방어 없는 RoomRegistry(시도 1): 11회 실행 모두 두 테스트 실패. 테스트별로 나눠 확인한 5회에서 위반 시도는
 *     LEAVE 경로 22~36회/200, 끊김 경로 20~45회/200. 위반은 모두 "① 세션 목록에는 방이 있는데 맵의 방에 세션 없음"
 *     → 정의 단계에서 예측한 방향(세션 → 방)에서만 드러남
 *   - 전체 락 RoomRegistry(시도 2): 5회 실행 모두 통과
 *   - 판단: 테스트를 바꾸지 않고 구현만 바꿨을 때 실패 → 통과로 바뀌었으므로, 이 테스트는 두 구현을 구분한다.
 *     다만 통과는 "이 조건에서 겹침을 찾지 못했다"는 뜻이지 안전 증명은 아니다. 근거는 락 범위에 대한 추론이다.
 */
