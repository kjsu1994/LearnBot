package com.learnbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LocalAgentVersionPolicy {
    private final String latestVersion;
    private final String minimumVersion;
    private final String updateUri;

    public LocalAgentVersionPolicy(
            @Value("${learnbot.local-agent.latest-version:0.1.0}") String latestVersion,
            @Value("${learnbot.local-agent.minimum-version:0.1.0}") String minimumVersion,
            @Value("${learnbot.local-agent.update-uri:/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller}") String updateUri
    ) {
        this.latestVersion = latestVersion;
        this.minimumVersion = minimumVersion;
        this.updateUri = updateUri;
    }

    public Decision evaluate(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return new Decision(latestVersion, minimumVersion, "UNKNOWN", updateUri);
        }
        if (compare(currentVersion, minimumVersion) < 0) {
            return new Decision(latestVersion, minimumVersion, "UPDATE_REQUIRED", updateUri);
        }
        if (compare(currentVersion, latestVersion) < 0) {
            return new Decision(latestVersion, minimumVersion, "UPDATE_AVAILABLE", updateUri);
        }
        return new Decision(latestVersion, minimumVersion, "CURRENT", updateUri);
    }

    private int compare(String left, String right) {
        List<Integer> a = numbers(left);
        List<Integer> b = numbers(right);
        int length = Math.max(a.size(), b.size());
        for (int index = 0; index < length; index++) {
            int av = index < a.size() ? a.get(index) : 0;
            int bv = index < b.size() ? b.get(index) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private List<Integer> numbers(String version) {
        String core = version.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        List<Integer> result = new ArrayList<>();
        for (String part : core.split("\\.")) {
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                result.add(0);
            }
        }
        return result;
    }

    public record Decision(String latestVersion, String minimumVersion, String updateState, String updateUri) {
    }
}
