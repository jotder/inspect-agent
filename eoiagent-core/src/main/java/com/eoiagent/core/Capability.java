package com.eoiagent.core;

/** Closed set of capabilities a tool may require and a role may be granted. */
public enum Capability {
    READ_METADATA,
    READ_SCHEMA,
    READ_DOCS,
    RUN_SQL_READONLY,
    GENERATE_SQL,
    AUTHOR_PIPELINE,
    RUN_PIPELINE,
    EDIT_CONFIG,
    WRITE_DATASTORE,
    TRIGGER_JOB,
    INVESTIGATE
}
