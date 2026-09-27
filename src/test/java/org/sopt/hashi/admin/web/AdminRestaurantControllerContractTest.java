package org.sopt.hashi.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.config.ApiExceptionsOperationCustomizer;
import org.springframework.web.method.HandlerMethod;

class AdminRestaurantControllerContractTest {

    private final ApiExceptionsOperationCustomizer customizer =
            new ApiExceptionsOperationCustomizer();
    private final AdminRestaurantController controller =
            new AdminRestaurantController(null);

    @Test
    void 식당_등록과_수정은_media_binding_오류를_OpenAPI에_노출한다() throws Exception {
        assertMediaBindingErrors("create", CreateRestaurantRequest.class);
        assertMediaBindingErrors("update", Long.class, UpdateRestaurantRequest.class);
    }

    private void assertMediaBindingErrors(String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = AdminRestaurantController.class
                .getDeclaredMethod(methodName, parameterTypes);
        Operation operation = new Operation().responses(new ApiResponses());

        customizer.customize(operation, new HandlerMethod(controller, method));

        assertThat(examples(operation, "400")).containsKey("MEDIA-008");
        assertThat(examples(operation, "404")).containsKey("MEDIA-001");
        assertThat(examples(operation, "409")).containsKeys("MEDIA-006", "MEDIA-007");
    }

    private Map<String, Example> examples(Operation operation, String status) {
        return operation.getResponses()
                .get(status)
                .getContent()
                .get("application/json")
                .getExamples();
    }
}
