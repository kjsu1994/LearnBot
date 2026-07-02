using System.Diagnostics;

internal sealed partial class LearnBotLocalAgent
{
    private CliStatusReport BuildCliStatusReport()
    {
        var config = LoadConfigOrDefault();
        var state = LoadRunState();
        var running = state is not null && state.Status == "running" && IsProcessRunning(state.ProcessId);
        return new CliStatusReport(
            CommandName: "learnbot",
            Version: Version,
            Configured: !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty,
            ServerUrl: config.ServerUrl,
            AgentId: config.AgentId,
            Transport: NormalizeTransport(config.Transport),
            WorkspaceCount: config.Workspaces.Count,
            ApprovedWorkspaceCount: config.Workspaces.Count(workspace => workspace.Approved),
            ConfigPath: ConfigPath(),
            ConfigExists: File.Exists(ConfigPath()),
            LogPath: LogPath(),
            LogExists: File.Exists(LogPath()),
            StatePath: StatePath(),
            Running: running,
            ExecutablePath: Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName,
            State: state);
    }

    private CliDoctorReport BuildCliDoctorReport()
    {
        var status = BuildCliStatusReport();
        var checks = new List<CliDoctorCheck>
        {
            new("configPath", true, status.ConfigPath),
            new("paired", status.Configured, status.Configured ? "agent is paired" : "run learnbot pair first"),
            new("tokenSecretHidden", true, "status and doctor never print the pairing token"),
            new("workspaceConfigured", status.WorkspaceCount > 0, status.WorkspaceCount > 0 ? $"{status.WorkspaceCount} workspace(s)" : "run learnbot workspace add <path>"),
            new("transportConfigured", !string.IsNullOrWhiteSpace(status.Transport), status.Transport),
            new("localStateWritable", CanCreateAgentDataDirectory(), AgentDataDirectory()),
            new("safeToolBoundary", true, "typed tools only; arbitrary shell execution is not accepted"),
            new("sideEffectApprovalBoundary", true, "patch, command, and rollback tools require approved Local Agent requests")
        };
        return new CliDoctorReport(
            CommandName: "learnbot",
            Version: Version,
            Ready: checks.Where(check => check.Name is "paired" or "workspaceConfigured" or "localStateWritable").All(check => check.Ok),
            Summary: status.Configured
                ? "Local Agent CLI is paired. Use learnbot agent start to process approved work."
                : "Local Agent CLI is installed but not paired.",
            Status: status,
            Checks: checks);
    }

    private static bool CanCreateAgentDataDirectory()
    {
        try
        {
            Directory.CreateDirectory(AgentDataDirectory());
            return true;
        }
        catch
        {
            return false;
        }
    }
}

internal sealed record CliStatusReport(
    string CommandName,
    string Version,
    bool Configured,
    string? ServerUrl,
    Guid AgentId,
    string Transport,
    int WorkspaceCount,
    int ApprovedWorkspaceCount,
    string ConfigPath,
    bool ConfigExists,
    string LogPath,
    bool LogExists,
    string StatePath,
    bool Running,
    string? ExecutablePath,
    AgentRunState? State);

internal sealed record CliDoctorReport(
    string CommandName,
    string Version,
    bool Ready,
    string Summary,
    CliStatusReport Status,
    IReadOnlyList<CliDoctorCheck> Checks);

internal sealed record CliDoctorCheck(string Name, bool Ok, string? Message);
