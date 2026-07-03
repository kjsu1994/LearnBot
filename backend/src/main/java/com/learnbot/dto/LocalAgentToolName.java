package com.learnbot.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum LocalAgentToolName {
    AGENT_STATUS("agent.status", false),
    AGENT_DOCTOR("agent.doctor", false),
    WORKSPACE_LIST("workspace.list", false),
    WORKSPACE_TREE("workspace.tree", false),
    WORKSPACE_SEARCH("workspace.search", false),
    WORKSPACE_ADD("workspace.add", true),
    FILE_READ("file.read", false),
    PATCH_APPLY("patch.apply", true),
    GIT_STATUS("git.status", false),
    GIT_DIFF("git.diff", false),
    COMMAND_RUN_ALLOWED("command.runAllowed", true),
    ROLLBACK_RESTORE("rollback.restore", true);

    private final String wireName;
    private final boolean sideEffectful;

    LocalAgentToolName(String wireName, boolean sideEffectful) {
        this.wireName = wireName;
        this.sideEffectful = sideEffectful;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public boolean isSideEffectful() {
        return sideEffectful;
    }

    @JsonCreator
    public static LocalAgentToolName fromWireName(String value) {
        return Arrays.stream(values())
                .filter(tool -> tool.wireName.equals(value) || tool.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Local Agent tool: " + value));
    }
}
