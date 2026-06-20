package com.eoiagent.knowledge;

import java.util.List;

/** Port for storing and similarity-searching embedded chunks. */
public interface VectorStore {

    void add(List<EmbeddedChunk> chunks);

    List<Match> search(float[] queryVector, int k, MetadataFilter filter);
}
