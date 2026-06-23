package com.eoiagent.persistence;

/** {@link InMemoryCheckpointStore} against the shared {@link CheckpointStoreContractTest} (offline). */
class InMemoryCheckpointStoreTest extends CheckpointStoreContractTest {

    @Override
    protected CheckpointStore store() {
        return new InMemoryCheckpointStore();
    }
}
