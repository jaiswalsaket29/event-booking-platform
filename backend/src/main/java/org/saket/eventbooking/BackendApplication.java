package org.saket.eventbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * The JVM runs in UTC everywhere (dev, tests, containers). Times are {@code Instant}s stored as
 * {@code timestamptz}, and business-day logic uses {@code app.reporting.zone} explicitly, so the default
 * zone only affects log output and the JDBC session zone. Pinning it also matters for the driver: pgjdbc
 * sends the JVM zone to Postgres at connect time, and some hosts report legacy ids (e.g. Windows'
 * "Asia/Calcutta") that Postgres rejects.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BackendApplication.class, args);
    }
}
