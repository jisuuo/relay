package com.relay.websocket;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 현재 연결된 WebSocket 세션을 보관하는 저장소.
 *
 * <p>왜 필요한가: 서버는 {@code afterConnectionEstablished}에서 세션 객체를 딱 한 번 받는다.
 * 여기에 보관하지 않으면 이후 그 사용자에게 메시지를 보낼 방법이 없다.
 * 종료 시 제거하지 않으면 끊긴 세션이 남아 메모리 누수·접속자 수 왜곡·전송 실패가 생긴다.
 *
 * <p>왜 Set인가: 필요한 연산은 추가·삭제·전체 반복·개수이고, 같은 세션이 두 번 들어갈 일이 없으며 순서도 필요 없다.
 *
 * <p>개념 노트: docs/learning/session-store-concurrency.md
 */
@Component
public class SessionRegistry {

	// 이유: 연결·종료 이벤트는 Tomcat 스레드 풀의 여러 스레드에서 동시에 호출된다.
	// HashSet은 동시 수정 시 세션이 예외 없이 사라진다 (하단 변경 이력 시도 1).
	// ConcurrentHashMap은 Set 전체가 아니라 버킷 단위로 락을 걸고 빈 버킷은 CAS로 처리하므로,
	// 서로 다른 버킷을 건드리는 스레드는 기다리지 않고 동시에 진행된다.
	// synchronizedSet(락 하나)은 수천 연결이 한 줄로 대기하고, CopyOnWriteArraySet은 연결·종료마다
	// 배열 전체를 복사해서 선택하지 않았다.
	private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

	public void add(WebSocketSession session) {
		sessions.add(session);
	}

	public void remove(WebSocketSession session) {
		sessions.remove(session);
	}

	// 이유: heartbeat가 모든 세션에 ping을 보내려고 반복한다. 바깥에서 add·remove하지 못하게 읽기 전용으로 돌려준다.
	// ConcurrentHashMap은 반복 중 다른 스레드가 연결·종료해도 예외 없이 계속 돈다(그 사이 바뀐 세션은 보일 수도, 안 보일 수도 있다).
	// 이번 주기에 못 본 세션은 다음 주기에 본다.
	public Set<WebSocketSession> sessions() {
		return Collections.unmodifiableSet(sessions);
	}

	public int size() {
		return sessions.size();
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] HashSet (2026-10-24)
 *   - 의도: 동시 등록 시 세션 유실(경쟁 조건)을 SessionRegistryTest로 재현
 *   - 결과: 10회 실행 모두 실패. 메시지를 확인한 6회는 기대 10000개 중 6611~8546개만 남음
 *           (1,454~3,389개 유실). 예외는 발생하지 않음.
 *   - 원인(추정, 어느 메커니즘이 몇 건을 유발했는지는 확인하지 않음): add 내부의 버킷 연결·size 증가·resize가 여러 단계라, 다른 스레드가 중간 상태를 보고
 *           작업하면 결과가 덮어써지거나 resize 중 기존 배열에 들어간 원소가 버려진다.
 *
 * [시도 2] ConcurrentHashMap.newKeySet() (2026-10-24)
 *   - 이유: 버킷 단위 락 + CAS로 동시 수정에 안전하면서, 다른 버킷끼리는 대기 없이 진행
 *   - 결과: 같은 테스트 10회 실행 모두 통과 (size == 10000)
 */
