package org.sopt.hashi.upload.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IssuePresignedUrlsRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void 파일은_최소_한_개_이상이어야_한다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest("review", Collections.emptyList());

        Set<ConstraintViolation<IssuePresignedUrlsRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("files");
    }

    @Test
    void 파일은_최대_열_개까지_요청할_수_있다() {
        List<IssuePresignedUrlsRequest.FileRequest> files = IntStream.range(0, 11)
                .mapToObj(index -> new IssuePresignedUrlsRequest.FileRequest("image/jpeg", 1024L))
                .toList();
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest("review", files);

        Set<ConstraintViolation<IssuePresignedUrlsRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("files");
    }

    @Test
    void 파일_항목의_필수값을_검증한다() {
        IssuePresignedUrlsRequest request = new IssuePresignedUrlsRequest(
                "review",
                List.of(new IssuePresignedUrlsRequest.FileRequest(" ", 0L))
        );

        Set<ConstraintViolation<IssuePresignedUrlsRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("files[0].contentType", "files[0].fileSize");
    }
}
