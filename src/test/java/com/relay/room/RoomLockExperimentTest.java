package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.socket.WebSocketSession;

/**
 * 실험: 전체 락(RoomRegistry) vs 방마다 락 + 재확인(PerRoomLockRoomRegistry).
 *
 * <p>왜 환경변수로만 실행하는가: 수십 초 걸리는 측정이라 평소 {@code ./gradlew test}에 넣지 않는다.
 * 실행: {@code RELAY_EXPERIMENT=true ./gradlew test --tests 'com.relay.room.RoomLockExperimentTest'}
 * 결과는 {@code build/experiments/room-lock.md}에 쓴다.
 *
 * <p>해석 주의: 노트북 한 대, mock 세션, 반복 루프로 잰 값이다. 절대값이 아니라 두 방식의 상대 차이만 본다.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_EXPERIMENT", matches = "true")
class RoomLockExperimentTest {

	private static final int[] THREADS = { 1, 4, 16, 64 };
	private static final int ROUNDS = 3;
	private static final long WARMUP_MS = 300;
	private static final long MEASURE_MS = 1_000;

	private record Snapshot(Map<String, Set<WebSocketSession>> rooms, Map<WebSocketSession, Set<String>> bySession) {
	}

	// 두 구현을 같은 코드로 측정하려고 감싼다.
	private interface Ops {
		void join(WebSocketSession s, String roomId);

		void leave(WebSocketSession s, String roomId);

		void removeSession(WebSocketSession s);

		// 실행 도중 "두 매핑을 한 순간에" 보려는 최선의 방법 (비교 요소 ⑤)
		Snapshot snapshot();

		long retries();
	}

	private static Ops globalLock() {
		RoomRegistry r = new RoomRegistry();
		return new Ops() {
			public void join(WebSocketSession s, String id) { r.join(s, id); }
			public void leave(WebSocketSession s, String id) { r.leave(s, id); }
			public void removeSession(WebSocketSession s) { r.removeSession(s); }
			// 이유: 모든 메서드가 this 락을 쓰므로 같은 락을 잡으면 그동안 아무 변경도 일어나지 않는다.
			public Snapshot snapshot() {
				synchronized (r) {
					return new Snapshot(r.roomsSnapshot(), r.roomsBySessionSnapshot());
				}
			}
			public long retries() { return 0; }
		};
	}

	private static Ops perRoomLock() {
		PerRoomLockRoomRegistry r = new PerRoomLockRoomRegistry();
		return new Ops() {
			public void join(WebSocketSession s, String id) { r.join(s, id); }
			public void leave(WebSocketSession s, String id) { r.leave(s, id); }
			public void removeSession(WebSocketSession s) { r.removeSession(s); }
			// 이유: 전체를 멈출 락이 없다. 두 매핑을 차례로 복사하는 것이 할 수 있는 전부다.
			public Snapshot snapshot() { return new Snapshot(r.roomsSnapshot(), r.roomsBySessionSnapshot()); }
			public long retries() { return r.retries.sum(); }
		};
	}

	private record Impl(String name, java.util.function.Supplier<Ops> factory) {
	}

	private static final List<Impl> IMPLS = List.of(
			new Impl("전체 락", RoomLockExperimentTest::globalLock),
			new Impl("방마다 락 + 재확인", RoomLockExperimentTest::perRoomLock));

	@Test
	void 실험() throws Exception {
		StringBuilder out = new StringBuilder("# 전체 락 vs 방마다 락 + 재확인 — 실행 결과\n\n");
		out.append("- 코어: ").append(Runtime.getRuntime().availableProcessors())
			.append(", Java ").append(System.getProperty("java.version")).append("\n\n");

		correctness(out);
		throughput(out);
		consistency(out);

		Path file = Path.of("build/experiments/room-lock.md");
		Files.createDirectories(file.getParent());
		Files.writeString(file, out.toString());
	}

	// ── ① 정확성: RoomRegistryConcurrencyTest와 같은 두 경쟁 시나리오 ──
	private void correctness(StringBuilder out) throws Exception {
		out.append("## ① 정확성 (방 1,000개 × 시도 200회, 실행이 끝난 뒤 불변식 검사)\n\n");
		out.append("| 구현 | LEAVE vs JOIN 위반 시도 | 끊김 vs JOIN 위반 시도 |\n|---|---|---|\n");
		for (Impl impl : IMPLS) {
			int leaveViolations = raceTrials(impl, false);
			int disconnectViolations = raceTrials(impl, true);
			out.append("| ").append(impl.name()).append(" | ").append(leaveViolations).append("/200 | ")
				.append(disconnectViolations).append("/200 |\n");
			assertThat(leaveViolations + disconnectViolations).isZero();
		}
		out.append("\n");
	}

	private int raceTrials(Impl impl, boolean disconnect) throws Exception {
		int rooms = 1_000;
		List<WebSocketSession> leavers = mocks(rooms);
		List<WebSocketSession> joiners = mocks(rooms);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		int violated = 0;
		try {
			for (int trial = 0; trial < 200; trial++) {
				Ops ops = impl.factory().get();
				for (int i = 0; i < rooms; i++) {
					ops.join(leavers.get(i), "room-" + i);
				}
				CountDownLatch start = new CountDownLatch(1);
				Runnable leaveSide = disconnect
						? () -> leavers.forEach(ops::removeSession)
						: () -> {
							for (int i = 0; i < rooms; i++) {
								ops.leave(leavers.get(i), "room-" + i);
							}
						};
				Runnable joinSide = () -> {
					for (int i = 0; i < rooms; i++) {
						ops.join(joiners.get(i), "room-" + i);
					}
				};
				List<Future<?>> futures = new ArrayList<>();
				for (Runnable task : List.of(leaveSide, joinSide)) {
					futures.add(executor.submit(() -> {
						start.await();
						task.run();
						return null;
					}));
				}
				start.countDown();
				for (Future<?> f : futures) {
					f.get(10, TimeUnit.SECONDS);
				}
				Snapshot snap = ops.snapshot();
				if (!RoomRegistryConcurrencyTest.invariantViolations(snap.rooms(), snap.bySession()).isEmpty()) {
					violated++;
				}
			}
		}
		finally {
			executor.shutdownNow();
		}
		return violated;
	}

	// ── ②③④ 처리량: 스레드마다 JOIN → LEAVE를 정해진 시간 동안 반복 ──
	private void throughput(StringBuilder out) throws Exception {
		out.append("## ②③④ 처리량 (JOIN·LEAVE 1회씩을 1건으로 셈, 초당 건수, ")
			.append(ROUNDS).append("회 중 중앙값)\n\n");
		// 이유: JIT 컴파일 전후로 속도가 크게 달라진다. 측정 전에 두 구현을 모두 한 번씩 돌려 둔다.
		for (Impl impl : IMPLS) {
			run(impl.factory().get(), 16, i -> "room-" + i, 500, false);
		}
		// 이유: (가)에서는 스레드가 방에 혼자라 LEAVE마다 방이 삭제되고 다음 JOIN에서 다시 만들어진다.
		// 락 범위의 효과와 방 생성·삭제 비용을 나눠 보려고, 방마다 상주 세션 1명을 두어 방이 삭제되지 않는 조건을 추가한다.
		record Distribution(String name, IntFunction<String> roomOf, boolean resident) {
		}
		List<Distribution> distributions = List.of(
				new Distribution("(가) 서로 다른 방 — 혼자라 LEAVE마다 방 삭제·재생성", i -> "room-" + i, false),
				new Distribution("(가') 서로 다른 방 — 상주 세션이 있어 방이 삭제되지 않음", i -> "room-" + i, true),
				new Distribution("(나) 같은 방 하나", i -> "room-0", false));
		for (Distribution dist : distributions) {
			out.append("### ").append(dist.name()).append("\n\n");
			out.append("| 스레드 | 전체 락 | 방마다 락 + 재확인 | 비율 (방마다/전체) | 재시도 (방마다, 1회 측정당) |\n|---|---|---|---|---|\n");
			for (int threads : THREADS) {
				long[] global = new long[ROUNDS];
				long[] perRoom = new long[ROUNDS];
				long[] retries = new long[ROUNDS];
				for (int round = 0; round < ROUNDS; round++) {
					global[round] = run(globalLock(), threads, dist.roomOf(), MEASURE_MS, dist.resident())[0];
					long[] r = run(perRoomLock(), threads, dist.roomOf(), MEASURE_MS, dist.resident());
					perRoom[round] = r[0];
					retries[round] = r[1];
				}
				long g = median(global);
				long p = median(perRoom);
				out.append("| ").append(threads).append(" | ").append(String.format("%,d", g)).append(" | ")
					.append(String.format("%,d", p)).append(" | ").append(String.format("%.2f", (double) p / g))
					.append(" | ").append(String.format("%,d", median(retries))).append(" |\n");
			}
			out.append("\n");
		}
	}

	// 반환: {초당 건수, 재시도 횟수}
	private long[] run(Ops ops, int threads, IntFunction<String> roomOf, long measureMs, boolean resident)
			throws Exception {
		List<WebSocketSession> sessions = mocks(threads);
		if (resident) {
			for (int t = 0; t < threads; t++) {
				ops.join(mock(WebSocketSession.class), roomOf.apply(t));
			}
		}
		AtomicBoolean measuring = new AtomicBoolean(false);
		AtomicBoolean stop = new AtomicBoolean(false);
		LongAdder count = new LongAdder();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			WebSocketSession s = sessions.get(t);
			String roomId = roomOf.apply(t);
			futures.add(executor.submit(() -> {
				start.await();
				long local = 0;
				while (!stop.get()) {
					ops.join(s, roomId);
					ops.leave(s, roomId);
					if (measuring.get()) {
						local += 2;
					}
				}
				count.add(local);
				return null;
			}));
		}
		start.countDown();
		Thread.sleep(WARMUP_MS);
		long retriesBefore = ops.retries();
		measuring.set(true);
		long begin = System.nanoTime();
		Thread.sleep(measureMs);
		measuring.set(false);
		long elapsed = System.nanoTime() - begin;
		long retries = ops.retries() - retriesBefore;
		stop.set(true);
		for (Future<?> f : futures) {
			f.get(10, TimeUnit.SECONDS);
		}
		executor.shutdown();
		return new long[] { count.sum() * 1_000_000_000L / elapsed, retries };
	}

	// ── ⑤ 실행 도중 일관된 모습: 작업이 도는 동안 다른 스레드가 스냅샷을 찍어 불변식 검사 ──
	private void consistency(StringBuilder out) throws Exception {
		int threads = 16;
		out.append("## ⑤ 실행 도중 스냅샷의 불변식 (스레드 ").append(threads)
			.append("개가 (가) 서로 다른 방에서 JOIN → LEAVE 반복, 1초 동안 관찰 스레드가 스냅샷 반복)\n\n");
		out.append("| 구현 | 찍은 스냅샷 | 불변식 위반 스냅샷 | 위반 예 |\n|---|---|---|---|\n");
		for (Impl impl : IMPLS) {
			Ops ops = impl.factory().get();
			List<WebSocketSession> sessions = mocks(threads);
			AtomicBoolean stop = new AtomicBoolean(false);
			ExecutorService executor = Executors.newFixedThreadPool(threads);
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				WebSocketSession s = sessions.get(t);
				String roomId = "room-" + t;
				futures.add(executor.submit(() -> {
					while (!stop.get()) {
						ops.join(s, roomId);
						ops.leave(s, roomId);
					}
					return null;
				}));
			}
			long snapshots = 0;
			long violated = 0;
			String example = "-";
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000);
			while (System.nanoTime() < deadline) {
				Snapshot snap = ops.snapshot();
				List<String> found = RoomRegistryConcurrencyTest.invariantViolations(snap.rooms(), snap.bySession());
				snapshots++;
				if (!found.isEmpty()) {
					violated++;
					example = found.get(0);
				}
			}
			stop.set(true);
			for (Future<?> f : futures) {
				f.get(10, TimeUnit.SECONDS);
			}
			executor.shutdown();
			out.append("| ").append(impl.name()).append(" | ").append(String.format("%,d", snapshots)).append(" | ")
				.append(String.format("%,d", violated)).append(" | ").append(example).append(" |\n");
		}
		out.append("\n");
	}

	private static long median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	private static List<WebSocketSession> mocks(int count) {
		List<WebSocketSession> sessions = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			sessions.add(mock(WebSocketSession.class));
		}
		return sessions;
	}

}
