package com.eoiagent.knowledge;

import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import java.util.List;

/** Port for retrieving relevant chunks for a query. */
public interface Retriever {

    List<RetrievedChunk> retrieve(RetrievalQuery query);
}
