package org.sopt.hashi.admin.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.sopt.hashi.admin.dto.AdminUserListResponse;
import org.sopt.hashi.admin.dto.AdminUserResponse;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.user.AdminUserInfo;
import org.sopt.hashi.user.AdminUserSortType;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * 어드민 회원 관리 — 진입점 모듈이라 도메인 로직 없이 {@link UserPort}로 위임하고
 * 응답 DTO 변환만 한다(architecture.md §9).
 */
@Service
public class AdminUserService {

    private final UserPort userPort;
    private final MediaPort mediaPort;

    public AdminUserService(UserPort userPort, MediaPort mediaPort) {
        this.userPort = userPort;
        this.mediaPort = mediaPort;
    }

    /** 회원 목록 — offset 페이지네이션. 정렬은 sortType, keyword가 있으면 닉네임 부분 일치 검색. */
    public AdminUserListResponse getUsers(AdminUserSortType sortType, String keyword, int page, int size) {
        Page<AdminUserInfo> users = userPort.findPageByAdmin(
                sortType, keyword, page, size);
        MediaProjection projection = loadProfileProjection(users.getContent().stream()
                .map(AdminUserInfo::profileImageReference)
                .toList());
        List<AdminUserResponse> content = users.getContent().stream()
                .map(info -> toResponse(info, projection))
                .toList();
        return AdminUserListResponse.from(users, content);
    }

    private MediaProjection loadProfileProjection(List<ImageReference> references) {
        List<MediaImageRequest> requests = references.stream()
                .filter(Objects::nonNull)
                .map(ImageReference::assetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR))
                .distinct()
                .toList();
        return requests.isEmpty()
                ? MediaProjection.empty()
                : new MediaProjection(mediaPort.findImages(requests));
    }

    private AdminUserResponse toResponse(AdminUserInfo info, MediaProjection projection) {
        ProjectedImage profileImage = project(info.profileImageReference(), projection);
        return AdminUserResponse.from(info, profileImage.url(), profileImage.image());
    }

    private ProjectedImage project(ImageReference reference, MediaProjection projection) {
        MediaImage image = reference == null || reference.assetId() == null
                ? null : projection.find(reference.assetId());
        MediaImageSelection selection = MediaImageSelection.from(reference, image);
        return new ProjectedImage(selection.url(), selection.image());
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(UUID assetId) {
            return images.get(new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {
    }
}
