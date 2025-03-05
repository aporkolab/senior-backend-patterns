package com.aporkolab.patterns.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Example test demonstrating Testcontainers usage.
 * 
 * This test runs against a real PostgreSQL database.
 * No mocks, no H2 — real behavior.
 */
class ExampleIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldConnectToRealPostgres() throws Exception {
        try (var connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {

            assertThat(rs.next()).isTrue();
            String version = rs.getString(1);

            assertThat(version).containsIgnoringCase("PostgreSQL");
            System.out.println("Connected to: " + version);
        }
    }

    @Test
    void shouldSupportPostgresSpecificFeatures() throws Exception {
        try (var connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {

            // Create table with JSONB (PostgreSQL-specific)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_events (
                    id SERIAL PRIMARY KEY,
                    data JSONB NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);

            // Insert with JSON
            stmt.execute("""
                INSERT INTO test_events (data) 
                VALUES ('{"type": "test", "value": 42}'::jsonb)
                """);

            // Query with JSON operators (wouldn't work in H2)
            try (ResultSet rs = stmt.executeQuery("""
                SELECT data->>'type' as event_type, 
                       (data->>'value')::int as event_value
                FROM test_events
                """)) {

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("event_type")).isEqualTo("test");
                assertThat(rs.getInt("event_value")).isEqualTo(42);
            }
        }
    }

    @Test
    void shouldHandleTransactionIsolation() throws Exception {
        // This test verifies real transaction behavior
        // H2 would pass but with different semantics

        try (var conn1 = dataSource.getConnection();
             var conn2 = dataSource.getConnection()) {

            conn1.setAutoCommit(false);
            conn2.setAutoCommit(false);

            try (Statement stmt1 = conn1.createStatement()) {
                stmt1.execute("""
                    CREATE TABLE IF NOT EXISTS counter (
                        id INT PRIMARY KEY,
                        value INT NOT NULL
                    )
                    """);
                stmt1.execute("INSERT INTO counter VALUES (1, 0) ON CONFLICT (id) DO NOTHING");
                conn1.commit();
            }

            // Connection 1 updates
            try (Statement stmt1 = conn1.createStatement()) {
                stmt1.execute("UPDATE counter SET value = value + 1 WHERE id = 1");
                // Not committed yet
            }

            // Connection 2 reads (should see old value with READ COMMITTED)
            try (Statement stmt2 = conn2.createStatement();
                 ResultSet rs = stmt2.executeQuery("SELECT value FROM counter WHERE id = 1")) {
                rs.next();
                assertThat(rs.getInt("value")).isEqualTo(0); // Old value
            }

            conn1.commit();

            // Now connection 2 should see new value
            try (Statement stmt2 = conn2.createStatement();
                 ResultSet rs = stmt2.executeQuery("SELECT value FROM counter WHERE id = 1")) {
                rs.next();
                assertThat(rs.getInt("value")).isEqualTo(1); // New value
            }

            conn2.commit();
        }
    }
}
