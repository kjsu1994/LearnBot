using System.Net;

internal sealed partial class LearnBotLocalAgent
{
    private async Task<int> Disconnect(string[] args)
    {
        var localOnly = args.Contains("--local-only", StringComparer.OrdinalIgnoreCase);
        var config = LoadConfigOrDefault();
        if (!localOnly)
        {
            if (config.AgentId == Guid.Empty || string.IsNullOrWhiteSpace(config.Token))
            {
                Console.Error.WriteLine("No usable Local Agent pairing was found. Use --local-only to remove stale local Agent data.");
                return 2;
            }
            try
            {
                using var client = Client(config);
                using var request = new HttpRequestMessage(HttpMethod.Delete, "/api/local-agents/self");
                using var response = await client.SendAsync(request);
                if (!response.IsSuccessStatusCode)
                {
                    Console.Error.WriteLine("The server did not revoke this Local Agent: HTTP " + (int)response.StatusCode);
                    Console.Error.WriteLine("Local data was preserved. Revoke the device in LearnBot, or use --local-only only when offline cleanup is intended.");
                    return 1;
                }
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
            {
                Console.Error.WriteLine("The server could not be reached, so Local Agent data was preserved: " + ex.Message);
                Console.Error.WriteLine("Retry later, or use --local-only only when offline cleanup is intended.");
                return 1;
            }
        }

        CleanupLocalAgentConnectionData();
        Console.WriteLine(localOnly
            ? "Local Agent data removed locally. Revoke the device in LearnBot when the server is available."
            : "Local Agent disconnected and local Agent data removed.");
        return 0;
    }

    private static void CleanupLocalAgentConnectionData()
    {
        TryDeleteFile(ConfigPath());
        TryDeleteFile(AgentCredentialPath());
        TryDeleteFile(PendingAgentCredentialPath());
        TryDeleteFile(PendingEnrollmentPath());
        TryDeleteFile(StatePath());
        TryDeleteFile(AgentUpdateStatePath());
        TryDeleteFile(LogPath());
        TryDeleteDirectory(LogArchiveDirectory());
        TryDeleteDirectory(Path.Combine(AgentDataDirectory(), "snapshots"));
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    private static bool IsAgentAuthenticationFailure(HttpRequestException exception) =>
        exception.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden;

    private static int SelfTestDisconnectLocalCleanupContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-disconnect-" + Guid.NewGuid().ToString("N"));
        var workspace = Path.Combine(root, "workspace");
        var agentRoot = Path.Combine(root, "agent");
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Directory.CreateDirectory(workspace);
            Directory.CreateDirectory(Path.Combine(agentRoot, "logs"));
            Directory.CreateDirectory(Path.Combine(agentRoot, "snapshots", "snap-test"));
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            File.WriteAllText(Path.Combine(workspace, "keep.txt"), "workspace-data");
            File.WriteAllText(ConfigPath(), "{}");
            File.WriteAllText(AgentCredentialPath(), "credential");
            File.WriteAllText(PendingAgentCredentialPath(), "pending");
            File.WriteAllText(PendingEnrollmentPath(), "pending-enrollment");
            File.WriteAllText(StatePath(), "state");
            File.WriteAllText(AgentUpdateStatePath(), "update");
            File.WriteAllText(LogPath(), "log");
            File.WriteAllText(Path.Combine(agentRoot, "logs", "agent.log"), "log");
            File.WriteAllText(Path.Combine(agentRoot, "snapshots", "snap-test", "manifest.json"), "{}");
            File.WriteAllText(WebSessionPath(), "web-session-must-remain");

            CleanupLocalAgentConnectionData();
            var passed = !File.Exists(ConfigPath())
                && !File.Exists(AgentCredentialPath())
                && !File.Exists(PendingAgentCredentialPath())
                && !File.Exists(PendingEnrollmentPath())
                && !File.Exists(StatePath())
                && !File.Exists(AgentUpdateStatePath())
                && !File.Exists(LogPath())
                && !Directory.Exists(Path.Combine(agentRoot, "logs"))
                && !Directory.Exists(Path.Combine(agentRoot, "snapshots"))
                && File.Exists(WebSessionPath())
                && File.Exists(Path.Combine(workspace, "keep.txt"));
            Console.WriteLine(passed ? "disconnect-local-cleanup-contract-ok" : "disconnect-local-cleanup-contract-failed");
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
