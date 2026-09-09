package org.sopt.hashi.user.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class UserProfileBackfillWorkflowTest {

    @Test
    void hosted_CI는_exact_HEAD와_실제_Docker를_사용하고_MySQL_skip을_거부한다() throws IOException {
        Map<?, ?> workflow;
        try (var input = Files.newInputStream(Path.of(
                ".github/workflows/ci-restaurant-media-backfill.yml"))) {
            workflow = new Yaml().load(input);
        }
        Map<?, ?> permissions = (Map<?, ?>) workflow.get("permissions");
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        Map<?, ?> verify = (Map<?, ?>) jobs.get("verify");
        List<?> steps = (List<?>) verify.get("steps");
        Map<?, ?> checkout = (Map<?, ?>) steps.getFirst();
        Map<?, ?> checkoutInputs = (Map<?, ?>) checkout.get("with");
        List<String> commands = steps.stream()
                .map(step -> (Map<?, ?>) step)
                .filter(step -> step.containsKey("run"))
                .map(step -> (String) step.get("run"))
                .toList();

        assertThat(permissions).hasSize(1);
        assertThat(permissions.get("contents")).isEqualTo("read");
        assertThat((String) checkout.get("uses")).matches("actions/checkout@[0-9a-f]{40}");
        assertThat(checkoutInputs.get("ref")).isEqualTo("${{ github.event.pull_request.head.sha }}");
        assertThat(commands).contains("docker info", "bash ./gradlew clean build --no-daemon");
        assertThat(commands).anySatisfy(command -> {
            assertThat(command).contains("RestaurantMediaBackfillPersistenceIntegrationTest.xml",
                    "UserProfileBackfillPersistenceIntegrationTest.xml");
            assertThat(command).contains("\"failures\", \"errors\", \"skipped\"");
            assertThat(command).contains("== 0");
        });
    }
}
