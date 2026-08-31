package dev.smithyai.orchestrator.config;

/**
 * Access to the designs a ticket links to.
 *
 * <p>Off unless a deployment says otherwise, because it needs a Figma token
 * and sends ticket content nowhere new only once one exists. A token is a
 * read-only personal access token with the {@code file_content} scope.
 *
 * @param maxDesigns how many frames one ticket may pull in. A file linked
 *                   without a frame can hold hundreds, and a prompt listing
 *                   them all would say less than one listing a handful.
 */
public record FigmaConfig(Boolean enabled, SecretRef token, String format, Double scale, Integer maxDesigns) {
    private static final String DEFAULT_FORMAT = "png";
    private static final double DEFAULT_SCALE = 2;
    private static final int DEFAULT_MAX_DESIGNS = 10;

    public static FigmaConfig disabled() {
        return new FigmaConfig(false, null, null, null, null);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Figma renders png, jpg, svg and pdf; anything else is a typo. */
    public String resolvedFormat() {
        if (format == null || format.isBlank()) return DEFAULT_FORMAT;
        String requested = format.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (requested) {
            case "png", "jpg", "svg", "pdf" -> requested;
            default -> throw new IllegalStateException(
                "figma.format must be one of png, jpg, svg, pdf but was '" + format + "'"
            );
        };
    }

    /** Two by default: legible text at the size a design is usually read. */
    public double resolvedScale() {
        if (scale == null) return DEFAULT_SCALE;
        if (scale < 0.01 || scale > 4) {
            throw new IllegalStateException("figma.scale must be between 0.01 and 4 but was " + scale);
        }
        return scale;
    }

    public int resolvedMaxDesigns() {
        return maxDesigns == null || maxDesigns <= 0 ? DEFAULT_MAX_DESIGNS : maxDesigns;
    }
}
