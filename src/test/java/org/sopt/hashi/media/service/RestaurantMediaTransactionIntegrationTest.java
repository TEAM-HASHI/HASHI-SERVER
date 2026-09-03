package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({
        MediaPortImpl.class,
        MediaPurposeAccessPolicy.class,
        RestaurantService.class,
        TimeConfig.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:restaurant-media-transaction-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestaurantMediaTransactionIntegrationTest {

    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    @MockitoBean
    private MediaSpecRegistry mediaSpecRegistry;

    @MockitoBean
    private FileStorage fileStorage;

    @Test
    void 외부_transaction이_rollback되면_restaurant_연결과_media_claim이_함께_복원된다() {
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 1L));
        ImageAsset asset = imageAssetRepository.saveAndFlush(readyRestaurantAsset());
        Restaurant restaurant = restaurant();
        restaurant.replaceImages(List.of(
                RestaurantImage.createLegacy("restaurants/legacy.jpg", 1)));
        restaurantRepository.saveAndFlush(restaurant);
        Long restaurantId = restaurant.getId();
        Long legacyImageId = restaurant.getImages().getFirst().getId();

        transactionTemplate.executeWithoutResult(status -> {
            restaurantService.updateByAdmin(
                    restaurantId,
                    updateImageCommand(asset.getPublicId())
            );
            status.setRollbackOnly();
        });

        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow()
                .getBindingStatus()).isEqualTo(ImageBindingStatus.UNBOUND);
        Restaurant reloaded = restaurantRepository
                .findActiveByIdWithImages(restaurantId)
                .orElseThrow();
        assertThat(reloaded.getImages()).singleElement().satisfies(image -> {
            assertThat(image.getId()).isEqualTo(legacyImageId);
            assertThat(image.getFileKey()).isEqualTo("restaurants/legacy.jpg");
            assertThat(image.getImageAssetId()).isNull();
        });
    }

    private ImageAsset readyRestaurantAsset() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.RESTAURANT,
                MediaOwnerType.ADMIN,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5)
        );
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032,
                SOURCE_CHECKSUM);
        return asset;
    }

    private Restaurant restaurant() {
        return Restaurant.create(
                "트랜잭션 식당",
                "Transaction Restaurant",
                "식당 소개",
                "식당 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        );
    }

    private AdminRestaurantCommand updateImageCommand(UUID assetId) {
        return new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, List.of(new ImageCommand(null, assetId)),
                null, null, null, null
        );
    }
}
