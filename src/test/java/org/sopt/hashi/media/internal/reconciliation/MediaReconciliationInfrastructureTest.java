package org.sopt.hashi.media.internal.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MediaReconciliationInfrastructureTest {

    @Test
    void Spring과_환경변수_예시는_비활성_DRY_RUN_7일로_시작한다() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String environment = Files.readString(Path.of(".env.dev.example"));

        assertThat(application)
                .contains("enabled: ${AWS_MEDIA_RECONCILIATION_ENABLED:false}")
                .contains("mode: ${AWS_MEDIA_RECONCILIATION_MODE:DRY_RUN}")
                .contains("orphan-retention: ${AWS_MEDIA_RECONCILIATION_ORPHAN_RETENTION:7d}");
        assertThat(environment)
                .contains("AWS_MEDIA_RECONCILIATION_ENABLED=false")
                .contains("AWS_MEDIA_RECONCILIATION_MODE=DRY_RUN")
                .contains("AWS_MEDIA_RECONCILIATION_ORPHAN_RETENTION=7d");
    }

    @Test
    void 기존_cleanup_IAM은_두_media_prefix의_version_목록과_삭제로만_제한된다() throws IOException {
        String template = Files.readString(Path.of("infra/media/template.yaml"));

        assertThat(template)
                .contains("CleanupAccessEnabled:")
                .contains("Default: \"false\"")
                .contains("s3:ListBucketVersions")
                .contains("s3:prefix: \"media/originals/*\"")
                .contains("s3:prefix: \"media/renditions/*\"")
                .contains("${OriginalImageBucket.Arn}/media/originals/*")
                .contains("${DeliveryBucketName}/media/renditions/*");
    }
}
