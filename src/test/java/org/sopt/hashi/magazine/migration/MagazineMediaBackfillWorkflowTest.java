package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MagazineMediaBackfillWorkflowTest {

    @Test
    void hosted_CI는_매거진_변경과_exact_HEAD를_검증하고_세_MySQL_suite의_skip을_거부한다() throws IOException {
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
        // SnakeYAML의 YAML 1.1 모드에서는 on이 boolean key로 해석된다.
        Map<?, ?> events = (Map<?, ?>) (workflow.containsKey("on")
                ? workflow.get("on") : workflow.get(Boolean.TRUE));
        Map<?, ?> pullRequest = (Map<?, ?>) events.get("pull_request");
        List<String> paths = ((List<?>) pullRequest.get("paths")).stream().map(String::valueOf).toList();

        assertThat(paths).contains("src/main/java/org/sopt/hashi/magazine/**",
                "src/test/java/org/sopt/hashi/magazine/**", "docs/media/magazine-backfill-runbook.md",
                "src/main/java/org/sopt/hashi/shared/migration/**",
                "src/test/java/org/sopt/hashi/shared/migration/**");
        assertThat(permissions).hasSize(1);
        assertThat(permissions.get("contents")).isEqualTo("read");
        assertThat((String) checkout.get("uses")).matches("actions/checkout@[0-9a-f]{40}");
        assertThat(checkoutInputs.get("ref")).isEqualTo("${{ github.event.pull_request.head.sha }}");
        assertThat(commands).contains("docker info", "bash ./gradlew clean build --no-daemon");
        assertThat(commands).anySatisfy(command -> {
            assertThat(command).contains("RestaurantMediaBackfillPersistenceIntegrationTest.xml",
                    "UserProfileBackfillPersistenceIntegrationTest.xml",
                    "MagazineMediaBackfillPersistenceIntegrationTest.xml");
            assertThat(command).contains("suite.attrib[\"tests\"]", "> 0");
            assertThat(command).contains("\"failures\", \"errors\", \"skipped\"", "== 0");
        });
    }
}
