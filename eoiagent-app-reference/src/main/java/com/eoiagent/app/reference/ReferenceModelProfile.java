package com.eoiagent.app.reference;

import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.ModelSelection;
import com.eoiagent.app.RoutingPolicy;

import java.util.List;

/**
 * OFFLINE model profile: local chat via Ollama ({@code qwen2.5:14b-instruct}) and in-JVM ONNX
 * {@code all-MiniLM-L6-v2} embeddings, with <strong>no hosted fallback</strong> (required for OFFLINE
 * — keeps {@code PackValidator} green and the deployment fail-closed).
 */
final class ReferenceModelProfile implements ModelProfile {

    @Override
    public ModelSelection chat() {
        return new ModelSelection("ollama", "qwen2.5:14b-instruct", "http://localhost:11434/v1", true);
    }

    @Override
    public ModelSelection embedding() {
        return new ModelSelection("onnx-all-minilm", "all-MiniLM-L6-v2", null, true);
    }

    @Override
    public RoutingPolicy routing() {
        return new RoutingPolicy(List.of("ollama"), false);
    }
}
