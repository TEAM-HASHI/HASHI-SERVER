package org.sopt.hashi.media.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.security.JwtAuthenticationFilter;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.dto.CompleteMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusesResponse;
import org.sopt.hashi.media.dto.MediaUploadResponse;
import org.sopt.hashi.media.service.MediaAssetService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = MediaAssetController.class,
        excludeFilters = @Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {OnboardingJwtIssuer.class, JwtAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MediaAssetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaAssetService mediaAssetService;

    @Test
    void asset과_업로드_URL을_공통_응답으로_발급한다() throws Exception {
        UUID assetId = UUID.randomUUID();
        CreateMediaAssetsResponse response = new CreateMediaAssetsResponse(List.of(
                new MediaUploadResponse(
                        assetId,
                        ImageProcessingStatus.PENDING_UPLOAD,
                        "https://s3.example.com/presigned",
                        Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"),
                        1024L,
                        300,
                        "PUT"
                )
        ));
        given(mediaAssetService.createAssets(org.mockito.ArgumentMatchers.any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/media/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "REVIEW",
                                  "files": [
                                    {"contentType": "image/jpeg", "fileSize": 1024}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploads[0].assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.data.uploads[0].status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.data.uploads[0].requiredHeaders.If-None-Match").value("*"))
                .andExpect(jsonPath("$.data.uploads[0].expectedContentLength").value(1024))
                .andExpect(jsonPath("$.data.uploads[0].uploadMethod").value("PUT"));
    }

    @Test
    void 빈_files_요청은_서비스를_호출하지_않고_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/media/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose": "REVIEW", "files": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"));

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    void complete는_요청_순서의_상태를_반환한다() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        given(mediaAssetService.completeAssets(new CompleteMediaAssetsRequest(List.of(firstId, secondId))))
                .willReturn(new MediaAssetStatusesResponse(List.of(
                        new MediaAssetStatusResponse(firstId, ImageProcessingStatus.PROCESSING),
                        new MediaAssetStatusResponse(secondId, ImageProcessingStatus.READY)
                )));

        mockMvc.perform(post("/api/v1/media/assets/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetIds": ["%s", "%s"]}
                                """.formatted(firstId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].assetId").value(firstId.toString()))
                .andExpect(jsonPath("$.data.assets[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assets[1].assetId").value(secondId.toString()))
                .andExpect(jsonPath("$.data.assets[1].status").value("READY"));
    }

    @Test
    void 쉼표로_구분한_assetIds를_순서대로_서비스에_전달한다() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        given(mediaAssetService.getAssetStatuses(List.of(firstId, secondId)))
                .willReturn(new MediaAssetStatusesResponse(List.of(
                        new MediaAssetStatusResponse(firstId, ImageProcessingStatus.PROCESSING),
                        new MediaAssetStatusResponse(secondId, ImageProcessingStatus.READY)
                )));

        mockMvc.perform(get("/api/v1/media/assets")
                        .param("assetIds", "%s,%s".formatted(firstId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].assetId").value(firstId.toString()))
                .andExpect(jsonPath("$.data.assets[1].assetId").value(secondId.toString()));

        verify(mediaAssetService).getAssetStatuses(List.of(firstId, secondId));
    }

    @Test
    void 다른_actor의_asset은_404_응답으로_존재를_숨긴다() throws Exception {
        UUID assetId = UUID.randomUUID();
        given(mediaAssetService.getAssetStatuses(List.of(assetId)))
                .willThrow(new BusinessException(MediaErrorCode.ASSET_NOT_FOUND));

        mockMvc.perform(get("/api/v1/media/assets")
                        .param("assetIds", assetId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA-001"));
    }
}
