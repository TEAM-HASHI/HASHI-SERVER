package org.sopt.hashi.user.collection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V31~V33 선행 결합 후 실행한다. missing V33을 target 설정만으로 통과 처리하지 않는다. */
@Testcontainers(disabledWithoutDocker = true)
class CollectionVersionMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");

    @Test
    void V33_기존_컬렉션과_관계를_보존하며_V34_변경번호를_초기화한다() throws Exception {
        Flyway throughV33 = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("33").cleanDisabled(false).load();
        throughV33.clean();
        throughV33.migrate();
        assertThat(throughV33.info().current().getVersion().getVersion()).isEqualTo("33");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("insert into restaurant_collection(id,user_id,name,color,description,visibility) "
                    + "values (1,1,'  여행  ','RED','   ','PUBLIC')");
            statement.executeUpdate("insert into saved_restaurant(collection_id,restaurant_id) values (1,101),(1,102)");
        }
        Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").validateOnMigrate(true).load();
        latest.migrate();
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("34");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select name,description,collection_version,"
                     + "(select count(*) from saved_restaurant where collection_id=1) as savedCount "
                     + "from restaurant_collection where id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("name")).isEqualTo("  여행  ");
            assertThat(result.getString("description")).isEqualTo("   ");
            assertThat(result.getLong("collection_version")).isZero();
            assertThat(result.wasNull()).isFalse();
            assertThat(result.getLong("savedCount")).isEqualTo(2);
        }
    }
}
