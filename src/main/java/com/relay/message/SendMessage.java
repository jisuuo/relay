package com.relay.message;

/**
 * 방에 채팅 메시지 전송 요청.
 *
 * <p>왜 roomId가 필요한가: 한 세션이 여러 방에 있을 수 있어 서버가 대상 방을 정할 수 없다 (ADR-0006).
 * roomId도 클라이언트가 쓴 값이므로, 세션이 그 방의 멤버인지는 서버 상태로 확인한다 (P1-3·4).
 */
public record SendMessage(String roomId, String content) implements ClientMessage {

	// 이유: Jackson은 JSON에 없는 필드를 걸러 주지 않는다 (하단 변경 이력 시도 1).
	// 파서가 아니라 생성자에 검사를 두면, 파서를 거치지 않는 경로로 만들어도 content 없는 SendMessage가 생기지 않는다 (ADR-0007).
	public SendMessage {
		Fields.requireText(roomId, "roomId");
		Fields.requireText(content, "content");
	}

}

/*
 * ── 변경 이력 ──────────────────────────────────────────────
 *
 * [시도 1] 생성자 검사 없음 (2026-10-24)
 *   - 의도: Jackson이 빠진 필드를 걸러 주는지 ClientMessageParserTest 시나리오 4·5·8로 확인
 *   - 결과: roomId·content 각각 누락·null·""·공백만 8가지 모두 예외 없이 객체가 반환됨.
 *           new SendMessage("A", null) 직접 생성도 성공 (테스트 실패)
 *
 * [시도 2] compact constructor에서 Fields.requireText (2026-10-24)
 *   - 이유: 어떤 경로로 만들어도 검사를 피할 수 없게 한다 (ADR-0007)
 *   - 결과: 시나리오 4·5·8 통과. Jackson 경로에서는 IllegalArgumentException이
 *           ValueInstantiationException으로 감싸져 나오고, 파서가 이를 InvalidMessageException으로 다시 감싼다
 */
