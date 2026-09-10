package com.uvya.apigateway.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class EventContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("eventSchemas")
    void everyVersionOneSchemaDeclaresTheCommonEnvelope(String resource, String eventType) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            JsonNode schema = objectMapper.readTree(stream);
            assertThat(schema.at("/allOf/0/$ref").asText()).isEqualTo("./event-envelope.v1.schema.json");
            assertThat(schema.at("/allOf/1/properties/eventType/const").asText()).isEqualTo(eventType);
            assertThat(schema.at("/allOf/1/properties/payload/type").asText()).isEqualTo("object");
        }
    }

    private static Stream<Arguments> eventSchemas() {
        return Stream.of(
                Arguments.of("/contracts/events/v1/message.created.v1.schema.json", "message.created"),
                Arguments.of("/contracts/events/v1/message.edited.v1.schema.json", "message.edited"),
                Arguments.of("/contracts/events/v1/message.deleted.v1.schema.json", "message.deleted"),
                Arguments.of("/contracts/events/v1/message.delivered.v1.schema.json", "message.delivered"),
                Arguments.of("/contracts/events/v1/message.read.v1.schema.json", "message.read"),
                Arguments.of("/contracts/events/v1/message.reaction.added.v1.schema.json",
                        "message.reaction.added"),
                Arguments.of("/contracts/events/v1/message.reaction.removed.v1.schema.json",
                        "message.reaction.removed"),
                Arguments.of("/contracts/events/v1/chat.created.v1.schema.json", "chat.created"),
                Arguments.of("/contracts/events/v1/group.member.added.v1.schema.json", "group.member.added"),
                Arguments.of("/contracts/events/v1/group.member.removed.v1.schema.json", "group.member.removed"),
                Arguments.of("/contracts/events/v1/notification.requested.v1.schema.json", "notification.requested"));
    }
}
