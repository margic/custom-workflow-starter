package com.anax.kogito.gradle;

/**
 * Parses custom URI schemes (dmn://, anax://, map://) from sw.json function operations.
 */
public final class UriParser {

    private UriParser() {}

    public static ParsedUri parse(String operation) {
        if (operation == null || operation.isEmpty()) {
            throw new IllegalArgumentException("Operation URI must not be null or empty");
        }

        int schemeEnd = operation.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Invalid custom URI (missing ://): " + operation);
        }

        String scheme = operation.substring(0, schemeEnd);
        String remainder = operation.substring(schemeEnd + 3); // skip "://"

        return switch (scheme) {
            case "dmn" -> parseDmn(remainder);
            case "anax" -> parseAnax(remainder);
            case "map" -> parseMap(remainder);
            default -> throw new IllegalArgumentException("Unknown URI scheme: " + scheme);
        };
    }

    private static ParsedUri parseDmn(String remainder) {
        int lastSlash = remainder.lastIndexOf('/');
        if (lastSlash <= 0) {
            throw new IllegalArgumentException(
                    "Invalid dmn:// URI — expected dmn://namespace/modelName, got: dmn://" + remainder);
        }
        String namespace = remainder.substring(0, lastSlash);
        String modelName = remainder.substring(lastSlash + 1);
        return new ParsedUri("dmn", namespace, modelName);
    }

    private static ParsedUri parseAnax(String remainder) {
        int slash = remainder.indexOf('/');
        if (slash < 0) {
            return new ParsedUri("anax", remainder, "execute");
        }
        String beanName = remainder.substring(0, slash);
        String methodName = remainder.substring(slash + 1);
        if (methodName.isEmpty()) {
            methodName = "execute";
        }
        return new ParsedUri("anax", beanName, methodName);
    }

    private static ParsedUri parseMap(String remainder) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid map:// URI — expected map://mappingName, got: map://");
        }
        return new ParsedUri("map", remainder, null);
    }

    /**
     * A parsed custom URI.
     * For dmn://: primary=namespace, secondary=modelName
     * For anax://: primary=beanName, secondary=methodName
     * For map://: primary=mappingName, secondary=null
     */
    public record ParsedUri(String scheme, String primary, String secondary) {}
}
