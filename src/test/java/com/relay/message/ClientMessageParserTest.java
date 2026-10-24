package com.relay.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P1-2 파서 단위 테스트. 시나리오 번호는 plan.md P1-2 "설계 확정"과 같다.
 *
 * <p>이 테스트는 생성자 검사가 없는 record에서 먼저 실패해야 의미가 있다 (시나리오 4·5·8).
 * Jackson이 빠진 필드를 null로 채워 객체를 만드는지 직접 확인하기 위해서다.
 */
class ClientMessageParserTest {

	private final ClientMessageParser parser = new ClientMessageParser();

	// ── 1. 정상 ──────────────────────────────────────────────

	@Test
	void 시나리오1_JOIN은_JoinMessage가_된다() {
		assertThat(parser.parse("""
				{"type":"JOIN","roomId":"A"}"""))
				.isEqualTo(new JoinMessage("A"));
	}

	@Test
	void 시나리오1_LEAVE는_LeaveMessage가_된다() {
		assertThat(parser.parse("""
				{"type":"LEAVE","roomId":"A"}"""))
				.isEqualTo(new LeaveMessage("A"));
	}

	@Test
	void 시나리오1_SEND는_SendMessage가_된다() {
		assertThat(parser.parse("""
				{"type":"SEND","roomId":"A","content":"hi"}"""))
				.isEqualTo(new SendMessage("A", "hi"));
	}

	// ── 2·3. 문법 오류, type 문제 ─────────────────────────────

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"type\":\"SEND\",\"roomId\":\"A\"",  // 2. 닫는 괄호 없음
			"not json",                             // 2. JSON 아님
			"{\"type\":\"DANCE\",\"roomId\":\"A\"}", // 3. 모르는 type
			"{\"roomId\":\"A\"}"                    // 3. type 누락
	})
	void 시나리오2_3_문법_오류나_type_문제는_실패한다(String text) {
		assertThatThrownBy(() -> parser.parse(text))
				.isInstanceOf(InvalidMessageException.class);
	}

	// ── 4. roomId 누락·빈 값 (세 타입 각각) ───────────────────

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"type\":\"JOIN\"}",
			"{\"type\":\"JOIN\",\"roomId\":null}",
			"{\"type\":\"JOIN\",\"roomId\":\"\"}",
			"{\"type\":\"JOIN\",\"roomId\":\"   \"}",
			"{\"type\":\"LEAVE\"}",
			"{\"type\":\"LEAVE\",\"roomId\":null}",
			"{\"type\":\"LEAVE\",\"roomId\":\"\"}",
			"{\"type\":\"LEAVE\",\"roomId\":\"   \"}",
			"{\"type\":\"SEND\",\"content\":\"hi\"}",
			"{\"type\":\"SEND\",\"roomId\":null,\"content\":\"hi\"}",
			"{\"type\":\"SEND\",\"roomId\":\"\",\"content\":\"hi\"}",
			"{\"type\":\"SEND\",\"roomId\":\"   \",\"content\":\"hi\"}"
	})
	void 시나리오4_roomId가_없거나_비어_있으면_실패한다(String text) {
		assertThatThrownBy(() -> parser.parse(text))
				.isInstanceOf(InvalidMessageException.class);
	}

	// ── 5. SEND의 content 누락·빈 값 ─────────────────────────

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"type\":\"SEND\",\"roomId\":\"A\"}",
			"{\"type\":\"SEND\",\"roomId\":\"A\",\"content\":null}",
			"{\"type\":\"SEND\",\"roomId\":\"A\",\"content\":\"\"}",
			"{\"type\":\"SEND\",\"roomId\":\"A\",\"content\":\"   \"}"
	})
	void 시나리오5_content가_없거나_비어_있으면_실패한다(String text) {
		// 이유: 루트 원인까지 확인해야 Jackson이 아니라 생성자 검사가 거절했다는 것을 알 수 있다.
		assertThatThrownBy(() -> parser.parse(text))
				.isInstanceOf(InvalidMessageException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	// ── 6. 내용이 있는 공백은 보존 ─────────────────────────────

	@Test
	void 시나리오6_앞뒤_공백이_있는_content는_그대로_보존한다() {
		assertThat(parser.parse("""
				{"type":"SEND","roomId":"A","content":" 안녕 "}"""))
				.isEqualTo(new SendMessage("A", " 안녕 "));
	}

	// ── 7. 모르는 필드는 무시 ──────────────────────────────────

	@Test
	void 시나리오7_sender를_보내도_무시하고_성공한다() {
		assertThat(parser.parse("""
				{"type":"SEND","roomId":"A","content":"hi","sender":"bob"}"""))
				.isEqualTo(new SendMessage("A", "hi"));
	}

	@Test
	void 시나리오7_JOIN에_붙은_content는_무시하고_성공한다() {
		assertThat(parser.parse("""
				{"type":"JOIN","roomId":"A","content":"뭐지?"}"""))
				.isEqualTo(new JoinMessage("A"));
	}

	// ── 8. 파서를 거치지 않는 생성도 막힌다 ────────────────────

	@Test
	void 시나리오8_파서_없이_직접_만들어도_잘못된_SendMessage는_생기지_않는다() {
		assertThatThrownBy(() -> new SendMessage("A", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
