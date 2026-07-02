package com.learnbot.dto.loop;

import java.util.LinkedHashMap;
import java.util.Map;

public record CodeAgentLoopRecommendedAction(
        String schema,
        String actionKey,
        String label,
        boolean enabled,
        String method,
        String endpoint,
        boolean requestCreationEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        String reason
) {
    public Map<String, Object> toMap() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("schema", schema);
        action.put("actionKey", actionKey);
        action.put("label", label);
        action.put("enabled", enabled);
        action.put("method", method);
        action.put("endpoint", endpoint);
        action.put("requestCreationEnabled", requestCreationEnabled);
        action.put("pushEnabled", pushEnabled);
        action.put("claimEnabled", claimEnabled);
        action.put("mutationEnabled", mutationEnabled);
        action.put("reason", reason);
        return Map.copyOf(action);
    }
}
