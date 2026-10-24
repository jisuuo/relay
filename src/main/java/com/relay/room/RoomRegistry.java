package com.relay.room;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 방 → {세션들}, 세션 → {방들} 두 매핑을 함께 관리한다.
 *
 * <p>왜 매핑이 두 개인가: 방 → {세션들}만 있으면 연결이 끊긴 세션을 정리할 때 전체 방을 훑어야 한다.
 * 사용자는 보통 LEAVE 없이 연결을 끊으므로 끊김 정리가 주요 경로다. 세션 → {방들}로 그 세션이 들어간 방만 바로 찾는다.
 *
 * <p>지켜야 할 불변식 (P1-3 정의):
 * <ol>
 *   <li>S ∈ 방 A의 세션들 ⇔ A ∈ S의 방들</li>
 *   <li>빈 방은 존재하지 않는다 (같은 이유로 빈 세션 항목도 두지 않는다)</li>
 *   <li>끊긴 세션은 매핑에 없다</li>
 * </ol>
 */
@Component
public class RoomRegistry {

	// 이유: JOIN·LEAVE·끊김은 "방 찾기 → 추가", "빈 방 확인 → 삭제"처럼 여러 단계이고 두 매핑을 함께 바꾼다.
	// ConcurrentHashMap은 단계 하나하나만 안전해서, 그 사이에 다른 스레드가 끼어들면 ①이 깨진다 (하단 변경 이력 시도 1).
	// 그래서 모든 메서드를 같은 락(this) 하나로 감싸 한 번에 하나의 변경만 일어나게 한다.
	// 방마다 락 + 재확인은 서로 다른 방끼리 기다리지 않지만, 객체 비교·재시도·락 안 삭제 규칙이 필요하다.
	// 줄 서기가 실제 문제인지는 아직 재현되지 않았으므로 단순한 쪽에서 시작한다 (ADR-0008, P1-7에서 측정).
	// 모든 접근이 락 안에서만 일어나므로 맵은 스레드 안전할 필요가 없다.
	private final Map<String, Set<WebSocketSession>> rooms = new HashMap<>();
	private final Map<WebSocketSession, Set<String>> roomsBySession = new HashMap<>();

	// 없는 방이면 만든다: 방을 만드는 별도 메시지가 없다 (ADR-0007). 이미 들어간 방이면 아무 일 없다(멱등).
	public synchronized boolean join(WebSocketSession session, String roomId) {
		Set<WebSocketSession> members = rooms.computeIfAbsent(roomId, id -> new HashSet<>());
		boolean added = members.add(session);
		roomsBySession.computeIfAbsent(session, s -> new HashSet<>()).add(roomId);
		return added;
	}

	// 방에 없는 세션의 LEAVE는 아무 일 없이 false. 실패를 클라이언트에 어떻게 알릴지는 P1-5에서 정한다.
	public synchronized boolean leave(WebSocketSession session, String roomId) {
		Set<WebSocketSession> members = rooms.get(roomId);
		if (members == null || !members.remove(session)) {
			return false;
		}
		if (members.isEmpty()) {
			rooms.remove(roomId);
		}
		Set<String> joined = roomsBySession.get(session);
		joined.remove(roomId);
		if (joined.isEmpty()) {
			roomsBySession.remove(session);
		}
		return true;
	}

	// 끊김에는 roomId가 없다. 세션 → {방들}에서 그 세션이 들어간 방만 꺼내 정리한다.
	public synchronized void removeSession(WebSocketSession session) {
		Set<String> joined = roomsBySession.remove(session);
		if (joined == null) {
			return;
		}
		for (String roomId : joined) {
			Set<WebSocketSession> members = rooms.get(roomId);
			members.remove(session);
			if (members.isEmpty()) {
				rooms.remove(roomId);
			}
		}
	}

	// 브로드캐스트 대상. from이 방의 멤버가 아니면(방이 없어도) 빈 집합.
	// 멤버 확인과 복사를 같은 락 안에서 한다: 락 밖에서 확인하면 확인과 복사 사이에 LEAVE·삭제가 끼어든다.
	// 원본이 아니라 복사본을 준다: 전송은 락 밖에서 하므로 원본을 주면 순회 중 JOIN·LEAVE가 같은 HashSet을 바꿔
	// ConcurrentModificationException이 나거나, 복사 후 들어온 세션까지 받는다 (RoomBroadcaster 변경 이력 시도 1).
	// 복사한 순간이 이 SEND의 처리 시점이 된다 (불변식 ①, docs/learning/broadcast-snapshot.md).
	public synchronized Set<WebSocketSession> membersFor(WebSocketSession from, String roomId) {
		Set<WebSocketSession> members = rooms.get(roomId);
		if (members == null || !members.contains(from)) {
			return Set.of();
		}
		return Set.copyOf(members);
	}

	// 테스트가 불변식을 검사하려고 두 매핑을 들여다보는 용도. 내부 상태가 바깥에서 바뀌지 않도록 복사본을 준다.
	synchronized Map<String, Set<WebSocketSession>> roomsSnapshot() {
		Map<String, Set<WebSocketSession>> copy = new HashMap<>();
		rooms.forEach((id, members) -> copy.put(id, Set.copyOf(members)));
		return copy;
	}

	synchronized Map<WebSocketSession, Set<String>> roomsBySessionSnapshot() {
		Map<WebSocketSession, Set<String>> copy = new HashMap<>();
		roomsBySession.forEach((session, joined) -> copy.put(session, Set.copyOf(joined)));
		return copy;
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] ConcurrentHashMap, 락 없음 (2026-10-24)
 *   - 의도: "마지막 1명 LEAVE로 빈 방 삭제 직전 JOIN이 방을 쥐면 유실"을 RoomRegistryConcurrencyTest로 재현
 *   - 결과: 11회 실행 모두 두 테스트 실패. 테스트별로 나눠 확인한 5회에서 시도 200회 중 위반이 생긴 시도는
 *           LEAVE 경로 22~36회, 끊김 경로 20~45회.
 *           위반은 모두 "① 세션 목록에는 방이 있는데 맵의 방에 그 세션이 없음". 예외는 발생하지 않음.
 *   - 원인: join이 computeIfAbsent로 방 집합을 꺼낸 뒤 add하기 전에, leave·removeSession이 그 방을 비었다고 보고
 *           맵에서 삭제한다. join은 맵에서 떨어진 집합에 세션을 넣고, 자기 방 목록에는 방을 기록한다.
 *
 * [시도 2] synchronized로 전체 락 하나 (2026-10-24)
 *   - 이유: 방 찾기부터 두 매핑 갱신까지를 한 번에 실행해 틈을 없앤다. 방마다 락은 방 찾기가 락 밖에 남아 같은 문제가 생긴다 (ADR-0008)
 *   - 결과: 같은 테스트 5회 실행 모두 통과 (RoomRegistryConcurrencyTest 2개, RoomRegistryTest 7개)
 *
 * [P1-4] membersFor 추가 (2026-10-24)
 *   - 처음엔 원본 집합을 그대로 돌려줌 → RoomBroadcasterTest 시나리오 4가 5회 모두 ConcurrentModificationException으로 실패
 *   - Set.copyOf로 복사본을 돌려주게 바꾼 뒤 시나리오 4가 5회 모두 통과 (RoomBroadcaster 변경 이력 시도 1·2)
 */
