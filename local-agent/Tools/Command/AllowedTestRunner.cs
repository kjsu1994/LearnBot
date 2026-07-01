using System.Diagnostics;
using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private const int DefaultCommandTimeoutSeconds = 120;
    private const int AbsoluteMaxCommandTimeoutSeconds = 300;
    private const int DefaultCommandOutputBytes = 64_000;
    private const int AbsoluteMaxCommandOutputBytes = 128_000;

    private static readonly IReadOnlyDictionary<string, AllowedCommandSpec> AllowedCommands =
        new Dictionary<string, AllowedCommandSpec>(StringComparer.Ordinal)
        {
            ["dotnet.version"] = new("dotnet.version", "dotnet", ["--version"]),
            ["dotnet.build"] = new("dotnet.build", "dotnet", ["build", "--no-restore"]),
            ["dotnet.test"] = new("dotnet.test", "dotnet", ["test", "--no-restore"]),
            ["npm.run.build"] = new("npm.run.build", "npm", ["run", "build"]),
            ["npm.test"] = new("npm.test", "npm", ["test"]),
            ["maven.test"] = new(
                "maven.test",
                "mvn",
                ["test"],
                null,
                [".tools/apache-maven-3.9.9/bin/mvn.cmd"]),
            ["maven.backend.test"] = new(
                "maven.backend.test",
                "mvn",
                ["test"],
                "backend",
                [".tools/apache-maven-3.9.9/bin/mvn.cmd"])
        };

    private ToolResult RunAllowedCommand(AgentConfig config, Guid? workspaceId, JsonElement request)
    {
        if (!request.TryGetProperty("input", out var input))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "command.runAllowed input is required.");
        }

        var approvalState = request.TryGetProperty("approvalState", out var approval)
            ? approval.GetString()
            : null;
        if (!string.Equals(approvalState, "APPROVED", StringComparison.Ordinal))
        {
            return ToolResult.Fail("REJECTED", "APPROVAL_REQUIRED", "command.runAllowed requires an approved Local Agent tool request.");
        }

        var commandId = TryInputStringFromObject(input, "commandId");
        if (string.IsNullOrWhiteSpace(commandId) || !AllowedCommands.TryGetValue(commandId, out var spec))
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "command.runAllowed accepts only typed allowlisted command ids.");
        }

        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var workingDirectory = workspace.Root!;
        if (!string.IsNullOrWhiteSpace(spec.RelativeWorkingDirectory))
        {
            var nested = Path.GetFullPath(Path.Combine(workingDirectory, spec.RelativeWorkingDirectory));
            if (!IsWithin(workingDirectory, nested) || !Directory.Exists(nested))
            {
                return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Allowlisted command working directory is unavailable or escapes the approved workspace.");
            }
            workingDirectory = nested;
        }

        var resolved = ResolveAllowedExecutable(workspace.Root!, spec);
        if (!resolved.Success)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", resolved.Error!, CommandOutput(spec.Id, workingDirectory, null, null, false, 0, "", "", false));
        }

        var timeoutSeconds = Math.Clamp(TryInputIntFromObject(input, "timeoutSeconds") ?? DefaultCommandTimeoutSeconds, 1, AbsoluteMaxCommandTimeoutSeconds);
        var maxOutputBytes = Math.Clamp(TryInputIntFromObject(input, "maxOutputBytes") ?? DefaultCommandOutputBytes, 1, AbsoluteMaxCommandOutputBytes);
        var started = Stopwatch.StartNew();
        var run = ExecuteAllowedCommand(resolved.Executable!, spec.Args, workingDirectory, TimeSpan.FromSeconds(timeoutSeconds), maxOutputBytes);
        var output = CommandOutput(spec.Id, workingDirectory, resolved.DisplayCommand, run.ExitCode, run.TimedOut, started.ElapsedMilliseconds, run.Stdout, run.Stderr, run.Truncated);

        if (run.TimedOut)
        {
            return ToolResult.Fail("TIMED_OUT", "TIMEOUT", "Allowlisted command timed out.", output);
        }
        if (run.ExitCode != 0)
        {
            return ToolResult.Fail("FAILED", "TEST_FAILED", "Allowlisted command exited with a non-zero status.", output);
        }

        return ToolResult.Ok(output);
    }

    private static AllowedExecutableResolution ResolveAllowedExecutable(string workspaceRoot, AllowedCommandSpec spec)
    {
        foreach (var candidate in spec.WorkspaceExecutableCandidates)
        {
            var path = Path.GetFullPath(Path.Combine(workspaceRoot, candidate.Replace('/', Path.DirectorySeparatorChar)));
            if (IsWithin(workspaceRoot, path) && File.Exists(path))
            {
                return AllowedExecutableResolution.Ok(path, path);
            }
        }

        var pathValue = Environment.GetEnvironmentVariable("PATH") ?? "";
        var extensions = OperatingSystem.IsWindows()
            ? (Environment.GetEnvironmentVariable("PATHEXT") ?? ".EXE;.CMD;.BAT").Split(';', StringSplitOptions.RemoveEmptyEntries)
            : [""];
        foreach (var directory in pathValue.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            foreach (var extension in extensions)
            {
                var executable = Path.Combine(directory, spec.Executable + extension);
                if (File.Exists(executable))
                {
                    return AllowedExecutableResolution.Ok(executable, spec.Executable);
                }
            }
        }

        return AllowedExecutableResolution.Fail("Allowlisted executable was not found: " + spec.Executable);
    }

    private static AllowedCommandRun ExecuteAllowedCommand(
        string executable,
        IReadOnlyList<string> args,
        string workingDirectory,
        TimeSpan timeout,
        int maxOutputBytes)
    {
        using var process = new Process();
        var isBatch = OperatingSystem.IsWindows()
            && (executable.EndsWith(".cmd", StringComparison.OrdinalIgnoreCase)
                || executable.EndsWith(".bat", StringComparison.OrdinalIgnoreCase));
        if (isBatch)
        {
            process.StartInfo.FileName = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
            process.StartInfo.ArgumentList.Add("/d");
            process.StartInfo.ArgumentList.Add("/s");
            process.StartInfo.ArgumentList.Add("/c");
            process.StartInfo.ArgumentList.Add("\"" + executable + "\" " + string.Join(' ', args.Select(QuoteWindowsArgument)));
        }
        else
        {
            process.StartInfo.FileName = executable;
            foreach (var arg in args)
            {
                process.StartInfo.ArgumentList.Add(arg);
            }
        }
        process.StartInfo.WorkingDirectory = workingDirectory;
        process.StartInfo.UseShellExecute = false;
        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        process.StartInfo.StandardOutputEncoding = Encoding.UTF8;
        process.StartInfo.StandardErrorEncoding = Encoding.UTF8;

        var stdout = new LimitedOutputBuffer(maxOutputBytes);
        var stderr = new LimitedOutputBuffer(maxOutputBytes);
        try
        {
            process.Start();
            var stdoutTask = Task.Run(() => stdout.Read(process.StandardOutput));
            var stderrTask = Task.Run(() => stderr.Read(process.StandardError));
            var exited = process.WaitForExit((int)timeout.TotalMilliseconds);
            if (!exited)
            {
                try
                {
                    process.Kill(entireProcessTree: true);
                }
                catch (InvalidOperationException)
                {
                }
                catch (System.ComponentModel.Win32Exception)
                {
                }
                Task.WaitAll([stdoutTask, stderrTask], TimeSpan.FromSeconds(2));
                return new AllowedCommandRun(null, true, stdout.Text, stderr.Text, true);
            }
            Task.WaitAll(stdoutTask, stderrTask);
            return new AllowedCommandRun(process.ExitCode, false, stdout.Text, stderr.Text, stdout.Truncated || stderr.Truncated);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return new AllowedCommandRun(1, false, stdout.Text, "Allowlisted command failed to start: " + ex.Message, true);
        }
    }

    private static Dictionary<string, object?> CommandOutput(
        string commandId,
        string workingDirectory,
        string? displayCommand,
        int? exitCode,
        bool timedOut,
        long durationMs,
        string stdout,
        string stderr,
        bool truncated) => new()
    {
        ["commandId"] = commandId,
        ["workingDirectory"] = workingDirectory,
        ["displayCommand"] = displayCommand,
        ["exitCode"] = exitCode,
        ["timedOut"] = timedOut,
        ["durationMs"] = durationMs,
        ["stdout"] = stdout,
        ["stderr"] = stderr,
        ["truncated"] = truncated,
        ["arbitraryShellAllowed"] = false
    };

    private static int? TryInputIntFromObject(JsonElement input, string property)
    {
        if (!input.TryGetProperty(property, out var value))
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var parsed) ? parsed : null;
    }

    private static string QuoteWindowsArgument(string value) =>
        "\"" + value.Replace("\"", "\\\"", StringComparison.Ordinal) + "\"";

    private static int SelfTestAllowedTestRunnerContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-allowed-command-" + Guid.NewGuid().ToString("N"));
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
            var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
            var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
            var requestId = Guid.Parse("66666666-6666-6666-6666-666666666666");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(workspaceRoot);
            var config = new AgentConfig
            {
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };

            using var approvedJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "command.runAllowed",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?>
                {
                    ["commandId"] = "dotnet.version",
                    ["timeoutSeconds"] = 30,
                    ["maxOutputBytes"] = 8
                }
            }, JsonOptions));
            var approved = new LearnBotLocalAgent().HandleTool(config, requestId, approvedJson.RootElement, "command.runAllowed");

            using var unknownJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "command.runAllowed",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?> { ["commandId"] = "shell.anything" }
            }, JsonOptions));
            var unknown = new LearnBotLocalAgent().HandleTool(config, requestId, unknownJson.RootElement, "command.runAllowed");

            using var unapprovedJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "command.runAllowed",
                ["approvalState"] = "REQUIRED",
                ["input"] = new Dictionary<string, object?> { ["commandId"] = "dotnet.version" }
            }, JsonOptions));
            var unapproved = new LearnBotLocalAgent().HandleTool(config, requestId, unapprovedJson.RootElement, "command.runAllowed");

            var ok = approved.Status == "SUCCEEDED"
                && approved.Output.TryGetValue("commandId", out var commandId)
                && string.Equals(commandId?.ToString(), "dotnet.version", StringComparison.Ordinal)
                && approved.Output.TryGetValue("arbitraryShellAllowed", out var arbitraryShellAllowed)
                && arbitraryShellAllowed is false
                && unknown.Status == "REJECTED"
                && unknown.FailureCode == "UNSAFE_TOOL"
                && unapproved.Status == "REJECTED"
                && unapproved.FailureCode == "APPROVAL_REQUIRED";
            if (!ok)
            {
                Console.Error.WriteLine(approved.Error ?? "allowed test runner contract self-test failed");
                return 1;
            }
            Console.WriteLine("allowed-test-runner-contract-ok");
            return 0;
        }
        finally
        {
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch (IOException)
                {
                }
                catch (UnauthorizedAccessException)
                {
                }
            }
        }
    }
}

internal sealed record AllowedCommandSpec(
    string Id,
    string Executable,
    string[] Args,
    string? RelativeWorkingDirectory = null,
    string[]? WorkspaceExecutableCandidates = null)
{
    public string[] WorkspaceExecutableCandidates { get; } = WorkspaceExecutableCandidates ?? [];
}

internal sealed record AllowedExecutableResolution(bool Success, string? Executable, string? DisplayCommand, string? Error)
{
    public static AllowedExecutableResolution Ok(string executable, string displayCommand) => new(true, executable, displayCommand, null);

    public static AllowedExecutableResolution Fail(string error) => new(false, null, null, error);
}

internal sealed record AllowedCommandRun(int? ExitCode, bool TimedOut, string Stdout, string Stderr, bool Truncated);

internal sealed class LimitedOutputBuffer
{
    private readonly int maxBytes;
    private readonly StringBuilder builder = new();
    private int bytes;

    public LimitedOutputBuffer(int maxBytes)
    {
        this.maxBytes = maxBytes;
    }

    public string Text => builder.ToString();

    public bool Truncated { get; private set; }

    public void Read(TextReader reader)
    {
        var buffer = new char[1024];
        int read;
        while ((read = reader.Read(buffer, 0, buffer.Length)) > 0)
        {
            Append(buffer.AsSpan(0, read).ToString());
        }
    }

    private void Append(string value)
    {
        if (Truncated)
        {
            return;
        }
        foreach (var character in value)
        {
            var characterBytes = Encoding.UTF8.GetByteCount([character]);
            if (bytes + characterBytes > maxBytes)
            {
                Truncated = true;
                return;
            }
            builder.Append(character);
            bytes += characterBytes;
        }
    }
}
