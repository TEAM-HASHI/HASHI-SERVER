package org.sopt.hashi.exchangerate.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Quote;
import static org.assertj.core.api.Assertions.assertThat;

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
