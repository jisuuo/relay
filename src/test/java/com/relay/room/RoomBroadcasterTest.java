package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.relay.websocket.SessionSender;

/**
 * P1-4 브로드캐스트 불변식 검사 (기록용 가짜 세션).
 *
 * <p>왜 가짜 세션인가: broadcast는 같은 스레드에서 모든 sendMessage를 부른 뒤 반환한다.
 * 가짜 세션이 sendMessage를 받는 즉시 기록하면, 반환 직후 "받지 않았다"를 기다림 없이 확정할 수 있다.
 * 실제 소켓의 동시 쓰기 문제는 SessionSendConcurrencyTest가 따로 다룬다.
 *
 * <p>시나리오 3·4는 방어 없는 구현에서 먼저 실패해야 의미가 있다.
 */
class RoomBroadcasterTest {

	private final RoomRegistry registry = new RoomRegistry();
	private final RoomBroadcaster broadcaster = new RoomBroadcaster(registry, new SessionSender());
	private final ExecutorService executor = Executors.newCachedThreadPool();

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	// 시나리오 3 (불변식 ②): 같은 방에 여러 스레드가 동시에 SEND해도 모든 멤버가 같은 순서로 받는다.
	@Test
	void 같은_방에_동시에_보내도_모든_멤버가_같은_순서로_받는다() throws Exception {
		int memberCount = 4;
		int perThread = 100;
		List<List<String>> inboxes = new ArrayList<>();
		List<WebSocketSession> members = new ArrayList<>();
		for (int i = 0; i < memberCount; i++) {
			List<String> inbox = Collections.synchronizedList(new ArrayList<>());
			inboxes.add(inbox);
			// 이유: 전송마다 0~20µs를 무작위로 쉬게 해 스레드마다 루프 속도가 달라지게 한다.
			// 실제 네트워크에서도 멤버마다 쓰는 시간이 다르다. 지연이 없으면 루프가 너무 빨라 앞지르기가 거의 생기지 않는다.
			WebSocketSession member = recordingSession(inbox,
					() -> LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(20_000)));
			members.add(member);
			registry.join(member, "R");
		}

		// 멤버 각자가 보낸 사람이 되어 동시에 출발한다.
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < memberCount; t++) {
			WebSocketSession from = members.get(t);
			String sender = "s" + t;
			futures.add(executor.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					broadcaster.broadcast(from, "R", new TextMessage(sender + "-" + i));
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}

		// 이유: 순서 비교 전에 개수부터 맞아야 한다. 빠진 메시지가 있으면 순서 비교 결과가 의미 없다.
		for (List<String> inbox : inboxes) {
			assertThat(inbox).hasSize(memberCount * perThread);
		}
		List<String> reference = inboxes.get(0);
		for (int m = 1; m < memberCount; m++) {
			List<String> inbox = inboxes.get(m);
			assertThat(inbox)
				.as("멤버 0과 멤버 %d의 순서가 처음 달라지는 위치: %s", m, firstDifference(reference, inbox))
				.isEqualTo(reference);
		}
	}

	// 시나리오 4 (불변식 ①): 복사한 뒤·전송하기 전에 JOIN한 B는 그 메시지를 받지 않는다.
	@Test
	void 전송_도중에_JOIN한_세션은_그_메시지를_받지_않는다() throws Exception {
		CountDownLatch inFirstSend = new CountDownLatch(1);
		CountDownLatch resume = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		// 이유: 첫 전송에서 멈춰 "복사 후·전송 전" 틈을 원하는 만큼 벌린다. 그 틈에 B를 넣고 다시 진행시킨다.
		// 무작위 타이밍에 기대지 않으므로 매번 같은 순서로 실행된다.
		Runnable pauseOnFirstSend = () -> {
			if (first.compareAndSet(true, false)) {
				inFirstSend.countDown();
				try {
					resume.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};
		List<String> inboxA = Collections.synchronizedList(new ArrayList<>());
		List<String> inboxC = Collections.synchronizedList(new ArrayList<>());
		List<String> inboxB = Collections.synchronizedList(new ArrayList<>());
		WebSocketSession a = recordingSession(inboxA, pauseOnFirstSend);
		WebSocketSession c = recordingSession(inboxC, pauseOnFirstSend);
		WebSocketSession b = recordingSession(inboxB, () -> { });
		registry.join(a, "R");
		registry.join(c, "R");

		Future<Boolean> sending = executor.submit(() -> broadcaster.broadcast(a, "R", new TextMessage("안녕")));
		assertThat(inFirstSend.await(5, TimeUnit.SECONDS)).isTrue();

		// 이유: JOIN이 브로드캐스트를 기다리며 멈추면 테스트가 끝나지 않는다. 다른 스레드에서 제한 시간을 두고 실행한다.
		executor.submit(() -> registry.join(b, "R")).get(5, TimeUnit.SECONDS);
		resume.countDown();

		assertThat(sending.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(inboxB).as("복사 후 JOIN한 B").isEmpty();
		assertThat(inboxA).as("A(본인)").containsExactly("안녕");
		assertThat(inboxC).as("C").containsExactly("안녕");
	}

	// sendMessage가 불리면 내용을 기록하고 hook을 실행하는 가짜 세션.
	private static WebSocketSession recordingSession(List<String> inbox, Runnable hook) throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		doAnswer(invocation -> {
			WebSocketMessage<?> message = invocation.getArgument(0);
			hook.run();
			inbox.add((String) message.getPayload());
			return null;
		}).when(session).sendMessage(any());
		return session;
	}

	private static String firstDifference(List<String> a, List<String> b) {
		for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
			if (!a.get(i).equals(b.get(i))) {
				return "index " + i + " (" + a.get(i) + " vs " + b.get(i) + ")";
			}
		}
		return "없음";
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [v1] 시나리오 3(멤버 4명이 각자 100번 동시 SEND, 전송마다 0~20µs 무작위 지연)·시나리오 4(첫 전송에서 멈춤 → B JOIN → 재개) (2026-10-24)
 *   - 방어 없음(원본 순회, 방 락 없음): 5회 모두 둘 다 실패. 4는 ConcurrentModificationException, 3은 index 14~219에서 순서가 갈림
 *   - 복사만: 5회 모두 4 통과, 3 실패 (index 10~39)
 *   - 복사 + 방 락: 5회 모두 둘 다 통과
 *   - 판단: 테스트를 바꾸지 않고 구현만 바꿨을 때 각 단계가 막는 문제가 하나씩 통과로 바뀌었다. 시나리오 3의 통과는
 *     "이 조건에서 앞지르기를 찾지 못했다"는 뜻이고, 근거는 방 락 범위에 대한 추론이다.
 */
