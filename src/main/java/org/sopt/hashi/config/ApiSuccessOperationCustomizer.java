package org.sopt.hashi.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.error.SuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * {@link ApiSuccess}가 붙은 핸들러에, 지정한 SuccessCode들을 상태코드별로 묶어
 * {@link SuccessResponse} 봉투 예시(code·message)로 Swagger 응답에 첨부한다
 * ({@link ApiExceptionsOperationCustomizer}의 성공 응답 짝).
 *
 * <p>어노테이션이 없으면 200 응답에 {@code CommonSuccessCode.OK}를 기본 문서화한다 —
 * 대부분의 조회 API가 OK를 쓰므로 비기본 코드 엔드포인트만 선언하면 된다.
 * 예시의 {@code data}는 항상 {@code null}이다(실제 구조는 Schema 탭이 담당).
 */
public class ApiSuccessOperationCustomizer implements GlobalOperationCustomizer {

    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String OK_STATUS = "200";

    /** 핸들러의 @ApiSuccess(들)을 읽어(없으면 200에 OK 기본) 상태코드별 예시로 응답에 첨부한다. */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiSuccess[] apiSuccesses = handlerMethod.getMethod().getAnnotationsByType(ApiSuccess.class);
        if (apiSuccesses.length == 0) {
            applyDefaultOk(operation, handlerMethod);
            return operation;
        }

        Map<Integer, List<SuccessCode>> codesByStatus = Arrays.stream(apiSuccesses)
                .flatMap(this::toSuccessCodes)
                .collect(Collectors.groupingBy(code -> code.getStatus().value()));

        codesByStatus.forEach((status, codes) -> attachExamples(operation.getResponses(), status, codes));
        return operation;
    }

    /** 어노테이션이 없는 SuccessResponse 반환 핸들러의 200 응답에 OK 봉투 예시를 기본 첨부한다. */
    private void applyDefaultOk(Operation operation, HandlerMethod handlerMethod) {
        boolean returnsEnvelope = SuccessResponse.class.isAssignableFrom(handlerMethod.getMethod().getReturnType());
        boolean hasOkResponse = operation.getResponses() != null && operation.getResponses().get(OK_STATUS) != null;
        if (returnsEnvelope && hasOkResponse) {
            attachExamples(operation.getResponses(), CommonSuccessCode.OK.getStatus().value(),
                    List.of(CommonSuccessCode.OK));
        }
    }

    /** 하나의 @ApiSuccess에서 지정한 코드(비우면 전체)만 골라낸다. */
    private Stream<SuccessCode> toSuccessCodes(ApiSuccess apiSuccess) {
        Class<? extends SuccessCode> enumType = apiSuccess.value();
        SuccessCode[] constants = enumType.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException("@ApiSuccess는 enum SuccessCode에만 사용할 수 있습니다: " + enumType.getName());
        }

        Set<String> wantedNames = Set.of(apiSuccess.codes());
        validateCodeNames(enumType, constants, wantedNames);

        return Arrays.stream(constants)
                .filter(code -> wantedNames.isEmpty() || wantedNames.contains(((Enum<?>) code).name()));
    }

    /** codes에 지정한 이름이 실제 enum에 없으면 문서 생성 시점에 즉시 실패시킨다(오타 방어). */
    private void validateCodeNames(Class<? extends SuccessCode> enumType, SuccessCode[] constants,
                                   Set<String> wantedNames) {
        Set<String> available = Arrays.stream(constants)
                .map(code -> ((Enum<?>) code).name())
                .collect(Collectors.toSet());
        for (String name : wantedNames) {
            if (!available.contains(name)) {
                throw new IllegalArgumentException(
                        "존재하지 않는 성공 코드입니다: " + enumType.getSimpleName() + "." + name);
            }
        }
    }

    /** 같은 상태코드의 성공 코드들을 해당 상태코드 JSON 응답에 예시로 등록한다. 기존 응답·스키마는 유지한다. */
    private void attachExamples(ApiResponses responses, int status, List<SuccessCode> codes) {
        String statusCode = String.valueOf(status);

        ApiResponse apiResponse = responses.get(statusCode);
        if (apiResponse == null) {
            apiResponse = new ApiResponse();
            responses.addApiResponse(statusCode, apiResponse);
        }
        apiResponse.setDescription(codes.stream()
                .map(SuccessCode::getMessage)
                .collect(Collectors.joining(" / ")));

        Content content = apiResponse.getContent();
        if (content == null) {
            content = new Content();
            apiResponse.setContent(content);
        }

        MediaType mediaType = content.computeIfAbsent(JSON_MEDIA_TYPE, key -> new MediaType());
        for (SuccessCode code : codes) {
            Example example = new Example()
                    .description(code.getMessage() + " — data 구조는 Schema 탭 참조")
                    .value(toExampleValue(code));
            mediaType.addExamples(code.getCode(), example);
        }
    }

    /**
     * 예시 봉투 JSON. {@link SuccessResponse} 인스턴스를 쓰면 springdoc 직렬화(NON_NULL)가
     * {@code data: null}을 떨궈 "data는 항상 포함" 계약과 어긋나므로, JsonNode로 null을 명시한다.
     */
    private ObjectNode toExampleValue(SuccessCode code) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("success", true);
        value.put("code", code.getCode());
        value.put("message", code.getMessage());
        value.putNull("data");
        return value;
    }
}
