using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private ToolResult HandlePatchApply(AgentConfig config, Guid? workspaceId, JsonElement request)
    {
        if (!request.TryGetProperty("input", out var input))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "patch.apply input is required.");
        }
        if (TryInputBool(input, "mutationAllowed") == true)
        {
            return ApplyPatchMutation(config, workspaceId, request, input);
        }
        return DryRunPatchApply(config, workspaceId, request);
    }

    private ToolResult ApplyPatchMutation(AgentConfig config, Guid? workspaceId, JsonElement request, JsonElement input)
    {
        var approvalState = request.TryGetProperty("approvalState", out var approval)
            ? approval.GetString()
            : null;
        if (!string.Equals(approvalState, "APPROVED", StringComparison.Ordinal))
        {
            return ToolResult.Fail("REJECTED", "APPROVAL_REQUIRED", "patch.apply mutation requires an approved Local Agent tool request.");
        }
        if (TryInputBool(input, "dryRunOnly") == true)
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "patch.apply mutation refuses dryRunOnly=true requests.");
        }

        var manifestId = TryInputStringFromObject(input, "manifestId")
            ?? TryInputStringFromObject(input, "snapshotManifestId");
        if (string.IsNullOrWhiteSpace(manifestId))
        {
            return ToolResult.Fail("REJECTED", "ROLLBACK_REFUSED", "patch.apply mutation requires a managed rollback snapshot manifest id.");
        }

        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var diff = TryInputString(request, "diff") ?? "";
        if (string.IsNullOrWhiteSpace(diff))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "patch.apply diff is required.");
        }
        if (Encoding.UTF8.GetByteCount(diff) > AbsoluteMaxPatchBytes)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "patch.apply diff exceeds the local safety limit.");
        }

        var parsed = ParseUnifiedDiff(diff);
        if (!parsed.Success)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", parsed.Error ?? "Invalid unified diff.");
        }
        if (parsed.Files.Count == 0 || parsed.Files.Count > 5)
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "patch.apply mutation requires between one and five files.");
        }
        if (parsed.Files.Any(file => file.IsDelete))
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "Deleting files is not supported by patch.apply.");
        }
        if (parsed.Files.Select(file => file.Path).Distinct(StringComparer.Ordinal).Count() != parsed.Files.Count)
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "patch.apply mutation refuses duplicate file entries.");
        }

        var targetFiles = TryInputStringList(input, "targetFiles");
        var outsideTarget = targetFiles.Count == 0
            ? null
            : parsed.Files.FirstOrDefault(file => !targetFiles.Contains(file.Path, StringComparer.Ordinal));
        if (outsideTarget is not null)
        {
            return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Patch modifies a file outside targetFiles: " + outsideTarget.Path);
        }

        var manifest = LoadManagedSnapshotManifest(workspace.Workspace!.WorkspaceId, workspace.Root!, manifestId);
        if (!manifest.Success)
        {
            return ToolResult.Fail("REJECTED", "ROLLBACK_REFUSED", manifest.Error ?? "Managed rollback snapshot is not valid.", PatchMutationOutput(workspace.Workspace!.WorkspaceId, manifestId, false, []));
        }

        var fileOutput = new List<Dictionary<string, object?>>();
        foreach (var patchFile in parsed.Files)
        {
            if (!manifest.Files.TryGetValue(patchFile.Path, out var snapshotFile))
            {
                return ToolResult.Fail("REJECTED", "ROLLBACK_REFUSED", "Managed rollback snapshot does not cover patch target: " + patchFile.Path, PatchMutationOutput(workspace.Workspace!.WorkspaceId, manifestId, false, fileOutput));
            }

            var targetPath = Path.GetFullPath(Path.Combine(workspace.Root!, patchFile.Path.Replace('/', Path.DirectorySeparatorChar)));
            if (!IsWithin(workspace.Root!, targetPath))
            {
                return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Patch target escapes the approved workspace: " + patchFile.Path);
            }

            var write = TryWritePatchedFileWithRecheck(workspace.Root!, targetPath, patchFile, snapshotFile.ActualSha256, snapshotFile.ExistedBefore);
            fileOutput.Add(new Dictionary<string, object?>
            {
                    ["path"] = patchFile.Path,
                    ["operation"] = snapshotFile.ExistedBefore ? "update" : "create",
                    ["beforeSha256"] = write.BeforeSha256,
                    ["afterSha256"] = write.AfterSha256,
                    ["beforeBytes"] = write.BeforeBytes,
                    ["afterBytes"] = write.AfterBytes,
                    ["lineEnding"] = write.LineEnding,
                    ["applied"] = write.Success,
                    ["error"] = write.Error
            });
            if (!write.Success)
            {
                return ToolResult.Fail("REJECTED", "CONTEXT_MISMATCH", write.Error ?? "Patch write failed.", PatchMutationOutput(workspace.Workspace!.WorkspaceId, manifestId, false, fileOutput));
            }
        }

        return ToolResult.Ok(PatchMutationOutput(workspace.Workspace!.WorkspaceId, manifestId, true, fileOutput));
    }

    private static ManagedSnapshotManifestResult LoadManagedSnapshotManifest(Guid workspaceId, string workspaceRoot, string manifestId)
    {
        if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, [], out var baseLayout, out var baseLayoutError) || baseLayout is null)
        {
            return ManagedSnapshotManifestResult.Failed(baseLayoutError ?? "Invalid snapshot manifest id.");
        }
        if (!File.Exists(baseLayout.ManifestPath))
        {
            return ManagedSnapshotManifestResult.Failed("Snapshot manifest was not found.");
        }

        try
        {
            using var manifestJson = JsonDocument.Parse(File.ReadAllText(baseLayout.ManifestPath, Encoding.UTF8));
            var manifest = manifestJson.RootElement;
            if (!string.Equals(TryInputStringFromObject(manifest, "schema"), "learnbot.local-agent.snapshot-manifest.v1", StringComparison.Ordinal))
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot manifest schema is not supported.");
            }
            if (!string.Equals(TryInputStringFromObject(manifest, "id"), manifestId, StringComparison.Ordinal))
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot manifest id does not match the requested id.");
            }
            if (!Guid.TryParse(TryInputStringFromObject(manifest, "workspaceId"), out var manifestWorkspaceId) || manifestWorkspaceId != workspaceId)
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot workspace does not match the approved workspace.");
            }
            var manifestWorkspaceRoot = TryInputStringFromObject(manifest, "workspaceRoot");
            if (string.IsNullOrWhiteSpace(manifestWorkspaceRoot) || !PathEquals(manifestWorkspaceRoot, workspaceRoot))
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot workspace root does not match the approved workspace.");
            }
            if (!manifest.TryGetProperty("files", out var filesElement) || filesElement.ValueKind != JsonValueKind.Array)
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot manifest files are missing.");
            }

            var targetPaths = filesElement.EnumerateArray()
                .Select(file => TryInputStringFromObject(file, "path")?.Replace('\\', '/'))
                .Where(path => !string.IsNullOrWhiteSpace(path))
                .Select(path => path!)
                .ToList();
            if (targetPaths.Count == 0)
            {
                return ManagedSnapshotManifestResult.Failed("Snapshot manifest files are missing.");
            }
            if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, targetPaths, out var layout, out var layoutError) || layout is null)
            {
                return ManagedSnapshotManifestResult.Failed(layoutError ?? "Invalid snapshot layout.");
            }

            var files = new Dictionary<string, ManagedSnapshotFile>(StringComparer.Ordinal);
            foreach (var file in filesElement.EnumerateArray())
            {
                var path = TryInputStringFromObject(file, "path")?.Replace('\\', '/');
                if (string.IsNullOrWhiteSpace(path))
                {
                    return ManagedSnapshotManifestResult.Failed("Snapshot file path is missing.");
                }
                var layoutFile = layout.Files.First(item => string.Equals(item.Path, path, StringComparison.Ordinal));
                if (!string.Equals(TryInputStringFromObject(file, "snapshotRelativePath"), layoutFile.SnapshotRelativePath, StringComparison.Ordinal))
                {
                    return ManagedSnapshotManifestResult.Failed("Snapshot file path does not match the managed layout: " + path);
                }
                if (!File.Exists(layoutFile.DestinationPath))
                {
                    return ManagedSnapshotManifestResult.Failed("Snapshot file was not found: " + path);
                }
                var actualSha256 = TryInputStringFromObject(file, "actualSha256");
                if (string.IsNullOrWhiteSpace(actualSha256))
                {
                    return ManagedSnapshotManifestResult.Failed("Snapshot file hash is missing: " + path);
                }
                var existedBefore = TryInputBool(file, "existedBefore") ?? true;
                var snapshotSha256 = Sha256Hex(File.ReadAllBytes(layoutFile.DestinationPath));
                if (!string.Equals(actualSha256, snapshotSha256, StringComparison.OrdinalIgnoreCase))
                {
                    return ManagedSnapshotManifestResult.Failed("Snapshot file hash does not match the manifest: " + path);
                }
                files[path] = new ManagedSnapshotFile(
                    path,
                    actualSha256,
                    layoutFile.DestinationPath,
                    existedBefore);
            }
            return ManagedSnapshotManifestResult.Ok(files);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return ManagedSnapshotManifestResult.Failed("Snapshot manifest could not be read: " + ex.Message);
        }
    }

    private static Dictionary<string, object?> PatchMutationOutput(
        Guid workspaceId,
        string manifestId,
        bool mutationApplied,
        List<Dictionary<string, object?>> files) => new()
    {
        ["workspaceId"] = workspaceId,
        ["dryRun"] = false,
        ["mutationApplied"] = mutationApplied,
        ["snapshotManifestId"] = manifestId,
        ["rollbackAvailable"] = true,
        ["rollbackTool"] = "rollback.restore",
        ["files"] = files
    };

    private static int SelfTestPatchApplyMutationContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-patch-mutation-" + Guid.NewGuid().ToString("N"));
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
            var original = "class App {\n    string Name = \"old\";\n}\n";
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
            var config = new AgentConfig
            {
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            using var requestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "patch.apply",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?>
                {
                    ["mutationAllowed"] = true,
                    ["dryRunOnly"] = false,
                    ["manifestId"] = manifestId,
                    ["diff"] = diff,
                    ["targetFiles"] = new[] { "src/App.cs" }
                }
            }, JsonOptions));
            var response = new LearnBotLocalAgent().HandleTool(config, requestId, requestJson.RootElement, "patch.apply");
            var afterApply = File.ReadAllText(targetPath, Encoding.UTF8);

            using var rollbackJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
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
            var rollback = new LearnBotLocalAgent().HandleTool(config, requestId, rollbackJson.RootElement, "rollback.restore");

            var newTargetPath = Path.Combine(workspaceRoot, "src", "NewPage.html");
            var emptyHash = Sha256Hex([]);
            var createSnapshot = CreateSnapshot(workspaceId, workspaceRoot, inputJson.RootElement, [
                new Dictionary<string, object?>
                {
                    ["path"] = "src/NewPage.html",
                    ["absolutePath"] = newTargetPath,
                    ["operation"] = "create",
                    ["existedBefore"] = false,
                    ["actualSha256"] = emptyHash,
                    ["hashMatches"] = true,
                    ["contextMatches"] = true
                }
            ]);
            var createManifestId = createSnapshot.Manifest is not null && createSnapshot.Manifest.TryGetValue("id", out var createId)
                ? createId!.ToString()!
                : "";
            var createDiff = """
            --- /dev/null
            +++ b/src/NewPage.html
            @@ -0,0 +1,3 @@
            +<!doctype html>
            +<title>New</title>
            +<main>Hello</main>
            """;
            using var createRequestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "patch.apply",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?>
                {
                    ["mutationAllowed"] = true,
                    ["dryRunOnly"] = false,
                    ["manifestId"] = createManifestId,
                    ["diff"] = createDiff,
                    ["targetFiles"] = new[] { "src/NewPage.html" }
                }
            }, JsonOptions));
            var createResponse = new LearnBotLocalAgent().HandleTool(config, requestId, createRequestJson.RootElement, "patch.apply");
            var createdContent = File.Exists(newTargetPath) ? File.ReadAllText(newTargetPath, Encoding.UTF8) : "";
            using var createRollbackJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "rollback.restore",
                ["approvalState"] = "APPROVED",
                ["input"] = new Dictionary<string, object?> { ["manifestId"] = createManifestId }
            }, JsonOptions));
            var createRollback = new LearnBotLocalAgent().HandleTool(config, requestId, createRollbackJson.RootElement, "rollback.restore");

            using var unapprovedJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "patch.apply",
                ["approvalState"] = "REQUIRED",
                ["input"] = new Dictionary<string, object?>
                {
                    ["mutationAllowed"] = true,
                    ["dryRunOnly"] = false,
                    ["manifestId"] = manifestId,
                    ["diff"] = diff
                }
            }, JsonOptions));
            var unapproved = new LearnBotLocalAgent().HandleTool(config, requestId, unapprovedJson.RootElement, "patch.apply");

            var ok = response.Status == "SUCCEEDED"
                && response.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is true
                && afterApply == "class App {\n    string Name = \"new\";\n    string Mode = \"safe\";\n}\n"
                && rollback.Status == "SUCCEEDED"
                && File.ReadAllText(targetPath, Encoding.UTF8) == original
                && createResponse.Status == "SUCCEEDED"
                && createdContent.Contains("<main>Hello</main>", StringComparison.Ordinal)
                && createRollback.Status == "SUCCEEDED"
                && !File.Exists(newTargetPath)
                && unapproved.Status == "REJECTED"
                && unapproved.FailureCode == "APPROVAL_REQUIRED";
            if (!ok)
            {
                Console.Error.WriteLine(response.Error ?? "patch apply mutation contract self-test failed");
                return 1;
            }
            Console.WriteLine("patch-apply-mutation-contract-ok");
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

internal sealed record ManagedSnapshotFile(string Path, string? ActualSha256, string SnapshotPath, bool ExistedBefore);

internal sealed record ManagedSnapshotManifestResult(bool Success, string? Error, Dictionary<string, ManagedSnapshotFile> Files)
{
    public static ManagedSnapshotManifestResult Ok(Dictionary<string, ManagedSnapshotFile> files) => new(true, null, files);

    public static ManagedSnapshotManifestResult Failed(string error) => new(false, error, new(StringComparer.Ordinal));
}
