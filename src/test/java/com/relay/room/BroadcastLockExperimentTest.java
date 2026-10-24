package com.relay.room;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.socket.WebSocketSession;

/**
 * EXP-02: 방 락의 열쇠를 얻는 비용 — (가) 방 이름 해시로 배열 칸 고르기 vs (나) 방 객체를 열쇠로 쓰고 재확인.
 *
 * <p>사용자 요청 (P1-4 설계): "테스트에 이것도 꼭 기재해서 테스트해주세요".
 * 실행: {@code RELAY_EXPERIMENT=true ./gradlew test --tests 'com.relay.room.BroadcastLockExperimentTest'}
 * 결과는 {@code build/experiments/broadcast-lock.md}에 쓴다.
 *
 * <p>측정 범위: 브로드캐스트 한 번의 "열쇠 얻기 → 멤버 확인·복사"까지. 전송은 빼서 두 방식의 차이만 보이게 한다.
 * 해석 주의: 노트북 한 대, mock 세션, 반복 루프. 절대값이 아니라 두 방식의 상대 차이만 본다.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_EXPERIMENT", matches = "true")
class BroadcastLockExperimentTest {

	private static final int[] THREADS = { 1, 4, 16, 64 };
	private static final int ROUNDS = 3;
	private static final long WARMUP_MS = 300;
	private static final long MEASURE_MS = 1_000;
	private static final int RESIDENTS = 4;

	// 두 방식을 같은 코드로 측정하려고 감싼다. 반환값은 복사본(비멤버면 빈 집합).
	private interface KeyStrategy {
		Set<WebSocketSession> lockAndCopy(WebSocketSession from, String roomId);

		long retries();
	}

	// (가) 실제 코드와 같은 방식: 이름만으로 칸을 계산한다. 맵·전체 락을 보지 않는다.
	private static KeyStrategy striped(RoomRegistry registry) {
		RoomBroadcaster broadcaster = new RoomBroadcaster(registry, null);
		return new KeyStrategy() {
			public Set<WebSocketSession> lockAndCopy(WebSocketSession from, String roomId) {
				synchronized (broadcaster.lockFor(roomId)) {
					return registry.membersFor(from, roomId);
				}
			}

			public long retries() {
				return 0;
			}
		};
	}

	// (나) 설계 단계에서 비교한 방식: 방 객체(멤버 Set)를 열쇠로 쓴다.
	// 열쇠를 얻으려면 맵을 봐야 해서 전체 락이 먼저 필요하고, 방 락을 잡은 뒤 "맵의 방이 아직 그 객체인가"를 다시 확인한다.
	// 다르면(그사이 삭제·재생성) 처음부터 다시 (ADR-0008 대안 C와 같은 패턴).
	private static KeyStrategy roomObject(RoomRegistry registry) throws Exception {
		// 이유: 실험 하나 때문에 운영 코드에 방 객체를 꺼내는 메서드를 만들지 않으려고 리플렉션으로 내부 맵을 본다.
		// 맵 접근은 RoomRegistry와 같은 락(registry 자신)을 잡고 한다.
		Field field = RoomRegistry.class.getDeclaredField("rooms");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Set<WebSocketSession>> rooms = (Map<String, Set<WebSocketSession>>) field.get(registry);
		LongAdder retries = new LongAdder();
		return new KeyStrategy() {
			public Set<WebSocketSession> lockAndCopy(WebSocketSession from, String roomId) {
				while (true) {
					Set<WebSocketSession> room;
					synchronized (registry) {
						room = rooms.get(roomId);
					}
					if (room == null) {
						return Set.of();
					}
					synchronized (room) {
						synchronized (registry) {
							if (rooms.get(roomId) != room) {
								retries.increment();
								continue;
							}
							return room.contains(from) ? Set.copyOf(room) : Set.of();
						}
					}
				}
			}

			public long retries() {
				return retries.sum();
			}
		};
	}

	private record Impl(String name, StrategyFactory factory) {
	}

	@FunctionalInterface
	private interface StrategyFactory {
		KeyStrategy create(RoomRegistry registry) throws Exception;
	}

	private static final List<Impl> IMPLS = List.of(
			new Impl("(가) 해시 칸", BroadcastLockExperimentTest::striped),
			new Impl("(나) 방 객체 + 재확인", BroadcastLockExperimentTest::roomObject));

	// 측정 조건. churn = 매 반복 JOIN → (측정 구간) → LEAVE로 방이 생기고 지워짐.
	private record Condition(String name, boolean sameRoom, boolean churn) {
	}

	private static final List<Condition> CONDITIONS = List.of(
			new Condition("① 서로 다른 방 — 방마다 상주 멤버 " + RESIDENTS + "명, 방 유지", false, false),
			new Condition("② 같은 방 하나 — 모든 스레드가 같은 방", true, false),
			new Condition("③ 서로 다른 방 — 혼자라 매 반복 방 생성·삭제", false, true));

	@Test
	void 실험() throws Exception {
		StringBuilder out = new StringBuilder("# EXP-02 열쇠 얻기 비용 — 실행 결과\n\n");
		out.append("- 코어: ").append(Runtime.getRuntime().availableProcessors())
			.append(", Java ").append(System.getProperty("java.version")).append("\n")
			.append("- 1건 = 브로드캐스트 한 번의 \"방 락 얻기 → 멤버 확인·복사\" (전송 제외). ")
			.append(ROUNDS).append("회 중 중앙값\n\n");

		// 이유: JIT 컴파일 전후로 속도가 크게 달라진다. 측정 전에 두 방식을 모두 한 번씩 돌려 둔다.
		for (Impl impl : IMPLS) {
			for (Condition c : CONDITIONS) {
				run(impl, c, 16, 300);
			}
		}

		for (Condition c : CONDITIONS) {
			out.append("## ").append(c.name()).append("\n\n");
			out.append("| 스레드 | (가) 해시 칸 (초당) | (나) 방 객체 + 재확인 (초당) | 비율 (나/가) | (가) ns/건 | (나) ns/건 | 재시도 (나, 1초당) |\n")
				.append("|---|---|---|---|---|---|---|\n");
			for (int threads : THREADS) {
				long[] a = new long[ROUNDS];
				long[] b = new long[ROUNDS];
				long[] r = new long[ROUNDS];
				for (int round = 0; round < ROUNDS; round++) {
					a[round] = run(IMPLS.get(0), c, threads, MEASURE_MS)[0];
					long[] res = run(IMPLS.get(1), c, threads, MEASURE_MS);
					b[round] = res[0];
					r[round] = res[1];
				}
				long ma = median(a);
				long mb = median(b);
				// ns/건은 스레드 하나가 한 건을 끝내는 데 걸린 평균 시간 (= 스레드 수 × 1초 / 전체 건수).
				out.append("| ").append(threads)
					.append(" | ").append(String.format("%,d", ma))
					.append(" | ").append(String.format("%,d", mb))
					.append(" | ").append(String.format("%.2f", (double) mb / ma))
					.append(" | ").append(String.format("%.0f", threads * 1e9 / ma))
					.append(" | ").append(String.format("%.0f", threads * 1e9 / mb))
					.append(" | ").append(String.format("%,d", median(r))).append(" |\n");
			}
			out.append("\n");
		}

		Path file = Path.of("build/experiments/broadcast-lock.md");
		Files.createDirectories(file.getParent());
		Files.writeString(file, out.toString());
	}

	// 반환: {초당 건수, 측정 구간 재시도 횟수}
	private long[] run(Impl impl, Condition c, int threads, long measureMs) throws Exception {
		RoomRegistry registry = new RoomRegistry();
		KeyStrategy strategy = impl.factory().create(registry);
		List<WebSocketSession> senders = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			WebSocketSession s = mock(WebSocketSession.class);
			senders.add(s);
			String roomId = roomOf(c, t);
			if (!c.churn()) {
				registry.join(s, roomId);
			}
		}
		if (!c.churn()) {
			// 이유: 복사 비용이 0명짜리가 되지 않게 방마다 상주 멤버를 둔다. 같은 방 조건에서는 방이 하나라 한 번만 넣는다.
			int roomCount = c.sameRoom() ? 1 : threads;
			for (int room = 0; room < roomCount; room++) {
				for (int i = 0; i < RESIDENTS; i++) {
					registry.join(mock(WebSocketSession.class), "room-" + room);
				}
			}
		}

		AtomicBoolean measuring = new AtomicBoolean(false);
		AtomicBoolean stop = new AtomicBoolean(false);
		LongAdder count = new LongAdder();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			WebSocketSession s = senders.get(t);
			String roomId = roomOf(c, t);
			futures.add(executor.submit(() -> {
				start.await();
				long local = 0;
				while (!stop.get()) {
					if (c.churn()) {
						registry.join(s, roomId);
					}
					Set<WebSocketSession> copy = strategy.lockAndCopy(s, roomId);
					if (copy.isEmpty()) {
						throw new IllegalStateException("보낸 사람이 멤버인데 빈 복사본");
					}
					if (c.churn()) {
						registry.leave(s, roomId);
					}
					if (measuring.get()) {
						local++;
					}
				}
				count.add(local);
				return null;
			}));
		}
		start.countDown();
		Thread.sleep(WARMUP_MS);
		long retriesBefore = strategy.retries();
		measuring.set(true);
		long begin = System.nanoTime();
		Thread.sleep(measureMs);
		measuring.set(false);
		long elapsed = System.nanoTime() - begin;
		long retries = strategy.retries() - retriesBefore;
		stop.set(true);
		for (Future<?> f : futures) {
			f.get(10, TimeUnit.SECONDS);
		}
		executor.shutdown();
		return new long[] { count.sum() * 1_000_000_000L / elapsed, retries };
	}

	private static String roomOf(Condition c, int thread) {
		return c.sameRoom() ? "room-0" : "room-" + thread;
	}

	private static long median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] 조건 ①②③ × 스레드 1/4/16/64, 3회 중앙값 (2026-10-24, 2회 실행)
 *   - 결과: 비율 (나)/(가) 0.92~1.17, 실행마다 오르내려 일관된 차이 없음. 재시도는 모든 조건에서 0.
 *   - 자세한 표와 분석: docs/phases/phase-01-websocket/experiments/02-broadcast-lock-key.md
 */
