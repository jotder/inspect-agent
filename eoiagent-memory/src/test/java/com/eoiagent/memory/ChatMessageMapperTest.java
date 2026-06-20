package com.eoiagent.memory;

import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ChatMessageRecord <-> LC4j ChatMessage conversion preserves role and text for every role (AC6, role+text). */
class ChatMessageMapperTest {

    @ParameterizedTest
    @EnumSource(ChatRole.class)
    void roleAndTextRoundTripForEveryRole(ChatRole role) {
        ChatMessageRecord original = new ChatMessageRecord(role, "payload for " + role, Instant.EPOCH, Map.of());

        ChatMessage lc4j = ChatMessageMapper.toLc4j(original);
        ChatMessageRecord back = ChatMessageMapper.toRecord(lc4j);

        assertThat(back.role()).isEqualTo(role);
        assertThat(back.text()).isEqualTo("payload for " + role);
    }

    @Test
    void listMappingPreservesOrder() {
        var records = java.util.List.of(
                new ChatMessageRecord(ChatRole.SYSTEM, "sys", Instant.EPOCH, Map.of()),
                new ChatMessageRecord(ChatRole.USER, "u", Instant.EPOCH, Map.of()),
                new ChatMessageRecord(ChatRole.ASSISTANT, "a", Instant.EPOCH, Map.of()));

        var back = ChatMessageMapper.toRecords(ChatMessageMapper.toLc4j(records));

        assertThat(back).extracting(ChatMessageRecord::text).containsExactly("sys", "u", "a");
        assertThat(back).extracting(ChatMessageRecord::role)
                .containsExactly(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT);
    }
}
