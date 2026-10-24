package com.relay.message;

/**
 * 방 입장 요청. 연결(/ws 접속)과 입장은 다른 일이므로 별도 메시지로 요청한다.
 *
 * <p>방이 존재하는지, 이미 입장했는지는 서버 상태가 필요하므로 여기서 확인하지 않는다 (P1-3).
 */
public record JoinMessage(String roomId) implements ClientMessage {

	// 이유: 검사를 파서가 아니라 생성자에 둔다. 파서를 거치지 않는 경로(테스트, P2 DB 복원,
	// P4 Kafka 역직렬화 등)로 만들어도 잘못된 객체가 생기지 않는다. 객체가 존재하면 유효하다 (ADR-0007).
	public JoinMessage {
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
