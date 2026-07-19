using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private static string AgentUpdateStatePath() => Path.Combine(AgentDataDirectory(), "agent-update.json");

    private static void CaptureHeartbeatUpdateState(string responseBody, string? serverUrl)
    {
        if (string.IsNullOrWhiteSpace(responseBody)) return;
        try
        {
            using var document = JsonDocument.Parse(responseBody);
            var root = document.RootElement;
            var updateState = OptionalString(root, "updateState");
            if (string.IsNullOrWhiteSpace(updateState)) return;
            var state = new AgentUpdateState(
                updateState.ToUpperInvariant(),
                OptionalString(root, "latestVersion"),
                OptionalString(root, "minimumVersion"),
                NormalizeTrustedUpdateUri(OptionalString(root, "updateUri"), serverUrl),
                DateTimeOffset.UtcNow);
            var path = AgentUpdateStatePath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + ".tmp";
            File.WriteAllText(tempPath, JsonSerializer.Serialize(state, JsonOptions), new UTF8Encoding(false));
            File.Move(tempPath, path, overwrite: true);
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            Log("heartbeat update metadata could not be stored: " + ex.Message);
        }
    }

    private static AgentUpdateState? LoadAgentUpdateState()
    {
        try
        {
            var path = AgentUpdateStatePath();
            return File.Exists(path)
                ? JsonSerializer.Deserialize<AgentUpdateState>(File.ReadAllText(path), JsonOptions)
                : null;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static bool IsAgentUpdateRequired(out AgentUpdateState? state)
    {
        state = LoadAgentUpdateState();
        return string.Equals(state?.UpdateState, "UPDATE_REQUIRED", StringComparison.OrdinalIgnoreCase);
    }

    private static string? OptionalString(JsonElement root, string property) =>
        root.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static string? NormalizeTrustedUpdateUri(string? value, string? serverUrl)
    {
        return ServerOriginPolicy.TryResolveSameOriginUri(
            value,
            serverUrl ?? "",
            ConfiguredPublicBaseUrl(),
            ConfiguredAllowInsecurePrivateNetwork(),
            out var absolute)
                ? absolute.ToString()
                : null;
    }

    private static int SelfTestAgentUpdateGateContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-update-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent.json"));
            const string server = "https://learnbot.example.test";
            CaptureHeartbeatUpdateState("""
                {"latestVersion":"2.0.0","minimumVersion":"1.5.0","updateState":"UPDATE_REQUIRED","updateUri":"https://learnbot.example.test/downloads/agent.appinstaller"}
                """, server);
            var blocked = IsAgentUpdateRequired(out var state)
                && state?.LatestVersion == "2.0.0"
                && state.MinimumVersion == "1.5.0"
                && state.UpdateUri?.StartsWith("https://", StringComparison.Ordinal) == true
                && UpdateRequiredExitCode == 20;
            CaptureHeartbeatUpdateState("""
                {"latestVersion":"2.0.0","minimumVersion":"1.5.0","updateState":"UPDATE_REQUIRED","updateUri":"/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller"}
                """, server);
            var relative = IsAgentUpdateRequired(out var relativeState)
                && relativeState?.UpdateUri == "https://learnbot.example.test/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller";
            CaptureHeartbeatUpdateState("""
                {"latestVersion":"2.0.0","minimumVersion":"1.5.0","updateState":"UPDATE_REQUIRED","updateUri":"https://attacker.example/agent.appinstaller"}
                """, server);
            var crossOriginRejected = IsAgentUpdateRequired(out var crossOriginState)
                && crossOriginState?.UpdateUri is null;
            CaptureHeartbeatUpdateState("""
                {"latestVersion":"2.0.0","minimumVersion":"1.5.0","updateState":"UPDATE_REQUIRED","updateUri":"http://learnbot.example.test/agent.appinstaller"}
                """, server);
            var httpRejected = IsAgentUpdateRequired(out var httpState)
                && httpState?.UpdateUri is null;
            var enterpriseHttpAccepted = ServerOriginPolicy.TryResolveSameOriginUri(
                    "/downloads/local-agent/pilot/LearnBotLocalAgent.appinstaller",
                    "http://192.168.1.72:8083",
                    "http://192.168.1.72:8083",
                    allowInsecurePrivateNetwork: true,
                    out var privateUpdate)
                && privateUpdate.ToString() == "http://192.168.1.72:8083/downloads/local-agent/pilot/LearnBotLocalAgent.appinstaller";
            var differentPrivateOriginRejected = !ServerOriginPolicy.TryResolveSameOriginUri(
                "http://192.168.1.73:8083/downloads/local-agent/pilot/LearnBotLocalAgent.appinstaller",
                "http://192.168.1.72:8083",
                "http://192.168.1.72:8083",
                allowInsecurePrivateNetwork: true,
                out _);
            CaptureHeartbeatUpdateState("""
                {"latestVersion":"2.0.0","minimumVersion":"1.5.0","updateState":"CURRENT","updateUri":"https://learnbot.example.test/downloads/agent.appinstaller"}
                """, server);
            var current = !IsAgentUpdateRequired(out _);
            var passed = blocked
                && relative
                && crossOriginRejected
                && httpRejected
                && enterpriseHttpAccepted
                && differentPrivateOriginRejected
                && current;
            Console.WriteLine(passed ? "agent-update-gate-contract-ok" : "agent-update-gate-contract-failed");
            return passed ? 0 : 1;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            if (Directory.Exists(root))
            {
                try { Directory.Delete(root, recursive: true); } catch { }
            }
        }
    }
}

internal sealed record AgentUpdateState(
    string UpdateState,
    string? LatestVersion,
    string? MinimumVersion,
    string? UpdateUri,
    DateTimeOffset CheckedAt);
