package com.eoiagent.model;

import com.eoiagent.core.ModelUnavailableException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Objects;

/**
 * Shared {@link LlmGateway} mapping over a LangChain4j {@code ChatModel} (and optional
 * {@code StreamingChatModel}): converts our {@link ChatRequest}/{@link ChatResult} to/from LC4j and
 * normalizes failures to {@link ModelUnavailableException}. Concrete backends (Ollama,
 * OpenAI-compatible) only supply the constructed models + {@link ModelInfo}; tests inject fakes.
 *
 * <p>Chat-only: {@link #embed} is unsupported (embedding has its own backend). Tool calls map both
 * ways (T-350): request {@code tools} go out as LC4j {@code ToolSpecification}s and the model's
 * {@code ToolExecutionRequest}s come back as {@link ChatResult#toolCalls()}.
 */
public class Lc4jChatGateway implements LlmGateway, AutoCloseable {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel; // nullable
    private final ModelInfo modelInfo;

    public Lc4jChatGateway(ChatModel chatModel, StreamingChatModel streamingModel, ModelInfo modelInfo) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.streamingModel = streamingModel;
        this.modelInfo = Objects.requireNonNull(modelInfo, "modelInfo");
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ChatResponse response = chatModel.chat(toLc4jRequest(request));
            return toChatResult(response);
        } catch (RuntimeException e) {
            throw new ModelUnavailableException(
                    "chat failed on " + modelInfo.modelId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(ChatRequest request, TokenSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        if (streamingModel == null) {
            throw new ModelUnavailableException("streaming not supported by " + modelInfo.modelId());
        }
        streamingModel.chat(toLc4jRequest(request), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                sink.onToken(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                sink.onComplete(toChatResult(response));
            }

            @Override
            public void onError(Throwable error) {
                sink.onError(error);
            }
        });
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        throw new UnsupportedOperationException(modelInfo.provider() + " is a chat-only backend");
    }

    @Override
    public ModelInfo activeChatModel() {
        return modelInfo;
    }

    @Override
    public boolean isAvailable(ModelRole role) {
        return role == ModelRole.CHAT;
    }

    @Override
    public void close() {
        // The shared JDK HttpClient is managed by the JVM; nothing backend-specific to close here.
    }

    private static dev.langchain4j.model.chat.request.ChatRequest toLc4jRequest(ChatRequest request) {
        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(MessageMapping.toLc4j(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            builder.toolSpecifications(ToolMapping.toLc4j(request.tools()));
        }
        return builder.build();
    }

    private ChatResult toChatResult(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        String text = ai == null || ai.text() == null ? "" : ai.text();
        return new ChatResult(text, ToolMapping.toToolCalls(ai), modelInfo, toUsage(response.tokenUsage()));
    }

    private static Usage toUsage(TokenUsage u) {
        if (u == null) {
            return new Usage(0, 0, 0);
        }
        return new Usage(nz(u.inputTokenCount()), nz(u.outputTokenCount()), nz(u.totalTokenCount()));
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
