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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.shared.response.ErrorResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * {@link ApiException}가 붙은 핸들러에, 지정한 ErrorCode들을
 * 상태코드별로 묶어 {@link ErrorResponse} 예시로 Swagger 응답에 첨부한다.
 */
public class ApiExceptionsOperationCustomizer implements OperationCustomizer {

    private static final String JSON_MEDIA_TYPE = "application/json";
    // Swagger 예시는 실제 요청이 아니므로 요청 경로가 없다. ErrorResponse.path 자리표시자로 빈 문자열을 쓴다.
    private static final String EXAMPLE_REQUEST_PATH = "";

    /** 핸들러의 @ApiException(들)을 읽어 지정 코드를 상태코드별 예시로 묶어 응답에 첨부한다. */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiException[] apiExceptions = handlerMethod.getMethod().getAnnotationsByType(ApiException.class);
        if (apiExceptions.length == 0) {
            return operation;
        }

        Map<Integer, List<ExampleHolder>> holdersByStatus = Arrays.stream(apiExceptions)
                .flatMap(this::toExampleHolders)
                .collect(Collectors.groupingBy(ExampleHolder::status));

        addExamples(operation.getResponses(), holdersByStatus);
        return operation;
    }

    /** 하나의 @ApiException에서 지정한 코드(비우면 전체)만 골라 예시 홀더로 변환한다. */
    private Stream<ExampleHolder> toExampleHolders(ApiException apiException) {
        Class<? extends ErrorCode> enumType = apiException.value();
        ErrorCode[] constants = enumType.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException("@ApiException은 enum ErrorCode에만 사용할 수 있습니다: " + enumType.getName());
        }

        Set<String> wantedNames = Set.of(apiException.codes());
        validateCodeNames(enumType, constants, wantedNames);

        return Arrays.stream(constants)
                .filter(code -> wantedNames.isEmpty() || wantedNames.contains(((Enum<?>) code).name()))
                .map(this::toExampleHolder);
    }

    /** codes에 지정한 이름이 실제 enum에 없으면 문서 생성 시점에 즉시 실패시킨다(오타 방어). */
    private void validateCodeNames(Class<? extends ErrorCode> enumType, ErrorCode[] constants, Set<String> wantedNames) {
        Set<String> available = Arrays.stream(constants)
                .map(code -> ((Enum<?>) code).name())
                .collect(Collectors.toSet());
        for (String name : wantedNames) {
            if (!available.contains(name)) {
                throw new IllegalArgumentException(
                        "존재하지 않는 에러 코드입니다: " + enumType.getSimpleName() + "." + name);
            }
        }
    }

    /** ErrorCode 하나를 상태코드·코드명·ErrorResponse 예시를 담은 홀더로 변환한다. */
    private ExampleHolder toExampleHolder(ErrorCode errorCode) {
        Example example = new Example()
                .description(errorCode.getMessage())
                .value(ErrorResponse.of(errorCode, EXAMPLE_REQUEST_PATH));
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
