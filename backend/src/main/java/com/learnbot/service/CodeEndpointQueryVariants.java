package com.learnbot.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodeEndpointQueryVariants {
    private static final Pattern ROUTE = Pattern.compile("(?:/[A-Za-z0-9._{}:-]+){2,}");

    private CodeEndpointQueryVariants() {
    }

    static List<String> expand(String query) {
        String safe = query == null ? "" : query.trim();
        if (safe.isBlank()) return List.of();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(safe);
        Matcher matcher = ROUTE.matcher(safe);
        while (matcher.find() && variants.size() < 5) {
            String route = matcher.group();
            List<String> segments = java.util.Arrays.stream(route.split("/"))
                    .filter(value -> !value.isBlank()).toList();
            if (segments.size() < 2) continue;
            variants.add(String.join(" ", segments));
            variants.add("/" + segments.get(segments.size() - 1));
            if (segments.size() > 2) {
                variants.add("/" + String.join("/", segments.subList(0, segments.size() - 1)));
            }
        }
        return new ArrayList<>(variants).stream().limit(5).toList();
    }

    static List<String> routes(String query) {
        String safe = query == null ? "" : query;
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        Matcher matcher = ROUTE.matcher(safe);
        while (matcher.find() && routes.size() < 4) routes.add(matcher.group());
        return List.copyOf(routes);
    }
}
