package com.ainative.workspace;

public interface WorkspaceCleanupTransport {
    void send(WorkspaceCleanupCandidate candidate);
}
