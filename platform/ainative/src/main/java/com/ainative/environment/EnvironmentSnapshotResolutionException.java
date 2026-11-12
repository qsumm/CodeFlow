package com.ainative.environment;

/** Safe, terminal failure to construct a complete agent environment snapshot. */
public class EnvironmentSnapshotResolutionException extends IllegalStateException {
    public EnvironmentSnapshotResolutionException(String message) {
        super(message);
    }
}
