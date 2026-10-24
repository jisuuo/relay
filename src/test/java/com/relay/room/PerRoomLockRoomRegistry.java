package com.relay.room;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.web.socket.WebSocketSession;

/**
 * 실험용: ADR-0008 대안 C "방마다 락 + 락을 잡은 뒤 재확인".
 *
 * <p>왜 테스트 코드에 있는가: 실제 코드(RoomRegistry)는 ADR-0008대로 전체 락을 쓴다.
 * 이 클래스는 두 방식의 차이를 측정하려고만 만든 것이다 (RoomLockExperimentTest).
 *
 * <p>전체 락에는 없고 이 방식에서 지켜야 하는 규칙:
 * <ol>
 *   <li>방 락을 잡은 뒤, 맵의 방이 "내가 쥔 바로 그 객체"인지 다시 확인한다. 아니면 처음부터 다시 한다.
 *       이름만 확인하면 그사이 다른 JOIN이 만든 같은 이름의 새 방을 보고 통과해 버린다.</li>
 *   <li>빈 방 삭제는 반드시 그 방의 락 안에서, 그 객체일 때만 한다 ({@code remove(key, value)}).</li>
 *   <li>세션 → {방들}은 서로 다른 방 락에서 같은 세션 항목을 고칠 수 있다. 항목 단위로 원자적인
 *       {@code compute} 계열로 고쳐야 세션 쪽에서 같은 "빈 항목 삭제" 경쟁이 생기지 않는다.</li>
 *   <li>방 락을 잡은 채 다른 방 락을 잡지 않는다. 두 개를 엇갈린 순서로 잡으면 교착(deadlock)이 생길 수 있다.</li>
 * </ol>
 */
class PerRoomLockRoomRegistry {

	private static final class Room {
		final Set<WebSocketSession> members = new HashSet<>();
	}

	private final Map<String, Room> rooms = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<WebSocketSession, Set<String>> roomsBySession = new ConcurrentHashMap<>();

	// 측정용: 재확인에 실패해 처음부터 다시 한 횟수 (비교 요소 ④)
	final LongAdder retries = new LongAdder();

	boolean join(WebSocketSession session, String roomId) {
		while (true) {
			Room room = rooms.computeIfAbsent(roomId, id -> new Room());
			synchronized (room) {
				if (rooms.get(roomId) != room) {
					retries.increment();
					continue;
				}
				boolean added = room.members.add(session);
				roomsBySession.compute(session, (s, joined) -> {
					Set<String> result = joined == null ? new HashSet<>() : joined;
					result.add(roomId);
					return result;
				});
				return added;
			}
		}
	}

	boolean leave(WebSocketSession session, String roomId) {
		while (true) {
			Room room = rooms.get(roomId);
			if (room == null) {
				return false;
			}
			synchronized (room) {
				if (rooms.get(roomId) != room) {
					retries.increment();
					continue;
				}
				if (!room.members.remove(session)) {
					return false;
				}
				if (room.members.isEmpty()) {
					rooms.remove(roomId, room);
				}
				roomsBySession.computeIfPresent(session, (s, joined) -> {
					joined.remove(roomId);
					return joined.isEmpty() ? null : joined;
				});
				return true;
			}
		}
	}

	// 방을 하나씩 따로 잠근다(규칙 4). 그래서 정리 도중에는 "세션 항목은 없는데 방에는 아직 남은" 순간이 있다.
	void removeSession(WebSocketSession session) {
		Set<String> joined = roomsBySession.remove(session);
		if (joined == null) {
			return;
		}
		for (String roomId : Set.copyOf(joined)) {
			removeFromRoom(session, roomId);
		}
	}

	private void removeFromRoom(WebSocketSession session, String roomId) {
		while (true) {
			Room room = rooms.get(roomId);
			if (room == null) {
				return;
			}
			synchronized (room) {
				if (rooms.get(roomId) != room) {
					retries.increment();
					continue;
				}
				room.members.remove(session);
				if (room.members.isEmpty()) {
					rooms.remove(roomId, room);
				}
				return;
			}
		}
	}

	// 방마다 락이라 두 매핑 전체를 한 순간에 멈춰 볼 방법이 없다. 방 하나씩 잠그며 복사하므로 실행 도중에는
	// 서로 다른 시점의 모습이 섞인다 (비교 요소 ⑤).
	Map<String, Set<WebSocketSession>> roomsSnapshot() {
		Map<String, Set<WebSocketSession>> copy = new HashMap<>();
		rooms.forEach((id, room) -> {
			synchronized (room) {
				copy.put(id, Set.copyOf(room.members));
			}
		});
		return copy;
	}

	Map<WebSocketSession, Set<String>> roomsBySessionSnapshot() {
		Map<WebSocketSession, Set<String>> copy = new HashMap<>();
		roomsBySession.forEach((session, joined) -> roomsBySession.computeIfPresent(session, (s, current) -> {
			copy.put(s, Set.copyOf(current));
			return current;
		}));
		return copy;
	}

}
