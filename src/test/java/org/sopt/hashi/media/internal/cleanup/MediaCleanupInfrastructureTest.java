package org.sopt.hashi.media.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

class MediaCleanupInfrastructureTest {

    private static final Path TEMPLATE = Path.of("infra/media/template.yaml");
    private static final String POLICY = "SpringApplicationCleanupPolicy";

    @Test
    void 삭제_권한은_기본_false이며_별도_조건으로만_기존_Spring_role에_연결한다() throws IOException {
        Node template = parse(TEMPLATE);
        Node parameter = field(template, "Parameters", "CleanupAccessEnabled");
        assertThat(value(field(parameter, "Type"))).isEqualTo("String");
        assertThat(value(field(parameter, "Default"))).isEqualTo("false");
        assertThat(values(field(parameter, "AllowedValues"))).containsExactly("true", "false");
        Node condition = field(template, "Conditions", "IsCleanupAccessEnabled");
        assertThat(condition.getTag().getValue()).isEqualTo("!Equals");
        List<Node> operands = sequence(condition);
        assertThat(operands).hasSize(2);
        assertTagged(operands.getFirst(), "!Ref", "CleanupAccessEnabled");
        assertThat(value(operands.getLast())).isEqualTo("true");

        Node policy = field(template, "Resources", POLICY);
        assertThat(keys(policy)).containsExactlyInAnyOrder("Type", "Condition", "Properties");
        assertThat(value(field(policy, "Type"))).isEqualTo("AWS::IAM::ManagedPolicy");
        assertThat(value(field(policy, "Condition"))).isEqualTo("IsCleanupAccessEnabled");
        Node properties = field(policy, "Properties");
        assertThat(keys(properties)).containsExactlyInAnyOrder("Description", "Roles", "PolicyDocument");
        List<Node> roles = sequence(field(properties, "Roles"));
        assertThat(roles).hasSize(1);
        assertTagged(roles.getFirst(), "!Ref", "SpringApplicationRoleName");
    }

    @Test
    void version_목록은_지정한_두_bucket의_media_prefix로만_조회한다() throws IOException {
        assertListing("ListPrivateOriginalVersionsForCleanup", "!GetAtt", "OriginalImageBucket.Arn",
                "media/originals/*");
        assertListing("ListRenditionVersionsForCleanup", "!Sub",
                "arn:${AWS::Partition}:s3:::${DeliveryBucketName}", "media/renditions/*");
    }

    @Test
    void 삭제는_media_원본과_파생본에만_허용하고_추가_statement나_보존_우회_권한은_없다() throws IOException {
        List<Node> statements = statements();
        assertThat(statements.stream().map(statement -> value(field(statement, "Sid"))))
                .containsExactlyInAnyOrder("ListPrivateOriginalVersionsForCleanup", "ListRenditionVersionsForCleanup",
                        "DeletePrivateOriginalVersionsForCleanup", "DeleteRenditionVersionsForCleanup");
        assertDeletion("DeletePrivateOriginalVersionsForCleanup", "${OriginalImageBucket.Arn}/media/originals/*");
        assertDeletion("DeleteRenditionVersionsForCleanup",
                "arn:${AWS::Partition}:s3:::${DeliveryBucketName}/media/renditions/*");
        Node document = field(parse(TEMPLATE), "Resources", POLICY, "Properties", "PolicyDocument");
        assertThat(keys(document)).containsExactlyInAnyOrder("Version", "Statement");
        assertThat(value(field(document, "Version"))).isEqualTo("2012-10-17");
    }

    @Test
    void worker와_기존_policy에는_삭제_권한이나_전체_S3_허용을_추가하지_않는다() throws IOException {
        MappingNode resources = (MappingNode) field(parse(TEMPLATE), "Resources");
        for (NodeTuple resource : resources.getValue()) {
            if (!POLICY.equals(value(resource.getKeyNode()))) {
                List<String> actions = new ArrayList<>();
                collectAllowedActions(resource.getValueNode(), actions);
                assertThat(actions).as("actions outside cleanup policy")
                        .doesNotContain("s3:DeleteObject", "s3:DeleteObjectVersion", "s3:*", "*");
            }
        }
    }

    @Test
    void 두_환경의_권한_예시와_Spring_예시는_삭제가_꺼진_상태다() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String environment : List.of("dev", "prod")) {
            JsonNode parameters = mapper.readTree(
                    Path.of("infra/media/parameters/" + environment + ".example.json").toFile());
            int matches = 0;
            for (JsonNode parameter : parameters) {
                if ("CleanupAccessEnabled".equals(parameter.path("ParameterKey").asText())) {
                    assertThat(parameter.path("ParameterValue").asText()).isEqualTo("false");
                    matches++;
                }
            }
            assertThat(matches).isEqualTo(1);
        }
        String example = Files.readString(Path.of(".env.dev.example"));
        assertThat(example).contains("AWS_MEDIA_CLEANUP_ENABLED=false", "AWS_MEDIA_CLEANUP_MODE=DRY_RUN");
        assertThat(example.lines().filter(line -> line.startsWith("AWS_MEDIA_CLEANUP_UPLOAD_SAFETY_WINDOW=")))
                .containsExactly("AWS_MEDIA_CLEANUP_UPLOAD_SAFETY_WINDOW=");
    }

    @Test
    void 배포는_삭제_flag의_줄바꿈과_boolean을_검증하고_false도_명시해서_전달한다() throws IOException {
        Node workflow = parse(Path.of(".github/workflows/deploy-image-pipeline.yml"));
        Node validate = step(workflow, "deploy", "Select and validate repository configuration");
        assertThat(value(field(validate, "env", "MEDIA_DEV_CLEANUP_ACCESS_ENABLED")))
                .isEqualTo("${{ vars.MEDIA_DEV_CLEANUP_ACCESS_ENABLED }}");
        String script = value(field(validate, "run"));
        assertThat(script)
                .contains("selected[MEDIA_CLEANUP_ACCESS_ENABLED]=\"${MEDIA_DEV_CLEANUP_ACCESS_ENABLED:-false}\"")
                .contains("for optional_name in MEDIA_ALARM_NOTIFICATION_TOPIC_ARN MEDIA_WORKER_EVENT_SOURCE_ENABLED "
                        + "MEDIA_BACKFILL_ACCESS_ENABLED MEDIA_CLEANUP_ACCESS_ENABLED; do")
                .contains("for flag_name in MEDIA_WORKER_EVENT_SOURCE_ENABLED "
                        + "MEDIA_BACKFILL_ACCESS_ENABLED MEDIA_CLEANUP_ACCESS_ENABLED; do")
                .contains("\"${flag_value}\" != \"true\" && \"${flag_value}\" != \"false\"")
                .contains("printf '%s=%s\\n' \"${name}\" \"${selected[${name}]}\" >> \"${GITHUB_ENV}\"");
        assertThat(value(field(step(workflow, "deploy", "Deploy dev stack"), "run")))
                .contains("CleanupAccessEnabled=${MEDIA_CLEANUP_ACCESS_ENABLED}");
    }

    @Test
    void IAM과_별도로_Spring은_비활성_DRY_RUN이며_유예_시간을_자동으로_정하지_않는다() throws IOException {
        Node cleanup = field(parse(Path.of("src/main/resources/application.yml")), "hashi", "media", "cleanup");
        assertThat(value(field(cleanup, "enabled"))).isEqualTo("${AWS_MEDIA_CLEANUP_ENABLED:false}");
        assertThat(value(field(cleanup, "mode"))).isEqualTo("${AWS_MEDIA_CLEANUP_MODE:DRY_RUN}");
        assertThat(value(field(cleanup, "upload-safety-window")))
                .isEqualTo("${AWS_MEDIA_CLEANUP_UPLOAD_SAFETY_WINDOW:}");
    }

    @Test
    void 인프라_CI와_배포_build에서_정리_권한_검사를_실행한다() throws IOException {
        Node ci = parse(Path.of(".github/workflows/ci-image-pipeline-infra.yml"));
        String testPath = "src/test/java/org/sopt/hashi/media/internal/cleanup/MediaCleanupInfrastructureTest.java";
        assertThat(values(field(ci, "on", "pull_request", "paths"))).contains(testPath);
        assertThat(values(field(ci, "on", "push", "paths"))).contains(testPath);
        assertThat(value(field(step(ci, "validate", "Verify backfill and migration contracts"), "run")))
                .startsWith("bash ./gradlew test ")
                .contains("--tests org.sopt.hashi.media.internal.cleanup.MediaCleanupInfrastructureTest");
        assertThat(value(field(step(ci, "validate", "Test and enforce worker IAM boundary"), "run")))
                .contains("node infra/media/scripts/check-template-guardrails.mjs");
        Node deploy = parse(Path.of(".github/workflows/deploy-image-pipeline.yml"));
        assertThat(value(field(step(deploy, "build", "Test infrastructure policy checkers"), "run")))
                .contains("node infra/media/scripts/check-template-guardrails.mjs");
    }

    private void assertListing(String sid, String tag, String resource, String prefix) throws IOException {
        Node statement = statement(sid);
        assertThat(keys(statement)).containsExactlyInAnyOrder("Sid", "Effect", "Action", "Resource", "Condition");
        assertThat(value(field(statement, "Effect"))).isEqualTo("Allow");
        assertThat(values(field(statement, "Action"))).containsExactly("s3:ListBucketVersions");
        assertTagged(field(statement, "Resource"), tag, resource);
        Node condition = field(statement, "Condition");
        assertThat(keys(condition)).containsExactly("StringLike");
        assertThat(keys(field(condition, "StringLike"))).containsExactly("s3:prefix");
        assertThat(value(field(condition, "StringLike", "s3:prefix"))).isEqualTo(prefix);
    }

    private void assertDeletion(String sid, String resource) throws IOException {
        Node statement = statement(sid);
        assertThat(keys(statement)).containsExactlyInAnyOrder("Sid", "Effect", "Action", "Resource");
        assertThat(value(field(statement, "Effect"))).isEqualTo("Allow");
        assertThat(values(field(statement, "Action"))).containsExactly("s3:DeleteObject", "s3:DeleteObjectVersion");
        assertTagged(field(statement, "Resource"), "!Sub", resource);
    }

    private void collectAllowedActions(Node node, List<String> actions) {
        if (node instanceof MappingNode mapping) {
            List<String> names = keys(node);
            if (names.contains("Effect") && "Allow".equals(value(field(node, "Effect")))) {
                assertThat(names).doesNotContain("NotAction");
                Node action = field(node, "Action");
                actions.addAll(action instanceof SequenceNode ? values(action) : List.of(value(action)));
            }
            mapping.getValue().forEach(tuple -> collectAllowedActions(tuple.getValueNode(), actions));
        } else if (node instanceof SequenceNode sequence) {
            sequence.getValue().forEach(child -> collectAllowedActions(child, actions));
        }
    }

    private List<Node> statements() throws IOException {
        return sequence(field(parse(TEMPLATE), "Resources", POLICY, "Properties", "PolicyDocument", "Statement"));
    }

    private Node statement(String sid) throws IOException {
        List<Node> matches = statements().stream()
                .filter(statement -> sid.equals(value(field(statement, "Sid")))).toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private Node step(Node workflow, String job, String name) {
        return sequence(field(workflow, "jobs", job, "steps")).stream()
                .filter(step -> name.equals(value(field(step, "name")))).findFirst().orElseThrow();
    }

    private Node parse(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return new Yaml().compose(reader);
        }
    }

    private Node field(Node node, String... path) {
        Node current = node;
        for (String name : path) {
            assertThat(current).isInstanceOf(MappingNode.class);
            List<NodeTuple> matches = ((MappingNode) current).getValue().stream()
                    .filter(tuple -> name.equals(value(tuple.getKeyNode()))).toList();
            assertThat(matches).as("unique YAML field %s", name).hasSize(1);
            current = matches.getFirst().getValueNode();
        }
        return current;
    }

    private List<String> keys(Node node) {
        assertThat(node).isInstanceOf(MappingNode.class);
        return ((MappingNode) node).getValue().stream().map(tuple -> value(tuple.getKeyNode())).toList();
    }

    private List<Node> sequence(Node node) {
        assertThat(node).isInstanceOf(SequenceNode.class);
        return ((SequenceNode) node).getValue();
    }

    private List<String> values(Node node) {
        return sequence(node).stream().map(this::value).toList();
    }

    private String value(Node node) {
        assertThat(node).isInstanceOf(ScalarNode.class);
        return ((ScalarNode) node).getValue();
    }

    private void assertTagged(Node node, String tag, String expected) {
        assertThat(node.getTag().getValue()).isEqualTo(tag);
        assertThat(value(node)).isEqualTo(expected);
    }
}
