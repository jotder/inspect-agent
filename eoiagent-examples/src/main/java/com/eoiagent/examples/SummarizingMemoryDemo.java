package com.eoiagent.examples;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.memory.InMemoryMemoryStore;
import com.eoiagent.memory.SummarizingChatMemory;
import com.eoiagent.model.StubLlmGateway;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * Phase-2 — summarizing chat memory (T-207). {@link SummarizingChatMemory} keeps only the last
 * {@code maxMessages} raw turns; when a turn is evicted it is <em>folded</em> into a single running
 * summary {@link SystemMessage} via the LLM (audited {@code MODEL_CALL}) — so long-run context
 * survives in condensed form instead of being dropped.
 *
 * <p>Here {@code maxMessages=2}: after five turns, {@code messages()} returns a leading running-summary
 * system message plus the two most recent turns.
 */
public final class SummarizingMemoryDemo {

    private SummarizingMemoryDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Summarizing memory: condense evicted turns into a running summary");

        AgentContext ctx = DemoSupport.context(Role.ANALYST, DeploymentProfile.OFFLINE);
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("User greeted, asked Q3 order volume, now asking about pipeline health.")
                .build();
        SummarizingChatMemory memory = new SummarizingChatMemory(
                ctx, 2, gateway, new InMemoryMemoryStore(), new ConsoleAuditSink());

        DemoSupport.kv("window size", "2 raw turns (older turns are summarized, not dropped)");
        System.out.println();

        add(memory, UserMessage.from("hi"));
        add(memory, AiMessage.from("Hello! How can I help with the Acme lakehouse?"));
        add(memory, UserMessage.from("How many orders did we get in Q3?"));   // evicts "hi" -> summarized
        add(memory, AiMessage.from("Q3 had about 1.2M orders."));             // evicts the greeting -> summarized
        add(memory, UserMessage.from("Is the nightly pipeline healthy?"));    // evicts the Q3 question -> summarized

        System.out.println();
        DemoSupport.bullet("Context the model now sees (" + memory.messages().size() + " messages):");
        for (ChatMessage m : memory.messages()) {
            DemoSupport.kv("  " + m.type(), text(m));
        }
    }

    private static void add(SummarizingChatMemory memory, ChatMessage message) {
        DemoSupport.bullet("add " + message.type() + ": \"" + text(message) + "\"");
        memory.add(message);
    }

    private static String text(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> ((SystemMessage) m).text();
            case USER -> ((UserMessage) m).singleText();
            case AI -> ((AiMessage) m).text();
            default -> m.toString();
        };
    }
}
