using System.Diagnostics;
using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private static int SelfTestApprovedExecutionFlowContract(string? reportPath = null)
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-approved-flow-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var sourceRequestId = Guid.Parse("22222222-2222-2222-2222-222222222222");
            var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
            var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
            var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
            var releaseAttemptId = Guid.Parse("77777777-7777-7777-7777-777777777777");
            var workspaceRoot = Path.Combine(root, "workspace");
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src"));
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var targetPath = Path.Combine(workspaceRoot, "src", "App.cs");
            var original = "class App {\n    string Name = \"old\";\n}\n";
            var patched = "class App {\n    string Name = \"new\";\n    string Mode = \"safe\";\n}\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            var gitInit = RunFlowSetupProcess("git", ["init"], workspaceRoot, TimeSpan.FromSeconds(10));
            if (!gitInit.Success)
            {
                Console.Error.WriteLine(gitInit.Error ?? "approved execution flow setup could not initialize git");
                return 1;
            }

            var originalHash = Sha256Hex(File.ReadAllBytes(targetPath));
            using var snapshotInputJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sourceRequestId"] = sourceRequestId,
                ["releaseAttemptId"] = releaseAttemptId
            }, JsonOptions));
            var snapshot = CreateSnapshot(workspaceId, workspaceRoot, snapshotInputJson.RootElement, [
                new Dictionary<string, object?>
                {
                    ["path"] = "src/App.cs",
                    ["absolutePath"] = targetPath,
                    ["actualSha256"] = originalHash,
                    ["hashMatches"] = true,
                    ["contextMatches"] = true
                }
            ]);
            var manifestId = snapshot.Manifest is not null && snapshot.Manifest.TryGetValue("id", out var id)
                ? id?.ToString() ?? ""
                : "";
            if (string.IsNullOrWhiteSpace(manifestId))
            {
                Console.Error.WriteLine(snapshot.Error ?? "approved execution flow could not create snapshot");
                return 1;
            }

            var config = new AgentConfig
            {
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var heartbeatJson = JsonSerializer.Serialize(HeartbeatPayload(config), JsonOptions);
            var agent = new LearnBotLocalAgent();
            var diff = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,4 @@
             class App {
            -    string Name = "old";
            +    string Name = "new";
            +    string Mode = "safe";
             }
            """;

            using var patchRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "patch.apply",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["mutationAllowed"] = true,
                    ["dryRunOnly"] = false,
                    ["manifestId"] = manifestId,
                    ["diff"] = diff,
                    ["targetFiles"] = new[] { "src/App.cs" }
            });
            var patchResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888881"), patchRequest.RootElement, "patch.apply");
            var afterPatch = File.ReadAllText(targetPath, Encoding.UTF8);
            var afterPatchHash = Sha256Hex(File.ReadAllBytes(targetPath));

            using var commandRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "command.runAllowed",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["commandId"] = "dotnet.version",
                    ["timeoutSeconds"] = 30,
                    ["maxOutputBytes"] = 64
                });
            var commandResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888882"), commandRequest.RootElement, "command.runAllowed");

            using var statusRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "git.status",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId
                });
            var statusResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888883"), statusRequest.RootElement, "git.status");

            using var rollbackRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "rollback.restore",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["manifestId"] = manifestId
                });
            var rollbackResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888884"), rollbackRequest.RootElement, "rollback.restore");

            var afterRollback = File.ReadAllText(targetPath, Encoding.UTF8);
            var afterRollbackHash = Sha256Hex(File.ReadAllBytes(targetPath));
            var identitiesPreserved = patchResponse.SessionId == sessionId
                && patchResponse.UserId == userId
                && patchResponse.AgentId == agentId
                && patchResponse.WorkspaceId == workspaceId
                && commandResponse.SessionId == sessionId
                && statusResponse.SessionId == sessionId
                && rollbackResponse.SessionId == sessionId;
            var ok = patchResponse.Status == "SUCCEEDED"
                && heartbeatJson.Contains("\"patch.apply\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"command.runAllowed\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"rollback.restore\"", StringComparison.Ordinal)
                && patchResponse.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is true
                && afterPatch == patched
                && commandResponse.Status == "SUCCEEDED"
                && commandResponse.Output.TryGetValue("arbitraryShellAllowed", out var arbitraryShellAllowed)
                && arbitraryShellAllowed is false
                && statusResponse.Status == "SUCCEEDED"
                && statusResponse.Output.TryGetValue("clean", out var clean)
                && clean is false
                && rollbackResponse.Status == "SUCCEEDED"
                && rollbackResponse.Output.TryGetValue("restored", out var restored)
                && restored is true
                && afterRollback == original
                && identitiesPreserved;
            if (!ok)
            {
                Console.Error.WriteLine(
                    patchResponse.Error
                    ?? commandResponse.Error
                    ?? statusResponse.Error
                    ?? rollbackResponse.Error
                    ?? "approved execution flow contract self-test failed");
                return 1;
            }

            if (!string.IsNullOrWhiteSpace(reportPath))
            {
                WriteApprovedExecutionFlowReport(
                    reportPath,
                    workspaceId,
                    sourceRequestId,
                    releaseAttemptId,
                    "src/App.cs",
                    originalHash,
                    afterPatchHash,
                    afterRollbackHash,
                    patchResponse,
                    commandResponse,
                    statusResponse,
                    rollbackResponse,
                    identitiesPreserved,
                    heartbeatJson);
            }

            Console.WriteLine("approved-execution-flow-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
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

    private static void WriteApprovedExecutionFlowReport(
        string reportPath,
        Guid workspaceId,
        Guid sourceRequestId,
        Guid releaseAttemptId,
        string targetFile,
        string originalHash,
        string afterPatchHash,
        string afterRollbackHash,
        ToolResponse patchResponse,
        ToolResponse commandResponse,
        ToolResponse statusResponse,
        ToolResponse rollbackResponse,
        bool identitiesPreserved,
        string heartbeatJson)
    {
        var absolutePath = Path.GetFullPath(reportPath);
        var parent = Path.GetDirectoryName(absolutePath);
        if (!string.IsNullOrWhiteSpace(parent))
        {
            Directory.CreateDirectory(parent);
        }

        var report = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.approved-execution-flow-report.v1",
            ["createdAt"] = DateTimeOffset.UtcNow,
            ["workspaceId"] = workspaceId,
            ["sourceRequestId"] = sourceRequestId,
            ["releaseAttemptId"] = releaseAttemptId,
            ["targetFile"] = targetFile,
            ["fileEvidence"] = new Dictionary<string, object?>
            {
                ["originalSha256"] = originalHash,
                ["afterPatchSha256"] = afterPatchHash,
                ["afterRollbackSha256"] = afterRollbackHash,
                ["changedByPatch"] = !string.Equals(originalHash, afterPatchHash, StringComparison.OrdinalIgnoreCase),
                ["restoredByRollback"] = string.Equals(originalHash, afterRollbackHash, StringComparison.OrdinalIgnoreCase)
            },
            ["capabilities"] = new Dictionary<string, object?>
            {
                ["patchApply"] = heartbeatJson.Contains("\"patch.apply\"", StringComparison.Ordinal),
                ["commandRunAllowed"] = heartbeatJson.Contains("\"command.runAllowed\"", StringComparison.Ordinal),
                ["rollbackRestore"] = heartbeatJson.Contains("\"rollback.restore\"", StringComparison.Ordinal)
            },
            ["steps"] = new[]
            {
                StepEvidence("patch.apply", patchResponse, new Dictionary<string, object?>
                {
                    ["mutationApplied"] = patchResponse.Output.TryGetValue("mutationApplied", out var mutationApplied) ? mutationApplied : null
                }),
                StepEvidence("command.runAllowed", commandResponse, new Dictionary<string, object?>
                {
                    ["arbitraryShellAllowed"] = commandResponse.Output.TryGetValue("arbitraryShellAllowed", out var commandArbitraryShellAllowed) ? commandArbitraryShellAllowed : null
                }),
                StepEvidence("git.status", statusResponse, new Dictionary<string, object?>
                {
                    ["clean"] = statusResponse.Output.TryGetValue("clean", out var clean) ? clean : null
                }),
                StepEvidence("rollback.restore", rollbackResponse, new Dictionary<string, object?>
                {
                    ["restored"] = rollbackResponse.Output.TryGetValue("restored", out var restored) ? restored : null
                })
            },
            ["guardrails"] = new Dictionary<string, object?>
            {
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["identitiesPreserved"] = identitiesPreserved,
                ["arbitraryShellAllowed"] = commandResponse.Output.TryGetValue("arbitraryShellAllowed", out var guardrailArbitraryShellAllowed) ? guardrailArbitraryShellAllowed : null,
                ["serverLocalMutation"] = false,
                ["workspaceWasTemporary"] = true
            },
            ["passed"] = true
        };
        File.WriteAllText(absolutePath, JsonSerializer.Serialize(report, JsonOptions), new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
    }

    private static Dictionary<string, object?> StepEvidence(
        string toolName,
        ToolResponse response,
        Dictionary<string, object?> checks)
    {
        return new Dictionary<string, object?>
        {
            ["toolName"] = toolName,
            ["status"] = response.Status,
            ["requestId"] = response.RequestId,
            ["failureCode"] = response.FailureCode,
            ["checks"] = checks
        };
    }

    private static JsonDocument FlowToolRequest(
        Guid sessionId,
        Guid userId,
        Guid agentId,
        Guid workspaceId,
        string toolName,
        string approvalState,
        Dictionary<string, object?> input) =>
        JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["sessionId"] = sessionId,
            ["userId"] = userId,
            ["agentId"] = agentId,
            ["workspaceId"] = workspaceId,
            ["executionTarget"] = "USER_LOCAL_AGENT",
            ["toolName"] = toolName,
            ["approvalState"] = approvalState,
            ["input"] = input
        }, JsonOptions));

    private static FlowSetupProcessResult RunFlowSetupProcess(
        string executable,
        IReadOnlyList<string> args,
        string workingDirectory,
        TimeSpan timeout)
    {
        using var process = new Process();
        process.StartInfo.FileName = executable;
        foreach (var arg in args)
        {
            process.StartInfo.ArgumentList.Add(arg);
        }
        process.StartInfo.WorkingDirectory = workingDirectory;
        process.StartInfo.UseShellExecute = false;
        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        try
        {
            process.Start();
            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
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
                return FlowSetupProcessResult.Fail(executable + " setup timed out.");
            }

            var stderr = stderrTask.GetAwaiter().GetResult();
            _ = stdoutTask.GetAwaiter().GetResult();
            return process.ExitCode == 0
                ? FlowSetupProcessResult.Ok()
                : FlowSetupProcessResult.Fail(string.IsNullOrWhiteSpace(stderr) ? executable + " setup failed." : stderr.Trim());
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return FlowSetupProcessResult.Fail(executable + " setup failed: " + ex.Message);
        }
    }
}

internal sealed record FlowSetupProcessResult(bool Success, string? Error)
{
    public static FlowSetupProcessResult Ok() => new(true, null);

    public static FlowSetupProcessResult Fail(string error) => new(false, error);
}
