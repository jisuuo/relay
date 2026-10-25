package com.relay.websocket;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 주기적으로 ping을 보내고, 정해진 시간 안에 pong이 없는 세션을 닫는다 (ADR-0005 후속).
 *
 * <p>왜 필요한가: 클라이언트가 FIN·RST·Close 없이 사라지면 서버는 "아직 말이 없음"과 "사라짐"을 구별하지 못해
 * afterConnectionClosed가 불리지 않고 세션이 매핑에 남는다 (P1-5 문장 1, 실험 1: 71초).
 * 보낼 데이터가 없으면 Tomcat 쓰기 시간 초과(실험 3)도 일어나지 않는다. 그래서 서버가 직접 말을 걸어 대답을 확인한다.
 *
 * <p>왜 닫기만 하나: 닫으면 Spring이 afterConnectionClosed를 부르고, 매핑 정리는 그 한 경로에서만 한다.
 * 여기서 RoomRegistry를 직접 지우면 정리 경로가 둘이 된다.
 */
@Component
public class Heartbeat {

	private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

	// 이유: 시각을 세션 attributes에 둔다. 세션이 사라지면 함께 사라져 따로 지울 맵이 없다 (P1-5 설계).
	private static final String PING_SENT_AT = "heartbeat.pingSentAt";
	private static final String PONG_AT = "heartbeat.pongAt";

	private final SessionRegistry registry;
	private final SessionSender sender;
	private final Duration timeout;

	public Heartbeat(SessionRegistry registry, SessionSender sender,
			@Value("${relay.heartbeat.timeout}") Duration timeout) {
		this.registry = registry;
		this.sender = sender;
		this.timeout = timeout;
	}

	// 이유: fixedDelay는 앞 주기가 끝난 뒤부터 간격을 센다. 한 주기가 길어져도(전송이 막혀도) 주기끼리 겹치지 않는다.
	@Scheduled(fixedDelayString = "${relay.heartbeat.interval}")
	void check() {
		// 이유: 시계 시각(currentTimeMillis)은 NTP 보정으로 뒤로 갈 수 있다. 경과 시간만 필요하므로 nanoTime을 쓴다.
		long now = System.nanoTime();
		for (WebSocketSession session : registry.sessions()) {
			Long pingSentAt = (Long) session.getAttributes().get(PING_SENT_AT);
			Long pongAt = (Long) session.getAttributes().get(PONG_AT);

			// 대답을 기다리는 ping이 있다: 보낸 적이 있고, 그 뒤로 pong이 없다.
			boolean waiting = pingSentAt != null && (pongAt == null || pongAt < pingSentAt);
			if (waiting) {
				// 이유: 기준은 "ping을 보낸 뒤 타임아웃 안에 pong이 없음"이다. 조용한 사용자도 라이브러리가 pong은 보내므로
				// 끊기지 않는다 (터널·D 반례: 살아 있는 사용자를 끊는 것이 늦게 찾는 것보다 나쁘다).
				if (now - pingSentAt >= timeout.toNanos()) {
					log.info("pong 없음 {}ms → 닫음 session={}", Duration.ofNanos(now - pingSentAt).toMillis(),
							session.getId());
					sender.close(session, CloseStatus.SESSION_NOT_RELIABLE);
				}
				// 기다리는 중에는 ping을 더 보내지 않는다. 다시 보내면 PING_SENT_AT이 밀려 타임아웃이 영원히 오지 않는다.
				continue;
			}

			// 이유: 보내기 전에 시각을 적는다. 보낸 뒤 적으면 그 사이 pong이 먼저 도착해 pongAt < pingSentAt이 되고,
			// 대답한 세션을 기다리는 중으로 잘못 본다.
			session.getAttributes().put(PING_SENT_AT, now);
			sender.send(session, new PingMessage());
		}
	}

	void pongReceived(WebSocketSession session) {
		session.getAttributes().put(PONG_AT, System.nanoTime());
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [P1-5] 주기적 ping + ping 뒤 타임아웃 안에 pong이 없으면 닫기 (2026-10-25)
 *   - 고치기 전: HeartbeatWebSocketTest 유령 시나리오 실패. 3초 뒤에도 ghost-room과 유령 세션이 두 매핑에 남음 (실험 1과 같음)
 *   - 고친 후: 유령은 "pong 없음 613ms → 닫음" 로그 뒤 afterConnectionClosed로 정리됨 (주기 200ms·타임아웃 600ms).
 *              pong하는 조용한 클라이언트는 3초 동안 한 번도 닫히지 않음. 전체 테스트 62개 3회 통과
 *   - 한계: 닫기(Close 프레임 쓰기)도 세션 락 안이라, ACK 없는 진짜 유령이면 쓰기 시간 초과(약 20초)까지 스케줄러 스레드가
 *           막혀 그동안 다른 세션 확인이 밀릴 수 있다. localhost로는 재현하지 못함 → P1-8 느린 소비자와 함께 본다
 */
