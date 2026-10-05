package org.saket.eventbooking;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Every instant is stored as timestamptz, so its meaning doesn't depend on a session or JVM time zone. */
class TimestampColumnsTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void noTableUsesTimestampWithoutTimeZone() {
        var zoneless = jdbc.queryForList("""
                select table_name || '.' || column_name
                from information_schema.columns
                where table_schema = 'public'
                  and data_type = 'timestamp without time zone'
                  and table_name <> 'flyway_schema_history'
                """, String.class);
        assertThat(zoneless).isEmpty();
    }

    @Test
    void storedInstantsDoNotDependOnTheSessionTimeZone() {
        User user = createUser(Role.USER, true);
        Instant written = user.getCreatedAt();
        Instant first = null;

        for (String zone : new String[]{"UTC", "Asia/Kolkata", "America/Los_Angeles"}) {
            Instant read = jdbc.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set time zone '" + zone + "'");
                }
                try (var ps = c.prepareStatement("select created_at from users where id = ?")) {
                    ps.setObject(1, user.getId());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
                    }
                } finally {
                    try (var st = c.createStatement()) {
                        st.execute("reset time zone");
                    }
                }
            });
            // Postgres keeps microseconds (rounded), so allow 1µs against the Java value
            assertThat(read).as("read with session zone %s", zone).isCloseTo(written, within(1, ChronoUnit.MICROS));
            if (first == null) {
                first = read;
            }
            assertThat(read).as("read with session zone %s", zone).isEqualTo(first);
        }
    }
}
