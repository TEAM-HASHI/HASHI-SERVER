package org.sopt.hashi.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.sopt.hashi.shared.swagger.ApiExceptions;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * {@link ApiExceptions}가 붙은 핸들러에, 나열된 ErrorCode enum의 코드들을
 * 상태코드별로 묶어 {@link ErrorResponse} 예시로 Swagger 응답에 첨부한다.
 */
public class ApiExceptionsOperationCustomizer implements OperationCustomizer {

    private static final String JSON_MEDIA_TYPE = "application/json";

    /** 핸들러의 @ApiExceptions를 읽어 나열된 코드들을 상태코드별 예시로 묶어 응답에 첨부한다. */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiExceptions annotation = handlerMethod.getMethodAnnotation(ApiExceptions.class);
        if (annotation == null) {
            return operation;
        }

        Map<Integer, List<ExampleHolder>> holdersByStatus = Arrays.stream(annotation.value())
                .flatMap(enumClass -> Arrays.stream(enumClass.getEnumConstants()))
                .map(this::toExampleHolder)
                .collect(Collectors.groupingBy(ExampleHolder::status));

        addExamples(operation.getResponses(), holdersByStatus);
        return operation;
    }

    /** ErrorCode 하나를 상태코드·코드명·ErrorResponse 예시를 담은 홀더로 변환한다. */
    private ExampleHolder toExampleHolder(ErrorCode errorCode) {
        Example example = new Example()
                .description(errorCode.getMessage())
                .value(ErrorResponse.of(errorCode, ""));
        return new ExampleHolder(errorCode.getStatus().value(), errorCode.getCode(), example);
    }

    /** 상태코드별로 묶인 예시들을 각 상태코드의 JSON 응답에 등록한다. */
    private void addExamples(ApiResponses responses, Map<Integer, List<ExampleHolder>> holdersByStatus) {
        holdersByStatus.forEach((status, holders) -> {
            MediaType mediaType = new MediaType();
            holders.forEach(holder -> mediaType.addExamples(holder.name(), holder.example()));

            ApiResponse apiResponse = new ApiResponse()
                    .description("에러 응답")
                    .content(new Content().addMediaType(JSON_MEDIA_TYPE, mediaType));
            responses.addApiResponse(String.valueOf(status), apiResponse);
        });
    }

    private record ExampleHolder(int status, String name, Example example) {
    }
}
