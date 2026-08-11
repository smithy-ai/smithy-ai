package dev.smithyai.orchestrator.runtime.store;

/**
 * What kind of external thing a correlation points at. A correlation is the
 * answer to "an event arrived about X — which run owns it?", so these are the
 * handles events carry.
 */
public enum CorrelationKind {
    ISSUE("issue"),
    PR("pr"),
    BRANCH("branch"),
    CONTAINER("container"),
    /**
     * A routing key a definition composed itself.
     *
     * <p>A definition decides what identifies its runs — an issue in a
     * repository, a pull request, a release train — so the key is opaque to the
     * platform and only has to be stable for the same work.
     */
    KEY("key"),
    /** Anything provider-specific that does not fit the above. */
    EXTERNAL("external");

    private final String value;

    CorrelationKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CorrelationKind fromValue(String value) {
        for (CorrelationKind k : values()) {
            if (k.value.equals(value)) return k;
        }
        throw new IllegalArgumentException("Unknown correlation kind: " + value);
    }
}
