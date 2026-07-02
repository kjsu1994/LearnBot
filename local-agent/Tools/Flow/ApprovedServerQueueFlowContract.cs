using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private static int SelfTestApprovedServerQueueSecondAttemptContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-server-queue-second-attempt-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        TcpListener? listener = null;
        CancellationTokenSource? serverCancellation = null;
        Task? serverTask = null;
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
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var gitInit = RunFlowSetupProcess("git", ["init"], workspaceRoot, TimeSpan.FromSeconds(10));
            if (!gitInit.Success)
            {
                Console.Error.WriteLine(gitInit.Error ?? "approved server queue second-attempt setup could not initialize git");
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
                Console.Error.WriteLine(snapshot.Error ?? "approved server queue second-attempt could not create snapshot");
                return 1;
            }

            var firstDiff = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,4 @@
             class App {
            -    string Name = "old";
            +    string Name = "new";
            +    string Mode = "safe";
             }
            """;
            var revisedDryRunDiff = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,4 +1,4 @@
             class App {
            -    string Name = "new";
            +    string Name = "fixed";
                 string Mode = "safe";
             }
            """;
            var queued = new Queue<QueuedToolRequest>([
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888881"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "patch.apply",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["mutationAllowed"] = true,
                        ["dryRunOnly"] = false,
                        ["manifestId"] = manifestId,
                        ["diff"] = firstDiff,
                        ["targetFiles"] = new[] { "src/App.cs" }
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888882"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "command.runAllowed",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["commandId"] = "dotnet.test",
                        ["timeoutSeconds"] = 30,
                        ["maxOutputBytes"] = 4096
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888883"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "git.status",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888885"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "patch.apply",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["mutationAllowed"] = false,
                        ["dryRunOnly"] = true,
                        ["diff"] = revisedDryRunDiff,
                        ["targetFiles"] = new[] { "src/App.cs" }
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888884"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "rollback.restore",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["manifestId"] = manifestId
                    })
            ]);
            var responses = new List<ToolResponse>();
            listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var serverUrl = "http://127.0.0.1:" + ((IPEndPoint)listener.LocalEndpoint).Port;
            serverCancellation = new CancellationTokenSource();
            serverTask = RunApprovedFlowQueueServer(listener, queued, responses, serverCancellation.Token);

            var config = new AgentConfig
            {
                ServerUrl = serverUrl,
                Token = "server-queue-second-attempt-token",
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var agent = new LearnBotLocalAgent();
            for (var i = 0; i < 5; i++)
            {
                agent.PollOnce(config).GetAwaiter().GetResult();
            }

            var patchResponses = responses
                .Where(item => item.ToolName == "patch.apply")
                .OrderBy(item => item.RequestId)
                .ToList();
            var mutationResponse = patchResponses.First();
            var dryRunResponse = patchResponses.Last();
            var commandResponse = responses.Single(item => item.ToolName == "command.runAllowed");
            var statusResponse = responses.Single(item => item.ToolName == "git.status");
            var rollbackResponse = responses.Single(item => item.ToolName == "rollback.restore");
            var retryDecision = BuildPatchTestRetryDecisionReport(commandResponse, statusResponse);
            var proposalPlan = BuildRevisedPatchProposalPlanReport(retryDecision, commandResponse, ["src/App.cs"]);
            var modelRequest = new Dictionary<string, object?>
            {
                ["schema"] = "learnbot.local-agent.local-model-revised-patch-request.v1",
                ["status"] = "READY_MODEL_CALL_DISABLED",
                ["targetFiles"] = new List<string> { "src/App.cs" },
                ["maxDiffChars"] = 24000,
                ["mutationAllowed"] = false
            };
            var modelOutput = new Dictionary<string, object?>
            {
                ["schema"] = "learnbot.local-agent.local-model-revised-patch-output.v1",
                ["targetFiles"] = new List<string> { "src/App.cs" },
                ["unifiedDiff"] = revisedDryRunDiff,
                ["rationale"] = "Use the failed allowlisted test output to refine the first patch.",
                ["riskNotes"] = "Single target file."
            };
            var validatedOutput = BuildLocalModelRevisedPatchOutputValidationReport(modelRequest, modelOutput);
            var dryRunHandoff = BuildValidatedRevisedPatchDryRunHandoffReport(validatedOutput);
            var secondAttempt = BuildPatchTestSecondAttemptReport(
                retryDecision,
                dryRunResponse,
                ExtractRevisedPatchProposalEntries(proposalPlan),
                dryRunHandoff);
            var approvalRequest = BuildRevisedPatchApprovalRequestReport(
                secondAttempt,
                dryRunResponse,
                "src/App.cs",
                revisedDryRunDiff,
                staleIndexDisclosureRequired: true);
            var persistedApproval = BuildRevisedPatchApprovalPersistenceReport(
                approvalRequest,
                approved: true,
                approvedByUserId: userId);
            var approvedMutationCandidate = new Dictionary<string, object?>
            {
                ["approvalRequestId"] = approvalRequest.TryGetValue("approvalRequestId", out var approvalRequestId) ? approvalRequestId : null,
                ["mutationAllowed"] = true,
                ["dryRunOnly"] = false,
                ["targetFiles"] = new[] { "src/App.cs" },
                ["manifestId"] = manifestId
            };
            var missingApprovalCandidate = new Dictionary<string, object?>(approvedMutationCandidate, StringComparer.Ordinal)
            {
                ["approvalRequestId"] = null
            };
            var allowedGate = BuildRevisedPatchMutationGateReport(persistedApproval, approvedMutationCandidate);
            var blockedGate = BuildRevisedPatchMutationGateReport(persistedApproval, missingApprovalCandidate);
            var afterRollback = File.ReadAllText(targetPath, Encoding.UTF8);
            var ok = responses.Count == 5
                && mutationResponse.Status == "SUCCEEDED"
                && File.Exists(targetPath)
                && commandResponse.Status == "FAILED"
                && commandResponse.FailureCode == "TEST_FAILED"
                && retryDecision.TryGetValue("status", out var retryStatus)
                && string.Equals(retryStatus?.ToString(), "RETRY_RECOMMENDED", StringComparison.Ordinal)
                && proposalPlan.TryGetValue("status", out var proposalPlanStatus)
                && string.Equals(proposalPlanStatus?.ToString(), "READY_MODEL_DISABLED", StringComparison.Ordinal)
                && proposalPlan.TryGetValue("approvalRequiredBeforeMutation", out var proposalApprovalRequired)
                && proposalApprovalRequired is true
                && validatedOutput.TryGetValue("status", out var validatedOutputStatus)
                && string.Equals(validatedOutputStatus?.ToString(), "READY_FOR_DRY_RUN", StringComparison.Ordinal)
                && dryRunHandoff.TryGetValue("status", out var handoffStatus)
                && string.Equals(handoffStatus?.ToString(), "READY_DRY_RUN_QUEUE_DISABLED", StringComparison.Ordinal)
                && dryRunResponse.Status == "REJECTED"
                && dryRunResponse.FailureCode == "UNSAFE_TOOL"
                && dryRunResponse.Output.TryGetValue("dryRun", out var dryRun)
                && dryRun is true
                && dryRunResponse.Output.TryGetValue("preflightPassed", out var preflightPassed)
                && preflightPassed is true
                && dryRunResponse.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is false
                && secondAttempt.TryGetValue("status", out var secondAttemptStatus)
                && string.Equals(secondAttemptStatus?.ToString(), "APPROVAL_REQUIRED", StringComparison.Ordinal)
                && secondAttempt.TryGetValue("dryRunHandoff", out var secondAttemptHandoffRaw)
                && secondAttemptHandoffRaw is Dictionary<string, object?> secondAttemptHandoff
                && secondAttemptHandoff.TryGetValue("consumed", out var handoffConsumed)
                && handoffConsumed is true
                && secondAttemptHandoff.TryGetValue("targetsMatchDryRun", out var handoffTargetsMatch)
                && handoffTargetsMatch is true
                && secondAttempt.TryGetValue("actualMutationExecuted", out var actualMutationExecuted)
                && actualMutationExecuted is false
                && approvalRequest.TryGetValue("status", out var approvalRequestStatus)
                && string.Equals(approvalRequestStatus?.ToString(), "READY", StringComparison.Ordinal)
                && approvalRequest.TryGetValue("nextQueuedMutationPreconditions", out var preconditionsRaw)
                && preconditionsRaw is List<string> preconditions
                && preconditions.Contains("approvalState=APPROVED")
                && approvalRequest.TryGetValue("staleIndexDisclosureRequired", out var staleIndexDisclosureRequired)
                && staleIndexDisclosureRequired is true
                && persistedApproval.TryGetValue("status", out var persistedApprovalStatus)
                && string.Equals(persistedApprovalStatus?.ToString(), "APPROVED", StringComparison.Ordinal)
                && allowedGate.TryGetValue("status", out var allowedGateStatus)
                && string.Equals(allowedGateStatus?.ToString(), "ALLOWED", StringComparison.Ordinal)
                && blockedGate.TryGetValue("status", out var blockedGateStatus)
                && string.Equals(blockedGateStatus?.ToString(), "BLOCKED", StringComparison.Ordinal)
                && rollbackResponse.Status == "SUCCEEDED"
                && afterRollback == original;
            if (!ok)
            {
                Dictionary<string, object?>? failedHandoffMap = secondAttempt.TryGetValue("dryRunHandoff", out var failedHandoffRaw)
                    ? failedHandoffRaw as Dictionary<string, object?>
                    : null;
                Console.Error.WriteLine(
                    "approved server queue second-attempt contract self-test failed: "
                    + "validatedOutput=" + (validatedOutput.TryGetValue("status", out var failedValidatedStatus) ? failedValidatedStatus : null)
                    + ", handoff=" + (dryRunHandoff.TryGetValue("status", out var failedHandoffStatus) ? failedHandoffStatus : null)
                    + ", secondAttempt=" + (secondAttempt.TryGetValue("status", out var failedSecondAttemptStatus) ? failedSecondAttemptStatus : null)
                    + ", handoffConsumed=" + (failedHandoffMap != null && failedHandoffMap.TryGetValue("consumed", out var failedConsumed) ? failedConsumed : null)
                    + ", targetsMatch=" + (failedHandoffMap != null && failedHandoffMap.TryGetValue("targetsMatchDryRun", out var failedTargetsMatch) ? failedTargetsMatch : null)
                    + ", handoffTargets=" + (failedHandoffMap != null && failedHandoffMap.TryGetValue("targetFiles", out var failedHandoffTargets) ? JsonSerializer.Serialize(failedHandoffTargets, JsonOptions) : "null")
                    + ", dryRunTargets=" + (failedHandoffMap != null && failedHandoffMap.TryGetValue("dryRunResponseTargetFiles", out var failedDryRunTargets) ? JsonSerializer.Serialize(failedDryRunTargets, JsonOptions) : "null")
                    + ", error=" + (mutationResponse.Error
                    ?? commandResponse.Error
                    ?? dryRunResponse.Error
                    ?? rollbackResponse.Error
                    ?? "none"));
                return 1;
            }

            Console.WriteLine("approved-server-queue-second-attempt-contract-ok");
            return 0;
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine("approved server queue second-attempt TCP listener failed: " + ex.Message);
            return 1;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            if (serverCancellation is not null)
            {
                serverCancellation.Cancel();
            }
            if (listener is not null)
            {
                try
                {
                    listener.Stop();
                }
                catch (SocketException)
                {
                }
            }
            if (serverTask is not null)
            {
                try
                {
                    serverTask.Wait(TimeSpan.FromSeconds(2));
                }
                catch (AggregateException)
                {
                }
            }
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

    private static int SelfTestApprovedServerQueueFlowContract(string? reportPath = null)
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-server-queue-flow-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        TcpListener? listener = null;
        CancellationTokenSource? serverCancellation = null;
        Task? serverTask = null;
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
            var featurePath = Path.Combine(workspaceRoot, "src", "Feature.cs");
            var original = "class App {\n    string Name = \"old\";\n}\n";
            var feature = "class Feature {\n    string Name = \"helper\";\n}\n";
            var patched = "class App {\n    string Name = \"new\";\n    string Mode = \"safe\";\n}\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(featurePath, feature, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var gitInit = RunFlowSetupProcess("git", ["init"], workspaceRoot, TimeSpan.FromSeconds(10));
            if (!gitInit.Success)
            {
                Console.Error.WriteLine(gitInit.Error ?? "approved server queue flow setup could not initialize git");
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
                Console.Error.WriteLine(snapshot.Error ?? "approved server queue flow could not create snapshot");
                return 1;
            }

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
            var queued = new Queue<QueuedToolRequest>([
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888870"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "workspace.tree",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["path"] = ".",
                        ["maxEntries"] = 50,
                        ["maxDepth"] = 4
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888869"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "workspace.search",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["path"] = ".",
                        ["query"] = "Name",
                        ["maxMatches"] = 10,
                        ["maxFiles"] = 50
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888871"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "file.read",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["path"] = "src/App.cs",
                        ["maxBytes"] = 4096
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888873"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "file.read",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["path"] = "src/Feature.cs",
                        ["maxBytes"] = 4096
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888872"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "git.status",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888881"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "patch.apply",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["mutationAllowed"] = true,
                        ["dryRunOnly"] = false,
                        ["manifestId"] = manifestId,
                        ["diff"] = diff,
                        ["targetFiles"] = new[] { "src/App.cs" }
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888882"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "command.runAllowed",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["commandId"] = "dotnet.version",
                        ["timeoutSeconds"] = 30,
                        ["maxOutputBytes"] = 64
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888883"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "git.status",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId
                    }),
                QueueRequest(
                    Guid.Parse("88888888-8888-8888-8888-888888888884"),
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    "rollback.restore",
                    new Dictionary<string, object?>
                    {
                        ["sourceRequestId"] = sourceRequestId,
                        ["releaseAttemptId"] = releaseAttemptId,
                        ["manifestId"] = manifestId
                    })
            ]);
            var responses = new List<ToolResponse>();
            listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var serverUrl = "http://127.0.0.1:" + port;
            serverCancellation = new CancellationTokenSource();
            serverTask = RunApprovedFlowQueueServer(listener, queued, responses, serverCancellation.Token);

            var config = new AgentConfig
            {
                ServerUrl = serverUrl,
                Token = "server-queue-flow-token",
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var agent = new LearnBotLocalAgent();
            for (var i = 0; i < 9; i++)
            {
                agent.PollOnce(config).GetAwaiter().GetResult();
            }

            var afterRollback = File.ReadAllText(targetPath, Encoding.UTF8);
            var afterRollbackHash = Sha256Hex(File.ReadAllBytes(targetPath));
            var treeResponse = responses.Single(item => item.ToolName == "workspace.tree");
            var searchResponse = responses.Single(item => item.ToolName == "workspace.search");
            var readResponses = responses
                .Where(item => item.ToolName == "file.read")
                .OrderBy(item => item.RequestId)
                .ToList();
            var preStatusResponse = responses
                .Where(item => item.ToolName == "git.status")
                .OrderBy(item => item.RequestId)
                .First();
            var patchResponse = responses.Single(item => item.ToolName == "patch.apply");
            var commandResponse = responses.Single(item => item.ToolName == "command.runAllowed");
            var statusResponse = responses
                .Where(item => item.ToolName == "git.status")
                .OrderBy(item => item.RequestId)
                .Last();
            var rollbackResponse = responses.Single(item => item.ToolName == "rollback.restore");
            var heartbeatJson = JsonSerializer.Serialize(HeartbeatPayload(config), JsonOptions);
            var serializedTreeEntries = treeResponse.Output.TryGetValue("entries", out var treeEntries)
                ? JsonSerializer.Serialize(treeEntries, JsonOptions)
                : "";
            var serializedSearchMatches = searchResponse.Output.TryGetValue("matches", out var searchMatches)
                ? JsonSerializer.Serialize(searchMatches, JsonOptions)
                : "";
            var serializedReadResponses = JsonSerializer.Serialize(readResponses.Select(item => item.Output), JsonOptions);
            var ok = responses.Count == 9
                && treeResponse.Status == "SUCCEEDED"
                && serializedTreeEntries.Contains("src/App.cs", StringComparison.Ordinal)
                && serializedTreeEntries.Contains("src/Feature.cs", StringComparison.Ordinal)
                && searchResponse.Status == "SUCCEEDED"
                && serializedSearchMatches.Contains("src/App.cs", StringComparison.Ordinal)
                && serializedSearchMatches.Contains("src/Feature.cs", StringComparison.Ordinal)
                && readResponses.Count == 2
                && readResponses.All(item => item.Status == "SUCCEEDED")
                && serializedReadResponses.Contains("src/App.cs", StringComparison.Ordinal)
                && serializedReadResponses.Contains("src/Feature.cs", StringComparison.Ordinal)
                && serializedReadResponses.Contains("old", StringComparison.Ordinal)
                && serializedReadResponses.Contains("helper", StringComparison.Ordinal)
                && preStatusResponse.Status == "SUCCEEDED"
                && patchResponse.Status == "SUCCEEDED"
                && patchResponse.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is true
                && commandResponse.Status == "SUCCEEDED"
                && statusResponse.Status == "SUCCEEDED"
                && statusResponse.Output.TryGetValue("clean", out var clean)
                && clean is false
                && rollbackResponse.Status == "SUCCEEDED"
                && rollbackResponse.Output.TryGetValue("restored", out var restored)
                && restored is true
                && afterRollback == original;
            if (!ok)
            {
                Console.Error.WriteLine(
                    patchResponse.Error
                    ?? commandResponse.Error
                    ?? statusResponse.Error
                    ?? rollbackResponse.Error
                    ?? "approved server queue flow contract self-test failed");
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
                    Sha256Hex(Encoding.UTF8.GetBytes(patched)),
                    afterRollbackHash,
                    treeResponse,
                    searchResponse,
                    readResponses,
                    preStatusResponse,
                    patchResponse,
                    commandResponse,
                    statusResponse,
                    rollbackResponse,
                    true,
                    heartbeatJson);
            }

            Console.WriteLine("approved-server-queue-flow-contract-ok");
            return 0;
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine("approved server queue flow TCP listener failed: " + ex.Message);
            return 1;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            if (serverCancellation is not null)
            {
                serverCancellation.Cancel();
            }
            if (listener is not null)
            {
                try
                {
                    listener.Stop();
                }
                catch (SocketException)
                {
                }
            }
            if (serverTask is not null)
            {
                try
                {
                    serverTask.Wait(TimeSpan.FromSeconds(2));
                }
                catch (AggregateException)
                {
                }
            }
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

    private static QueuedToolRequest QueueRequest(
        Guid requestId,
        Guid sessionId,
        Guid userId,
        Guid agentId,
        Guid workspaceId,
        string toolName,
        Dictionary<string, object?> input) => new(
            requestId,
            new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = toolName,
                ["approvalState"] = "APPROVED",
                ["input"] = input
            });

    private static async Task RunApprovedFlowQueueServer(
        TcpListener listener,
        Queue<QueuedToolRequest> queued,
        List<ToolResponse> responses,
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                break;
            }
            catch (OperationCanceledException)
            {
                break;
            }

            _ = Task.Run(async () =>
            {
                await using var stream = client.GetStream();
                using (client)
                {
                    var request = await ReadHttpRequest(stream, cancellationToken);
                    if (request is null)
                    {
                        return;
                    }

                    if (request.Method == "GET" && request.Path == "/api/local-agents/tools/next")
                    {
                        if (queued.Count == 0)
                        {
                            await WriteHttp(stream, 204, "");
                            return;
                        }
                        var next = queued.Dequeue();
                        await WriteHttpJson(stream, new Dictionary<string, object?>
                        {
                            ["requestId"] = next.RequestId,
                            ["request"] = next.Request
                        });
                        return;
                    }

                    if (request.Method == "POST"
                        && request.Path.StartsWith("/api/local-agents/tools/", StringComparison.Ordinal)
                        && request.Path.EndsWith("/response", StringComparison.Ordinal))
                    {
                        var response = JsonSerializer.Deserialize<ToolResponse>(request.Body, JsonOptions);
                        if (response is not null)
                        {
                            responses.Add(response with { Output = NormalizeOutput(response.Output) });
                        }
                        await WriteHttpJson(stream, new { ok = true });
                        return;
                    }

                    await WriteHttp(stream, 404, "{\"error\":\"not found\"}");
                }
            }, cancellationToken);
        }
    }

    private static async Task<SimpleHttpRequest?> ReadHttpRequest(NetworkStream stream, CancellationToken cancellationToken)
    {
        var buffer = new byte[64 * 1024];
        var total = 0;
        while (true)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(total, buffer.Length - total), cancellationToken);
            if (read == 0)
            {
                return null;
            }
            total += read;
            var headerEnd = FindHeaderEnd(buffer, total);
            if (headerEnd < 0)
            {
                if (total == buffer.Length)
                {
                    return null;
                }
                continue;
            }

            var headerText = Encoding.ASCII.GetString(buffer, 0, headerEnd);
            var lines = headerText.Split("\r\n", StringSplitOptions.None);
            var requestLine = lines[0].Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
            if (requestLine.Length < 2)
            {
                return null;
            }
            var contentLength = 0;
            foreach (var line in lines.Skip(1))
            {
                var separator = line.IndexOf(':');
                if (separator <= 0)
                {
                    continue;
                }
                if (string.Equals(line[..separator], "Content-Length", StringComparison.OrdinalIgnoreCase))
                {
                    _ = int.TryParse(line[(separator + 1)..].Trim(), out contentLength);
                }
            }
            var bodyStart = headerEnd + 4;
            while (total - bodyStart < contentLength)
            {
                var bodyRead = await stream.ReadAsync(buffer.AsMemory(total, buffer.Length - total), cancellationToken);
                if (bodyRead == 0)
                {
                    return null;
                }
                total += bodyRead;
            }
            var body = Encoding.UTF8.GetString(buffer, bodyStart, contentLength);
            return new SimpleHttpRequest(requestLine[0], requestLine[1], body);
        }
    }

    private static int FindHeaderEnd(byte[] buffer, int length)
    {
        for (var i = 3; i < length; i++)
        {
            if (buffer[i - 3] == '\r'
                && buffer[i - 2] == '\n'
                && buffer[i - 1] == '\r'
                && buffer[i] == '\n')
            {
                return i - 3;
            }
        }
        return -1;
    }

    private static Task WriteHttpJson(NetworkStream stream, object value) =>
        WriteHttp(stream, 200, JsonSerializer.Serialize(value, JsonOptions), "application/json");

    private static async Task WriteHttp(NetworkStream stream, int statusCode, string body, string contentType = "application/json")
    {
        var statusText = statusCode switch
        {
            200 => "OK",
            204 => "No Content",
            404 => "Not Found",
            _ => "OK"
        };
        var bytes = Encoding.UTF8.GetBytes(body);
        var header = Encoding.ASCII.GetBytes(
            "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + bytes.Length + "\r\n"
            + "Connection: close\r\n\r\n");
        await stream.WriteAsync(header);
        if (bytes.Length > 0)
        {
            await stream.WriteAsync(bytes);
        }
    }

    private static Dictionary<string, object?> NormalizeOutput(Dictionary<string, object?> output) =>
        output.ToDictionary(item => item.Key, item => NormalizeJsonValue(item.Value), StringComparer.Ordinal);

    private static object? NormalizeJsonValue(object? value)
    {
        if (value is not JsonElement element)
        {
            return value;
        }
        return element.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number when element.TryGetInt64(out var longValue) => longValue,
            JsonValueKind.Number when element.TryGetDouble(out var doubleValue) => doubleValue,
            JsonValueKind.String => element.GetString(),
            JsonValueKind.Array => element.EnumerateArray().Select(item => NormalizeJsonValue(item)).ToList(),
            JsonValueKind.Object => element.EnumerateObject()
                .ToDictionary(item => item.Name, item => NormalizeJsonValue(item.Value), StringComparer.Ordinal),
            JsonValueKind.Null => null,
            _ => element.ToString()
        };
    }
}

internal sealed record QueuedToolRequest(Guid RequestId, Dictionary<string, object?> Request);

internal sealed record SimpleHttpRequest(string Method, string Path, string Body);
