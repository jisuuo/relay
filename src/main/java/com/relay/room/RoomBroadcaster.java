package com.relay.room;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.relay.websocket.SessionSender;

/**
 * 방 하나의 멤버 전원(보낸 사람 포함)에게 같은 메시지를 보낸다.
 *
 * <p>왜 RoomRegistry와 나누는가: RoomRegistry는 방 ↔ 세션 매핑의 정합성만 맡는다. 퍼뜨리는 순서와 전송은 이 클래스가 맡고,
 * 한 세션에 보내는 일은 SessionSender(유니캐스트)에 맡긴다. 에러 응답 같은 유니캐스트가 브로드캐스트에 의존하지 않게 한다.
 *
 * <p>왜 본인에게도 보내는가: 모두가 서버가 처리한 순서 하나를 같은 순서로 보게 하려고 (ADR-0009).
 */
@Component
public class RoomBroadcaster {

	// 이유: 64는 임시 값이다. 칸이 모자라 서로 다른 방이 같은 칸에 걸리면 쓸데없이 기다릴 뿐 정확성은 그대로다.
	// P1-7 측정 후 조정한다.
	private static final int LOCK_STRIPES = 64;

	private final RoomRegistry rooms;
	private final SessionSender sender;

	// 방마다 락. 방 객체가 아니라 방 이름의 해시로 고정 배열의 칸을 고른다 (락 스트라이핑, ADR-0009).
	// 방 객체를 락으로 쓰면 ① 락을 얻으려고 맵을 먼저 봐야 해서(전체 락) 락 순서 "방 → 전체 → 세션"과 충돌하고
	// 재확인·재시도가 필요해진다(ADR-0008 대안 C). ② 방이 삭제·재생성되면 두 스레드가 다른 락을 잡아 순서가 드물게 깨진다.
	// 이름만으로 계산하면 같은 이름은 항상 같은 락이고, 만들거나 지울 일도 없다.
	private final Object[] roomLocks = new Object[LOCK_STRIPES];

	public RoomBroadcaster(RoomRegistry rooms, SessionSender sender) {
		this.rooms = rooms;
		this.sender = sender;
		for (int i = 0; i < roomLocks.length; i++) {
			roomLocks[i] = new Object();
		}
	}

	// 보낸 세션이 방 멤버가 아니면 아무에게도 보내지 않고 false. 에러 응답은 P1-5에서 정한다.
	// 한 멤버에게 실패해도 다음 멤버에게 계속 보낸다 (SessionSender는 실패를 false로만 알린다).
	//
	// 왜 방 락으로 복사부터 전송 루프까지 감싸는가: 복사만 락 안에서 하면 받는 사람은 정해지지만, 두 SEND의 루프가 락 밖에서
	// 서로 앞질러 멤버마다 받는 순서가 달라진다 (불변식 ②, 변경 이력 시도 2). 방 R의 SEND를 한 번에 하나씩 끝까지 보내면
	// 방 락을 얻은 순서가 곧 모든 멤버가 받는 순서가 된다.
	// 대가: 느린 멤버가 있으면 이 방의 다른 SEND가 기다린다. 느린 소비자는 재현 후 P1-8에서 다룬다 (후보: Decorator).
	// 락 순서는 항상 방 락 → 전체 락(membersFor 안) → 세션 락(SessionSender 안). 역방향으로 잡는 경로가 없어 교착이 없다.
	public boolean broadcast(WebSocketSession from, String roomId, TextMessage message) {
		synchronized (lockFor(roomId)) {
			Set<WebSocketSession> members = rooms.membersFor(from, roomId);
			if (members.isEmpty()) {
				return false;
			}
			for (WebSocketSession member : members) {
				sender.send(member, message);
			}
			return true;
		}
	}

	// hashCode는 음수일 수 있어 floorMod로 0 ~ 칸 수-1 범위를 맞춘다.
	Object lockFor(String roomId) {
		return roomLocks[Math.floorMod(roomId.hashCode(), roomLocks.length)];
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] 방어 없음: 원본 멤버 집합을 락 밖에서 순회, 방 락 없음 (2026-10-24)
 *   - 의도: 시나리오 3(순서 ②)·4(복사 후 JOIN ①)를 RoomBroadcasterTest로 재현
 *   - 결과: 5회 실행 모두 두 테스트 실패.
 *           시나리오 4: 매번 ConcurrentModificationException. 순회 중 B의 JOIN이 같은 HashSet을 바꿔 루프가 끊김
 *           → C도 받지 못함 (예상한 "B가 받음"이 아니라 "루프 중단"으로 나타남)
 *           시나리오 3: 멤버 0과 다른 멤버의 순서가 index 14~219에서 처음 달라짐 (멤버당 400개 중)
 *
 * [시도 2] RoomRegistry.membersFor가 락 안에서 복사본을 돌려줌, 방 락은 아직 없음 (2026-10-24)
 *   - 이유: ①을 먼저 막고, 복사만으로 ②도 지켜지는지 따로 확인한다
 *   - 결과: 5회 실행 모두 시나리오 4 통과, 시나리오 3 실패 (index 10~39에서 순서가 갈림)
 *           → 복사는 받는 사람만 정하고, 락 밖의 루프끼리 앞지르는 것은 막지 못한다 (고민 단계 예상과 같음)
 *
 * [시도 3] 방 락(락 스트라이핑 64칸)으로 복사부터 전송 루프까지 감쌈 (2026-10-24)
 *   - 이유: 방 하나의 SEND를 한 번에 하나씩 끝까지 보내 방 락을 얻은 순서 = 모든 멤버가 받는 순서로 만든다 (ADR-0009)
 *   - 결과: 5회 실행 모두 두 테스트 통과. 전체 테스트 46개 통과
 */
