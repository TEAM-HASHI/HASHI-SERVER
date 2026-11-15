package org.sopt.hashi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReviewMediaMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("review_media_migration");

    @Test
    void v45는_기존_ID와_key를_유지하며_asset_source와_unique_제약을_추가한다() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("44").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
                insert into review(id,reservation_id,restaurant_id,user_id,rating,content,deleted)
                values(901,901,901,901,5,'synthetic legacy review',false)
                """);
        jdbc.update("insert into review_image(id,review_id,file_key,display_order) values(501,901,'legacy.jpg',0)");
        var old = jdbc.queryForMap("select id,review_id,file_key,display_order from review_image where id=501");
        var result = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("45").load().migrate();
        assertThat(result.migrations).anySatisfy(migration -> assertThat(migration.version).isEqualTo("45"));
        assertThat(jdbc.queryForMap("select id,review_id,file_key,display_order from review_image where id=501"))
                .isEqualTo(old);
        assertThat(jdbc.queryForObject("select image_asset_id from review_image where id=501", String.class)).isNull();
        String asset = "11111111-1111-1111-1111-111111111111";
        jdbc.update("insert into review_image(review_id,image_asset_id,display_order) values(901,?,1)", asset);
        assertThatThrownBy(() -> jdbc.update("insert into review_image(review_id,image_asset_id,display_order) values(901,?,2)", asset))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into review_image(review_id,display_order) values(901,2)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into review_image(review_id,image_asset_id,display_order) values(901,'bad-id',2)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into review_image(review_id,file_key,display_order) values(901,'',2)"))
                .isInstanceOf(DataAccessException.class);
    }
}
