package com.relay.message;

/**
 * 방 퇴장 요청.
 *
 * <p>왜 roomId가 필요한가: 한 세션이 여러 방에 있을 수 있어 서버가 어느 방에서 나갈지 정할 수 없다 (ADR-0006).
 * 그 방의 멤버인지는 서버 상태로 확인한다 (P1-3·4).
 */
public record LeaveMessage(String roomId) implements ClientMessage {

	// 이유: JoinMessage와 같다. 어떤 경로로 만들어도 검사를 피할 수 없게 생성자에 둔다 (ADR-0007).
	public LeaveMessage {
		Fields.requireText(roomId, "roomId");
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] 생성자 검사 없음 (2026-10-24)
 *   - 의도: Jackson이 빠진 필드를 걸러 주는지 ClientMessageParserTest 시나리오 4로 확인
 *   - 결과: roomId 누락·null·""·공백만 4가지 모두 예외 없이 객체가 반환됨 (테스트 실패)
 *
 * [시도 2] compact constructor에서 Fields.requireText (2026-10-24)
 *   - 이유: 어떤 경로로 만들어도 검사를 피할 수 없게 한다 (ADR-0007)
 *   - 결과: 시나리오 4 통과
 */
