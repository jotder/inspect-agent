package com.eoiagent.scratchpad;

import java.util.List;

/** Port for transient keyed working storage during a run. */
public interface Scratchpad {

    String write(String key, String content);

    String read(String key);

    List<String> list(String prefix);

    void delete(String key);
}
