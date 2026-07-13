package org.sopt.hashi.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.sopt.hashi.shared.swagger.ApiErrorResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

class ApiExceptionsOperationCustomizerTest {

    private final ApiExceptionsOperationCustomizer customizer = new ApiExceptionsOperationCustomizer();

    @Test
    void enum과_명시적으로_선언한_오류를_상태별_Swagger_예시로_문서화한다() throws Exception {
        Method method = TestHandler.class.getDeclaredMethod("update");
        HandlerMethod handlerMethod = new HandlerMethod(new TestHandler(), method);
        Operation operation = new Operation().responses(new ApiResponses());

        customizer.customize(operation, handlerMethod);

        Map<String, Example> badRequestExamples = operation.getResponses()
                .get("400")
                .getContent()
                .get("application/json")
                .getExamples();
        Map<String, Example> notFoundExamples = operation.getResponses()
                .get("404")
                .getContent()
                .get("application/json")
                .getExamples();

        assertThat(badRequestExamples).containsKey("COMMON-400");
        assertThat(notFoundExamples).containsOnlyKeys("RESTAURANT-004", "RESTAURANT-009");
        assertThat(notFoundExamples.get("RESTAURANT-009").getValue())
                .isInstanceOfSatisfying(ErrorResponse.class, response -> {
                    assertThat(response.success()).isFalse();
                    assertThat(response.code()).isEqualTo("RESTAURANT-009");
                    assertThat(response.message()).isEqualTo("메뉴를 찾을 수 없습니다.");
                    assertThat(response.data()).isNull();
                });
    }

    static class TestHandler {

        @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
        @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-004",
                message = "식당을 찾을 수 없습니다.")
        @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "RESTAURANT-009",
                message = "메뉴를 찾을 수 없습니다.")
        void update() {
        }
    }
}
