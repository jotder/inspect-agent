package com.eoiagent.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ONNX embedder runs in-JVM and offline (T-101 AC1: 384-dim, no network; AC2: stable vectors).
 * The model loads once for the class.
 */
class OnnxEmbeddingAdapterTest {

    private static final OnnxEmbeddingAdapter MODEL = new OnnxEmbeddingAdapter();

    @Test
    void produces384DimensionVectors() { // AC1
        float[] vector = MODEL.embed("the ingestion pipeline failed last quarter").content().vector();
        assertThat(vector).hasSize(384);
        assertThat(MODEL.dimension()).isEqualTo(384);
    }

    @Test
    void identicalInputYieldsIdenticalVector() { // AC2
        float[] a = MODEL.embed("schema for the orders dataset").content().vector();
        float[] b = MODEL.embed("schema for the orders dataset").content().vector();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentInputsYieldDifferentVectors() {
        float[] a = MODEL.embed("orders dataset schema").content().vector();
        float[] b = MODEL.embed("a completely unrelated weather forecast").content().vector();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void modelIsLoadedOncePerJvmAndSharedAcrossInstances() { // T-402: cheap re-construction
        assertThat(OnnxEmbeddingAdapter.sharedDelegate())
                .isSameAs(OnnxEmbeddingAdapter.sharedDelegate());

        long start = System.nanoTime();
        OnnxEmbeddingAdapter second = new OnnxEmbeddingAdapter();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(second.embed("still works").content().vector()).hasSize(384);
        // The first load takes seconds; a shared re-construction must be near-instant.
        assertThat(elapsedMs)
                .as("re-constructing the adapter must not reload the ONNX model")
                .isLessThan(500);
    }
}
