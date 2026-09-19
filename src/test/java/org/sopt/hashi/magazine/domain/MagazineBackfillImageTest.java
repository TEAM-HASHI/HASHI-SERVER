package org.sopt.hashi.magazine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class MagazineBackfillImageTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void backfill은_대상_UUID만_추가하고_두_key와_표시_정보를_보존한다(boolean banner) {
        Magazine magazine = magazine();
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 4, 1, 0);
        ReflectionTestUtils.setField(magazine, "id", 10L);
        ReflectionTestUtils.setField(magazine, "createdAt", createdAt);
        UUID assetId = UUID.randomUUID();

        assertThat(attach(magazine, banner, banner ? "banner.jpg" : "thumbnail.jpg", assetId)).isTrue();

        assertThat(magazine.getBannerImageAssetId()).isEqualTo(banner ? assetId : null);
        assertThat(magazine.getThumbnailImageAssetId()).isEqualTo(banner ? null : assetId);
        assertThat(magazine.getBannerKey()).isEqualTo("banner.jpg");
        assertThat(magazine.getThumbnailKey()).isEqualTo("thumbnail.jpg");
        assertThat(magazine.getId()).isEqualTo(10L);
        assertThat(magazine.getTitle()).isEqualTo("매거진");
        assertThat(magazine.getInstagramRedirectUrl()).isEqualTo("https://www.instagram.com/p/test/");
        assertThat(magazine.getCreatedAt()).isEqualTo(createdAt);
        assertThat(magazine.isDeleted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 다른_key나_이미_연결된_슬롯은_덮어쓰지_않는다(boolean banner) {
        Magazine magazine = magazine();
        String key = banner ? "banner.jpg" : "thumbnail.jpg";
        UUID original = UUID.randomUUID();

        assertThat(attach(magazine, banner, "changed.jpg", original)).isFalse();
        assertThat(attach(magazine, banner, key, original)).isTrue();
        assertThat(attach(magazine, banner, key, UUID.randomUUID())).isFalse();
        assertThat(banner ? magazine.getBannerImageAssetId() : magazine.getThumbnailImageAssetId())
                .isEqualTo(original);
    }

    @Test
    void 삭제된_매거진과_빈_legacy_key는_연결하지_않는다() {
        Magazine deleted = magazine();
        ReflectionTestUtils.setField(deleted, "deleted", true);
        assertThat(deleted.attachBackfilledBanner("banner.jpg", UUID.randomUUID())).isFalse();
        assertThat(deleted.attachBackfilledThumbnail("thumbnail.jpg", UUID.randomUUID())).isFalse();
        Magazine blank = Magazine.create("빈 key", " ", " ", "https://example.test/");
        assertThat(blank.attachBackfilledBanner(" ", UUID.randomUUID())).isFalse();
        assertThat(blank.attachBackfilledThumbnail(" ", UUID.randomUUID())).isFalse();
        Magazine noLegacy = Magazine.create(
                "asset", null, UUID.randomUUID(), null, UUID.randomUUID(), "https://example.test/");
        assertThat(noLegacy.attachBackfilledBanner(null, UUID.randomUUID())).isFalse();
        assertThat(noLegacy.attachBackfilledThumbnail(null, UUID.randomUUID())).isFalse();
    }

    @Test
    void asset_ID가_없으면_슬롯을_변경하지_않고_거부한다() {
        Magazine magazine = magazine();
        assertThatThrownBy(() -> magazine.attachBackfilledBanner("banner.jpg", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> magazine.attachBackfilledThumbnail("thumbnail.jpg", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(magazine.getBannerImageAssetId()).isNull();
        assertThat(magazine.getThumbnailImageAssetId()).isNull();
    }

    private boolean attach(Magazine magazine, boolean banner, String key, UUID assetId) {
        return banner ? magazine.attachBackfilledBanner(key, assetId)
                : magazine.attachBackfilledThumbnail(key, assetId);
    }

    private Magazine magazine() {
        return Magazine.create("매거진", "banner.jpg", "thumbnail.jpg", "https://www.instagram.com/p/test/");
    }
}
