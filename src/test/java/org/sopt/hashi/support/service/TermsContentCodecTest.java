package org.sopt.hashi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsType;

class TermsContentCodecTest {
    private final TermsContentCodec codec = new TermsContentCodec(new ObjectMapper());

    @Test
    void 조항의_순서와_일반텍스트를_보존한다() {
        var command = new TermsCommand(TermsType.SERVICE_TERMS, "약관", "1.0", LocalDate.of(2026, 10, 1),
                List.of(new TermsClause("제1조", "<script>도 일반 텍스트\n본문"),
                        new TermsClause("제2조", "본문")));
        assertThat(codec.decode(codec.validateAndEncode(command))).isEqualTo(command.clauses());
    }

    @Test
    void 필수정보와_본문_상한을_검증한다() {
        var clause = List.of(new TermsClause("제1조", "본문"));
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(null, "약관", "1",
                LocalDate.now(), clause))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(TermsType.SERVICE_TERMS, " ", "1",
                LocalDate.now(), clause))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(TermsType.SERVICE_TERMS, "약관", " 1",
                LocalDate.now(), clause))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(TermsType.SERVICE_TERMS, "약관", "1",
                null, clause))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(TermsType.SERVICE_TERMS, "약관", "1",
                LocalDate.now(), List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(new TermsCommand(TermsType.SERVICE_TERMS, "약관", "1",
                LocalDate.now(), List.of(new TermsClause("제1조", "a".repeat(100001))))))
                .isInstanceOf(BusinessException.class);
    }
}
