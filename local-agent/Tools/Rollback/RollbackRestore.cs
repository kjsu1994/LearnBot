using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private ToolResult RestoreRollbackSnapshot(AgentConfig config, Guid? workspaceId, JsonElement request)
    {
        if (!request.TryGetProperty("input", out var input))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "rollback.restore input is required.");
        }
        var approvalState = request.TryGetProperty("approvalState", out var approval)
            ? approval.GetString()
            : null;
        if (!string.Equals(approvalState, "APPROVED", StringComparison.Ordinal))
        {
            return ToolResult.Fail("REJECTED", "APPROVAL_REQUIRED", "rollback.restore requires an approved Local Agent tool request.");
        }

        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var manifestId = TryInputStringFromObject(input, "manifestId")
            ?? TryInputStringFromObject(input, "snapshotManifestId");
        if (string.IsNullOrWhiteSpace(manifestId))
        {
            return ToolResult.Fail("REJECTED", "ROLLBACK_REFUSED", "rollback.restore requires a managed snapshot manifest id.");
        }

        var restore = RestoreManagedSnapshot(workspace.Workspace!.WorkspaceId, workspace.Root!, manifestId);
        if (!restore.Success)
        {
            return ToolResult.Fail("REJECTED", "ROLLBACK_REFUSED", restore.Error ?? "rollback.restore refused.", restore.Output);
        }
        return ToolResult.Ok(restore.Output);
    }

    private static RollbackRestoreResult RestoreManagedSnapshot(Guid workspaceId, string workspaceRoot, string manifestId)
    {
        if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, [], out var baseLayout, out var baseLayoutError) || baseLayout is null)
        {
            return RollbackRestoreResult.Failed(baseLayoutError ?? "Invalid snapshot manifest id.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
        }
        if (!File.Exists(baseLayout.ManifestPath))
        {
            return RollbackRestoreResult.Failed("Snapshot manifest was not found.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
        }

        try
        {
            using var manifestJson = JsonDocument.Parse(File.ReadAllText(baseLayout.ManifestPath, Encoding.UTF8));
            var manifest = manifestJson.RootElement;
            if (!string.Equals(TryInputStringFromObject(manifest, "schema"), "learnbot.local-agent.snapshot-manifest.v1", StringComparison.Ordinal))
            {
                return RollbackRestoreResult.Failed("Snapshot manifest schema is not supported.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }
            if (!string.Equals(TryInputStringFromObject(manifest, "id"), manifestId, StringComparison.Ordinal))
            {
                return RollbackRestoreResult.Failed("Snapshot manifest id does not match the requested id.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }
            if (!Guid.TryParse(TryInputStringFromObject(manifest, "workspaceId"), out var manifestWorkspaceId) || manifestWorkspaceId != workspaceId)
            {
                return RollbackRestoreResult.Failed("Snapshot workspace does not match the approved workspace.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }
            var manifestWorkspaceRoot = TryInputStringFromObject(manifest, "workspaceRoot");
            if (string.IsNullOrWhiteSpace(manifestWorkspaceRoot) || !PathEquals(manifestWorkspaceRoot, workspaceRoot))
            {
                return RollbackRestoreResult.Failed("Snapshot workspace root does not match the approved workspace.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }
            if (!manifest.TryGetProperty("files", out var filesElement) || filesElement.ValueKind != JsonValueKind.Array)
            {
                return RollbackRestoreResult.Failed("Snapshot manifest files are missing.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }

            var targetPaths = new List<string>();
            foreach (var file in filesElement.EnumerateArray())
            {
                var path = TryInputStringFromObject(file, "path");
                if (string.IsNullOrWhiteSpace(path))
                {
                    return RollbackRestoreResult.Failed("Snapshot file path is missing.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
                }
                targetPaths.Add(path.Replace('\\', '/'));
            }
            if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, targetPaths, out var layout, out var layoutError) || layout is null)
            {
                return RollbackRestoreResult.Failed(layoutError ?? "Invalid snapshot layout.", RollbackRestoreOutput(workspaceId, manifestId, restored: false));
            }

            var restoredFiles = new List<Dictionary<string, object?>>();
            foreach (var file in filesElement.EnumerateArray())
            {
                var path = TryInputStringFromObject(file, "path")!.Replace('\\', '/');
                var snapshotRelativePath = TryInputStringFromObject(file, "snapshotRelativePath");
                var layoutFile = layout.Files.First(item => string.Equals(item.Path, path, StringComparison.Ordinal));
                if (!string.Equals(snapshotRelativePath, layoutFile.SnapshotRelativePath, StringComparison.Ordinal))
                {
                    return RollbackRestoreResult.Failed("Snapshot file path does not match the managed layout: " + path, RollbackRestoreOutput(workspaceId, manifestId, restored: false));
                }

                var snapshotPath = Path.GetFullPath(layoutFile.DestinationPath);
                if (!IsWithin(layout.SnapshotRoot, snapshotPath) || !File.Exists(snapshotPath))
                {
                    return RollbackRestoreResult.Failed("Snapshot file was not found: " + path, RollbackRestoreOutput(workspaceId, manifestId, restored: false));
                }

                var targetPath = Path.GetFullPath(Path.Combine(workspaceRoot, path.Replace('/', Path.DirectorySeparatorChar)));
                if (!IsWithin(workspaceRoot, targetPath))
                {
                    return RollbackRestoreResult.Failed("Rollback target escapes the approved workspace: " + path, RollbackRestoreOutput(workspaceId, manifestId, restored: false));
                }

                var hadTarget = File.Exists(targetPath);
                var beforeBytes = hadTarget ? File.ReadAllBytes(targetPath) : [];
                var snapshotBytes = File.ReadAllBytes(snapshotPath);
                Directory.CreateDirectory(Path.GetDirectoryName(targetPath)!);
                File.Copy(snapshotPath, targetPath, overwrite: true);
                restoredFiles.Add(new Dictionary<string, object?>
                {
                    ["path"] = path,
                    ["beforeSha256"] = hadTarget ? Sha256Hex(beforeBytes) : null,
                    ["restoredSha256"] = Sha256Hex(snapshotBytes),
                    ["bytes"] = snapshotBytes.LongLength
                });
            }

            var output = RollbackRestoreOutput(workspaceId, manifestId, restored: true);
            output["files"] = restoredFiles;
            output["sourceRequestId"] = TryInputStringFromObject(manifest, "sourceRequestId");
            return RollbackRestoreResult.Succeeded(output);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return RollbackRestoreResult.Failed("Rollback restore failed: " + ex.Message, RollbackRestoreOutput(workspaceId, manifestId, restored: false));
        }
    }

    private static Dictionary<string, object?> RollbackRestoreOutput(Guid workspaceId, string manifestId, bool restored) => new()
    {
        ["workspaceId"] = workspaceId,
        ["manifestId"] = manifestId,
        ["tool"] = "rollback.restore",
        ["restored"] = restored,
        ["restoreScope"] = "SNAPSHOT_TARGET_FILES",
        ["source"] = "LOCAL_AGENT_MANAGED_SNAPSHOT"
    };

    private static int SelfTestRollbackRestoreContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-rollback-restore-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
            var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
            var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
            var requestId = Guid.Parse("66666666-6666-6666-6666-666666666666");
            var workspaceRoot = Path.Combine(root, "workspace");
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src"));
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var targetPath = Path.Combine(workspaceRoot, "src", "App.cs");
            var original = "class App { string Name = \"old\"; }\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var originalHash = Sha256Hex(File.ReadAllBytes(targetPath));
            using var inputJson = JsonDocument.Parse("""{"sourceRequestId":"22222222-2222-2222-2222-222222222222"}""");
            var snapshot = CreateSnapshot(workspaceId, workspaceRoot, inputJson.RootElement, [
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
                ? id!.ToString()!
                : "";
            File.WriteAllText(targetPath, "class App { string Name = \"changed\"; }\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            using var requestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "rollback.restore",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?> { ["manifestId"] = manifestId }
            }, JsonOptions));
            var config = new AgentConfig
            {
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };

            var response = new LearnBotLocalAgent().HandleTool(config, requestId, requestJson.RootElement, "rollback.restore");
            using var unapprovedJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "rollback.restore",
                ["approvalState"] = "REQUIRED",
                ["input"] = new Dictionary<string, object?> { ["manifestId"] = manifestId }
            }, JsonOptions));
            var unapproved = new LearnBotLocalAgent().HandleTool(config, requestId, unapprovedJson.RootElement, "rollback.restore");
            using var invalidManifestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "rollback.restore",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?> { ["manifestId"] = "../escape" }
            }, JsonOptions));
            var invalidManifest = new LearnBotLocalAgent().HandleTool(config, requestId, invalidManifestJson.RootElement, "rollback.restore");
            var ok = response.Status == "SUCCEEDED"
                && response.ToolName == "rollback.restore"
                && File.ReadAllText(targetPath, Encoding.UTF8) == original
                && response.Output.TryGetValue("restored", out var restored)
                && restored is true
                && response.Output.TryGetValue("manifestId", out var observedManifestId)
                && string.Equals(observedManifestId?.ToString(), manifestId, StringComparison.Ordinal)
                && unapproved.Status == "REJECTED"
                && unapproved.FailureCode == "APPROVAL_REQUIRED"
                && invalidManifest.Status == "REJECTED"
                && invalidManifest.FailureCode == "ROLLBACK_REFUSED";
            if (!ok)
            {
                Console.Error.WriteLine(response.Error ?? "rollback restore contract self-test failed");
                return 1;
            }
            Console.WriteLine("rollback-restore-contract-ok");
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
}

internal sealed record RollbackRestoreResult(bool Success, string? Error, Dictionary<string, object?> Output)
{
    public static RollbackRestoreResult Succeeded(Dictionary<string, object?> output) => new(true, null, output);

    public static RollbackRestoreResult Failed(string error, Dictionary<string, object?> output) => new(false, error, output);
}
