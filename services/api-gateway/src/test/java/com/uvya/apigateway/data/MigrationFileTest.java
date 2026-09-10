package com.uvya.apigateway.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class MigrationFileTest {
    @Test
    void authenticationMigrationRemainsTheSingleOwnerOfPhaseTwoTables() throws IOException {
        String migration = migration("/db/migration/V1__authentication_foundation.sql");
        assertThat(migration).contains("CREATE TABLE app_users", "CREATE TABLE devices",
                "CREATE TABLE auth_sessions", "CREATE TABLE auth_audit_logs");
    }

    @Test
    void dataMigrationAddsEveryMissingTableWithoutDuplicatingPhaseTwoTables() throws IOException {
        String migration = migration("/db/migration/V2__data_foundation.sql");
        for (String table : List.of("chats", "chat_members", "messages", "message_reactions",
                "message_versions", "user_inbox", "read_states", "blocked_users", "idempotency_keys",
                "outbox_events")) {
            assertThat(migration).contains("CREATE TABLE " + table);
        }
        assertThat(migration).doesNotContain("CREATE TABLE app_users", "CREATE TABLE devices",
                "CREATE TABLE auth_sessions", "CREATE TABLE auth_audit_logs");
        assertThat(migration).contains("UNIQUE (chat_id, sequence)",
                "UNIQUE (chat_id, client_message_id, sender_id)",
                "WHERE published_at IS NULL");
    }

    @Test
    void messageMigrationContainsTheRequiredOrderingAndLifecycleColumns() throws IOException {
        String migration = migration("/db/migration/V2__data_foundation.sql");
        for (String column : List.of("message_id", "chat_id", "sender_id", "sender_device_id",
                "client_message_id", "sequence", "message_type", "body", "reply_to_message_id",
                "forwarded_from_message_id", "created_at", "edited_at", "deleted_at", "version", "status")) {
            assertThat(migration).contains(column);
        }
    }

    private String migration(String resource) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
