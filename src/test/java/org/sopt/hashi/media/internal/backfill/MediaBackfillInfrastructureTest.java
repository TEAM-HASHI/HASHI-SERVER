package org.sopt.hashi.media.internal.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

class MediaBackfillInfrastructureTest {

    private static final Path TEMPLATE = Path.of("infra/media/template.yaml");
    private static final String POLICY = "SpringApplicationBackfillPolicy";

    @Test
    void 추가_권한은_기본_false이고_명시한_조건에서만_기존_Spring_role에_연결한다() throws IOException {
        Node template = parse(TEMPLATE);
        Node parameter = field(template, "Parameters", "BackfillAccessEnabled");
        assertThat(value(field(parameter, "Type"))).isEqualTo("String");
        assertThat(value(field(parameter, "Default"))).isEqualTo("false");
        assertThat(values(field(parameter, "AllowedValues"))).containsExactly("true", "false");
        Node condition = field(template, "Conditions", "IsBackfillAccessEnabled");
        assertThat(condition.getTag().getValue()).isEqualTo("!Equals");
        List<Node> operands = sequence(condition);
        assertThat(operands).hasSize(2);
        assertTagged(operands.getFirst(), "!Ref", "BackfillAccessEnabled");
        assertThat(value(operands.getLast())).isEqualTo("true");
        Node policy = field(template, "Resources", POLICY);
        assertThat(value(field(policy, "Condition"))).isEqualTo("IsBackfillAccessEnabled");
        List<Node> roles = sequence(field(policy, "Properties", "Roles"));
        assertThat(roles).hasSize(1);
        assertTagged(roles.getFirst(), "!Ref", "SpringApplicationRoleName");
    }

    @Test
    void legacy_source에는_지정한_delivery_bucket의_읽기만_허용한다() throws IOException {
        List<Node> statements = statements();
        assertThat(statements).hasSize(2);
        Node read = statements.getFirst();
        assertThat(keys(read)).containsExactlyInAnyOrder("Sid", "Effect", "Action", "Resource");
        assertThat(value(field(read, "Effect"))).isEqualTo("Allow");
        assertThat(values(field(read, "Action"))).containsExactlyInAnyOrder("s3:GetObject", "s3:GetObjectVersion");
        assertTagged(field(read, "Resource"), "!Sub", "arn:${AWS::Partition}:s3:::${DeliveryBucketName}/*");
        Node bucketParameter = field(parse(TEMPLATE), "Parameters", "DeliveryBucketName");
        String pattern = value(field(bucketParameter, "AllowedPattern"));
        assertThat("valid-delivery-bucket").matches(pattern);
        assertThat("other-bucket/*").doesNotMatch(pattern);
        assertThat("*").doesNotMatch(pattern);
    }

    @Test
    void 원본_version_조회는_media_originals_prefix로_제한하고_삭제나_공개_권한을_주지_않는다()
            throws IOException {
        Node list = statements().getLast();
        assertThat(keys(list)).containsExactlyInAnyOrder("Sid", "Effect", "Action", "Resource", "Condition");
        assertThat(value(field(list, "Effect"))).isEqualTo("Allow");
        assertThat(values(field(list, "Action"))).containsExactly("s3:ListBucketVersions");
        assertTagged(field(list, "Resource"), "!GetAtt", "OriginalImageBucket.Arn");
        assertThat(value(field(list, "Condition", "StringLike", "s3:prefix"))).isEqualTo("media/originals/*");
    }

    @Test
    void dev와_prod_예시_모두_backfill_추가_권한을_기본_비활성화한다() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String environment : List.of("dev", "prod")) {
            JsonNode parameters = mapper.readTree(
                    Path.of("infra/media/parameters/" + environment + ".example.json").toFile());
            int matches = 0;
            for (JsonNode parameter : parameters) {
                if ("BackfillAccessEnabled".equals(parameter.path("ParameterKey").asText())) {
                    assertThat(parameter.path("ParameterValue").asText()).isEqualTo("false");
                    matches++;
                }
            }
            assertThat(matches).isEqualTo(1);
        }
    }

    @Test
    void 배포_workflow는_추가_권한_flag를_검증하고_false_기본값을_명시해서_전달한다() throws IOException {
        Node workflow = parse(Path.of(".github/workflows/deploy-image-pipeline.yml"));
        Node validate = step(workflow, "Validate deployment approval and configuration");
        Node deploy = step(workflow, "Deploy SAM stack");
        for (Node step : List.of(validate, deploy)) {
            assertThat(value(field(step, "env", "MEDIA_BACKFILL_ACCESS_ENABLED")))
                    .isEqualTo("${{ vars.MEDIA_BACKFILL_ACCESS_ENABLED }}");
        }
        assertThat(value(field(validate, "run")))
                .contains("backfill_enabled=\"${MEDIA_BACKFILL_ACCESS_ENABLED:-false}\"")
                .contains("[[ \"${backfill_enabled}\" != \"true\" && \"${backfill_enabled}\" != \"false\" ]]");
        assertThat(value(field(deploy, "run")))
                .contains("BackfillAccessEnabled=${MEDIA_BACKFILL_ACCESS_ENABLED:-false}");
    }

    @Test
    void IAM_허용과_별도로_Spring_backfill_실행은_기본_비활성화한다() throws IOException {
        Node application = parse(Path.of("src/main/resources/application.yml"));
        assertThat(value(field(application, "hashi", "media", "backfill", "enabled")))
                .isEqualTo("${AWS_MEDIA_BACKFILL_ENABLED:false}");
    }

    @Test
    void Linux_CI는_실행권한에_의존하지_않고_Gradle_wrapper로_IAM_계약을_검증한다() throws IOException {
        Node workflow = parse(Path.of(".github/workflows/ci-image-pipeline-infra.yml"));
        Node verify = sequence(field(workflow, "jobs", "validate", "steps")).stream()
                .filter(step -> "Verify temporary backfill permissions".equals(value(field(step, "name"))))
                .findFirst().orElseThrow();
        assertThat(value(field(verify, "run")))
                .startsWith("bash ./gradlew test ")
                .contains("--tests org.sopt.hashi.media.internal.backfill.MediaBackfillInfrastructureTest")
                .contains("--no-daemon");
    }

    private List<Node> statements() throws IOException {
        return sequence(field(parse(TEMPLATE), "Resources", POLICY, "Properties", "PolicyDocument", "Statement"));
    }

    private Node step(Node workflow, String name) {
        return sequence(field(workflow, "jobs", "deploy", "steps")).stream()
                .filter(step -> name.equals(value(field(step, "name")))).findFirst().orElseThrow();
    }

    private Node parse(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            // compose는 CloudFormation intrinsic tag를 객체로 실행하지 않고 YAML node로만 읽는다.
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
