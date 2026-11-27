package org.sopt.hashi.exchangerate.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Quote;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class EximbankClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 엔화_100엔_매매기준율을_골라_원단위에서_한번만_반올림한다() throws Exception {
        var rate = EximbankClient.parse(json.readTree("""
                [{"result":1,"cur_unit":"USD","deal_bas_r":"1,350.60"},
                 {"result":1,"cur_unit":"JPY(100)","deal_bas_r":"1,000.00"}]
                """)).orElseThrow();
        var quote = new Quote(LocalDate.now(), LocalDate.now(), rate);
        assertThat(quote.toWon(new BigDecimal("10.05"))).isEqualByComparingTo("101");
    }

    @Test
    void 은행_인증오류와_조회한도는_진단하되_원문은_로그에_남기지_않는다(CapturedOutput output) throws Exception {
        EximbankClient.parse(json.readTree("""
                [{"result":3,"message":"test-response-marker"}]
                """));
        EximbankClient.parse(json.readTree("[{\"result\":4}]"));
        assertThat(output.getAll()).contains("인증 오류", "일일 조회 한도 초과").doesNotContain("test-response-marker");
    }

    @Test
    void 휴일_오류_또는_잘못된_통화단위와_값은_새_환율을_만들지_않는다() throws Exception {
        for (String body : new String[]{"[]", "{}", "[{\"result\":4}]", """
                [{"result":1,"cur_unit":"JPY","deal_bas_r":"950"}]
                """, """
                [{"result":1,"cur_unit":"JPY(100)","deal_bas_r":"9,50"}]
                """}) {
            assertThat(EximbankClient.parse(json.readTree(body))).isEmpty();
        }
    }
}
