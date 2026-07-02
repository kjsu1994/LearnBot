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
            var featurePath = Path.Combine(workspaceRoot, "src", "Feature.cs");
            var original = "class App {\n    string Name = \"old\";\n}\n";
            var feature = "class Feature {\n    string Name = \"helper\";\n}\n";
            var patched = "class App {\n    string Name = \"new\";\n    string Mode = \"safe\";\n}\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(featurePath, feature, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

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

            using var treeRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "workspace.tree",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["path"] = ".",
                    ["maxEntries"] = 50,
                    ["maxDepth"] = 4
                });
            var treeResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888870"), treeRequest.RootElement, "workspace.tree");

            using var searchRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "workspace.search",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["path"] = ".",
                    ["query"] = "Name",
                    ["maxMatches"] = 10,
                    ["maxFiles"] = 50
                });
            var searchResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888869"), searchRequest.RootElement, "workspace.search");

            using var readRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "file.read",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["path"] = "src/App.cs",
                    ["maxBytes"] = 4096
                });
            var readResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888871"), readRequest.RootElement, "file.read");

            using var featureReadRequest = FlowToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                "file.read",
                "APPROVED",
                new Dictionary<string, object?>
                {
                    ["sourceRequestId"] = sourceRequestId,
                    ["releaseAttemptId"] = releaseAttemptId,
                    ["path"] = "src/Feature.cs",
                    ["maxBytes"] = 4096
                });
            var featureReadResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888873"), featureReadRequest.RootElement, "file.read");
            var readResponses = new[] { readResponse, featureReadResponse };

            using var preStatusRequest = FlowToolRequest(
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
            var preStatusResponse = agent.HandleTool(config, Guid.Parse("88888888-8888-8888-8888-888888888872"), preStatusRequest.RootElement, "git.status");

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
                && treeResponse.SessionId == sessionId
                && searchResponse.SessionId == sessionId
                && readResponse.SessionId == sessionId
                && featureReadResponse.SessionId == sessionId
                && preStatusResponse.SessionId == sessionId
                && commandResponse.SessionId == sessionId
                && statusResponse.SessionId == sessionId
                && rollbackResponse.SessionId == sessionId;
            var closedLoopReport = BuildCodexClosedLoopReport(
                workspaceId,
                sourceRequestId,
                releaseAttemptId,
                "src/App.cs",
                originalHash,
                afterPatchHash,
                afterRollbackHash,
                treeResponse,
                searchResponse,
                readResponses,
                preStatusResponse,
                patchResponse,
                commandResponse,
                statusResponse,
                rollbackResponse,
                identitiesPreserved);
            var closedLoopReportJson = JsonSerializer.Serialize(closedLoopReport, JsonOptions);
            var ok = patchResponse.Status == "SUCCEEDED"
                && heartbeatJson.Contains("\"patch.apply\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"command.runAllowed\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"rollback.restore\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"workspace.tree\"", StringComparison.Ordinal)
                && heartbeatJson.Contains("\"workspace.search\"", StringComparison.Ordinal)
                && treeResponse.Status == "SUCCEEDED"
                && treeResponse.Output.TryGetValue("entries", out var treeEntries)
                && JsonSerializer.Serialize(treeEntries, JsonOptions).Contains("src/App.cs", StringComparison.Ordinal)
                && JsonSerializer.Serialize(treeEntries, JsonOptions).Contains("src/Feature.cs", StringComparison.Ordinal)
                && searchResponse.Status == "SUCCEEDED"
                && searchResponse.Output.TryGetValue("matches", out var searchMatches)
                && JsonSerializer.Serialize(searchMatches, JsonOptions).Contains("src/App.cs", StringComparison.Ordinal)
                && JsonSerializer.Serialize(searchMatches, JsonOptions).Contains("src/Feature.cs", StringComparison.Ordinal)
                && readResponse.Status == "SUCCEEDED"
                && readResponse.Output.TryGetValue("content", out var readContent)
                && readContent?.ToString()?.Contains("string Name = \"old\"", StringComparison.Ordinal) == true
                && featureReadResponse.Status == "SUCCEEDED"
                && featureReadResponse.Output.TryGetValue("content", out var featureReadContent)
                && featureReadContent?.ToString()?.Contains("string Name = \"helper\"", StringComparison.Ordinal) == true
                && preStatusResponse.Status == "SUCCEEDED"
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
                && identitiesPreserved
                && closedLoopReportJson.Contains("\"readOnlyToolLoop\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"candidateSelection\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"learnbot.local-agent.read-only-candidate-selection.v1\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"multiFileRead\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"learnbot.local-agent.multi-file-read-report.v1\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"selectedFileCount\": 2", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"readFileCount\": 2", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"patchTestLoop\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"patchTestRetryDecision\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"learnbot.local-agent.patch-test-retry-decision.v1\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"finalReport\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"ragFreshnessMarker\"", StringComparison.Ordinal)
                && closedLoopReportJson.Contains("\"partialReindexRequired\": true", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine(
                    readResponse.Error
                    ?? featureReadResponse.Error
                    ?? preStatusResponse.Error
                    ??
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
                    treeResponse,
                    searchResponse,
                    readResponses,
                    preStatusResponse,
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
        ToolResponse treeResponse,
        ToolResponse searchResponse,
        IReadOnlyList<ToolResponse> readResponses,
        ToolResponse preStatusResponse,
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
                ["rollbackRestore"] = heartbeatJson.Contains("\"rollback.restore\"", StringComparison.Ordinal),
                ["workspaceTree"] = heartbeatJson.Contains("\"workspace.tree\"", StringComparison.Ordinal),
                ["workspaceSearch"] = heartbeatJson.Contains("\"workspace.search\"", StringComparison.Ordinal)
            },
            ["steps"] = BuildApprovedFlowStepEvidence(treeResponse, searchResponse, readResponses, preStatusResponse, patchResponse, commandResponse, statusResponse, rollbackResponse),
            ["closedLoop"] = BuildCodexClosedLoopReport(
                workspaceId,
                sourceRequestId,
                releaseAttemptId,
                targetFile,
                originalHash,
                afterPatchHash,
                afterRollbackHash,
                treeResponse,
                searchResponse,
                readResponses,
                preStatusResponse,
                patchResponse,
                commandResponse,
                statusResponse,
                rollbackResponse,
                identitiesPreserved),
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

    private static List<Dictionary<string, object?>> BuildApprovedFlowStepEvidence(
        ToolResponse treeResponse,
        ToolResponse searchResponse,
        IReadOnlyList<ToolResponse> readResponses,
        ToolResponse preStatusResponse,
        ToolResponse patchResponse,
        ToolResponse commandResponse,
        ToolResponse statusResponse,
        ToolResponse rollbackResponse)
    {
        var steps = new List<Dictionary<string, object?>>
        {
            StepEvidence("workspace.tree", treeResponse, new Dictionary<string, object?>
            {
                ["readOnly"] = true,
                ["phase"] = "projectExploration"
            }),
            StepEvidence("workspace.search", searchResponse, new Dictionary<string, object?>
            {
                ["readOnly"] = true,
                ["phase"] = "candidateSearch"
            })
        };
        steps.AddRange(readResponses.Select(response =>
            StepEvidence("file.read", response, new Dictionary<string, object?>
            {
                ["readOnly"] = true
            })));
        steps.AddRange([
            StepEvidence("git.status", preStatusResponse, new Dictionary<string, object?>
            {
                ["readOnly"] = true,
                ["phase"] = "prePatch"
            }),
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
        ]);
        return steps;
    }

    private static Dictionary<string, object?> BuildCodexClosedLoopReport(
        Guid workspaceId,
        Guid sourceRequestId,
        Guid releaseAttemptId,
        string targetFile,
        string originalHash,
        string afterPatchHash,
        string afterRollbackHash,
        ToolResponse treeResponse,
        ToolResponse searchResponse,
        IReadOnlyList<ToolResponse> readResponses,
        ToolResponse preStatusResponse,
        ToolResponse patchResponse,
        ToolResponse commandResponse,
        ToolResponse statusResponse,
        ToolResponse rollbackResponse,
        bool identitiesPreserved)
    {
        var changedByPatch = !string.Equals(originalHash, afterPatchHash, StringComparison.OrdinalIgnoreCase);
        var candidateSelection = BuildReadOnlyCandidateSelectionReport(targetFile, treeResponse, searchResponse);
        var multiFileRead = BuildMultiFileReadReport(candidateSelection, readResponses);
        var patchTestRetryDecision = BuildPatchTestRetryDecisionReport(commandResponse, statusResponse);
        var revisedPatchProposalPlan = BuildRevisedPatchProposalPlanReport(
            patchTestRetryDecision,
            commandResponse,
            [targetFile]);
        var localModelRevisedPatchRequest = BuildLocalModelRevisedPatchRequestReport(
            revisedPatchProposalPlan,
            multiFileRead);
        var localModelRevisedPatchOutput = BuildLocalModelRevisedPatchOutputValidationReport(
            localModelRevisedPatchRequest,
            null);
        var validatedRevisedPatchDryRunHandoff = BuildValidatedRevisedPatchDryRunHandoffReport(
            localModelRevisedPatchOutput);
        var patchTestSecondAttempt = BuildPatchTestSecondAttemptReport(
            patchTestRetryDecision,
            null,
            ExtractRevisedPatchProposalEntries(revisedPatchProposalPlan),
            validatedRevisedPatchDryRunHandoff);
        var revisedPatchApprovalRequest = BuildRevisedPatchApprovalRequestReport(
            patchTestSecondAttempt,
            null,
            targetFile,
            null,
            changedByPatch);
        var readOnlySteps = new List<Dictionary<string, object?>>
        {
            StepEvidence("workspace.tree", treeResponse, new Dictionary<string, object?>
            {
                ["readOnly"] = true,
                ["projectExploration"] = true
            }),
            StepEvidence("workspace.search", searchResponse, new Dictionary<string, object?>
            {
                ["readOnly"] = true,
                ["candidateSearch"] = true
            })
        };
        readOnlySteps.AddRange(readResponses.Select(response =>
            StepEvidence("file.read", response, new Dictionary<string, object?> { ["readOnly"] = true })));
        readOnlySteps.Add(StepEvidence("git.status", preStatusResponse, new Dictionary<string, object?> { ["readOnly"] = true }));
        var allReadResponsesSucceeded = readResponses.Count > 0
            && readResponses.All(response => response.Status == "SUCCEEDED");
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.codex-closed-loop-report.v1",
            ["workspaceId"] = workspaceId,
            ["sourceRequestId"] = sourceRequestId,
            ["releaseAttemptId"] = releaseAttemptId,
            ["targetFile"] = targetFile,
            ["readOnlyToolLoop"] = new Dictionary<string, object?>
            {
                ["status"] = treeResponse.Status == "SUCCEEDED" && searchResponse.Status == "SUCCEEDED" && allReadResponsesSucceeded && preStatusResponse.Status == "SUCCEEDED" ? "SUCCEEDED" : "FAILED",
                ["steps"] = readOnlySteps
            },
            ["candidateSelection"] = candidateSelection,
            ["multiFileRead"] = multiFileRead,
            ["patchTestLoop"] = new Dictionary<string, object?>
            {
                ["status"] = patchResponse.Status == "SUCCEEDED" && commandResponse.Status == "SUCCEEDED" && statusResponse.Status == "SUCCEEDED"
                    ? "SUCCEEDED"
                    : "FAILED",
                ["steps"] = new[]
                {
                    StepEvidence("patch.apply", patchResponse, new Dictionary<string, object?>
                    {
                        ["mutationApplied"] = patchResponse.Output.TryGetValue("mutationApplied", out var mutationApplied) ? mutationApplied : null
                    }),
                    StepEvidence("command.runAllowed", commandResponse, new Dictionary<string, object?>
                    {
                        ["arbitraryShellAllowed"] = commandResponse.Output.TryGetValue("arbitraryShellAllowed", out var arbitraryShellAllowed) ? arbitraryShellAllowed : null
                    }),
                    StepEvidence("git.status", statusResponse, new Dictionary<string, object?>
                    {
                        ["phase"] = "postPatch",
                        ["clean"] = statusResponse.Output.TryGetValue("clean", out var clean) ? clean : null
                    })
                }
            },
            ["patchTestRetryDecision"] = patchTestRetryDecision,
            ["revisedPatchProposalPlan"] = revisedPatchProposalPlan,
            ["localModelRevisedPatchRequest"] = localModelRevisedPatchRequest,
            ["localModelRevisedPatchOutput"] = localModelRevisedPatchOutput,
            ["validatedRevisedPatchDryRunHandoff"] = validatedRevisedPatchDryRunHandoff,
            ["patchTestSecondAttempt"] = patchTestSecondAttempt,
            ["revisedPatchApprovalRequest"] = revisedPatchApprovalRequest,
            ["rollbackLoop"] = new Dictionary<string, object?>
            {
                ["status"] = rollbackResponse.Status,
                ["restored"] = rollbackResponse.Output.TryGetValue("restored", out var restored) ? restored : null
            },
            ["ragFreshnessMarker"] = new Dictionary<string, object?>
            {
                ["status"] = changedByPatch ? "STALE_UNTIL_PARTIAL_REINDEX" : "UNCHANGED",
                ["partialReindexRequired"] = changedByPatch,
                ["partialReindexEnabled"] = false,
                ["targetFiles"] = new[] { targetFile },
                ["finalReportMustDiscloseStaleIndex"] = changedByPatch
            },
            ["finalReport"] = new Dictionary<string, object?>
            {
                ["status"] = "READY_AUDIT_ONLY",
                ["sections"] = new[] { "changedFiles", "verification", "repositoryStatus", "rollback", "ragFreshness", "residualRisk" },
                ["changedFiles"] = new[]
                {
                    new Dictionary<string, object?>
                    {
                        ["path"] = targetFile,
                        ["beforeSha256"] = originalHash,
                        ["afterPatchSha256"] = afterPatchHash,
                        ["afterRollbackSha256"] = afterRollbackHash,
                        ["changedByPatch"] = changedByPatch
                    }
                },
                ["identitiesPreserved"] = identitiesPreserved,
                ["finalAnswerGenerated"] = false
            }
        };
    }

    private static int SelfTestPatchTestRetryDecisionContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var success = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888882"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "command.runAllowed",
            "SUCCEEDED",
            new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 0,
                ["timedOut"] = false,
                ["stdout"] = "Passed!",
                ["stderr"] = "",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            },
            null,
            null,
            now,
            now,
            []);
        var failed = success with
        {
            Status = "FAILED",
            FailureCode = "TEST_FAILED",
            Error = "Allowlisted command exited with a non-zero status.",
            Output = new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 1,
                ["timedOut"] = false,
                ["stdout"] = "Test Failed: AppTests.Name_should_change",
                ["stderr"] = "Expected new but found old",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            }
        };
        var rejected = success with
        {
            Status = "REJECTED",
            FailureCode = "UNSAFE_TOOL",
            Error = "command.runAllowed accepts only typed allowlisted command ids.",
            Output = new Dictionary<string, object?>
            {
                ["commandId"] = "powershell",
                ["arbitraryShellAllowed"] = false
            }
        };
        var statusResponse = success with
        {
            ToolName = "git.status",
            Output = new Dictionary<string, object?> { ["clean"] = false }
        };

        var successReport = BuildPatchTestRetryDecisionReport(success, statusResponse);
        var failedReport = BuildPatchTestRetryDecisionReport(failed, statusResponse);
        var rejectedReport = BuildPatchTestRetryDecisionReport(rejected, statusResponse);

        var ok = successReport.TryGetValue("status", out var successStatus)
            && string.Equals(successStatus?.ToString(), "NO_RETRY_NEEDED", StringComparison.Ordinal)
            && successReport.TryGetValue("retryRecommended", out var successRetry)
            && successRetry is false
            && failedReport.TryGetValue("status", out var failedStatus)
            && string.Equals(failedStatus?.ToString(), "RETRY_RECOMMENDED", StringComparison.Ordinal)
            && failedReport.TryGetValue("retryRecommended", out var failedRetry)
            && failedRetry is true
            && failedReport.TryGetValue("nextStep", out var nextStep)
            && string.Equals(nextStep?.ToString(), "replan_patch_from_failure_logs", StringComparison.Ordinal)
            && failedReport.TryGetValue("failureSummary", out var failureSummaryRaw)
            && failureSummaryRaw is Dictionary<string, object?> failureSummary
            && failureSummary.TryGetValue("stderrSnippet", out var stderrSnippet)
            && stderrSnippet?.ToString()?.Contains("Expected new", StringComparison.Ordinal) == true
            && rejectedReport.TryGetValue("status", out var rejectedStatus)
            && string.Equals(rejectedStatus?.ToString(), "BLOCKED", StringComparison.Ordinal)
            && rejectedReport.TryGetValue("retryRecommended", out var rejectedRetry)
            && rejectedRetry is false;
        if (!ok)
        {
            Console.Error.WriteLine("patch-test retry decision contract self-test failed");
            return 1;
        }

        Console.WriteLine("patch-test-retry-decision-contract-ok");
        return 0;
    }

    private static int SelfTestRevisedPatchProposalPlanContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var failedCommand = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888882"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "command.runAllowed",
            "FAILED",
            new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 1,
                ["timedOut"] = false,
                ["stdout"] = "Test Failed: AppTests.Name_should_change",
                ["stderr"] = "Expected new but found old",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            },
            "TEST_FAILED",
            "Allowlisted command exited with a non-zero status.",
            now,
            now,
            []);
        var statusResponse = failedCommand with
        {
            ToolName = "git.status",
            Status = "SUCCEEDED",
            FailureCode = null,
            Error = null,
            Output = new Dictionary<string, object?> { ["clean"] = false }
        };
        var retryDecision = BuildPatchTestRetryDecisionReport(failedCommand, statusResponse);
        var readyPlan = BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand, ["src/App.cs", "src/Feature.cs"]);
        var missingEvidencePlan = BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand with
        {
            Error = null,
            Output = new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 1,
                ["stdout"] = "",
                ["stderr"] = "",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            }
        }, ["src/App.cs"]);
        var noTargetPlan = BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand, []);
        var noRetry = BuildPatchTestRetryDecisionReport(failedCommand with
        {
            Status = "SUCCEEDED",
            FailureCode = null,
            Error = null,
            Output = new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 0,
                ["stdout"] = "Passed!",
                ["stderr"] = "",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            }
        }, statusResponse);
        var notRequiredPlan = BuildRevisedPatchProposalPlanReport(noRetry, failedCommand, ["src/App.cs"]);
        var proposalEntries = ExtractRevisedPatchProposalEntries(readyPlan);

        var ok = readyPlan.TryGetValue("schema", out var schema)
            && string.Equals(schema?.ToString(), "learnbot.local-agent.revised-patch-proposal-plan.v1", StringComparison.Ordinal)
            && readyPlan.TryGetValue("status", out var readyStatus)
            && string.Equals(readyStatus?.ToString(), "READY_MODEL_DISABLED", StringComparison.Ordinal)
            && readyPlan.TryGetValue("targetFilesKnown", out var targetFilesKnown)
            && targetFilesKnown is true
            && readyPlan.TryGetValue("failureEvidenceAvailable", out var evidenceAvailable)
            && evidenceAvailable is true
            && readyPlan.TryGetValue("mutationAllowed", out var mutationAllowed)
            && mutationAllowed is false
            && readyPlan.TryGetValue("dryRunRequiredBeforeApproval", out var dryRunRequired)
            && dryRunRequired is true
            && readyPlan.TryGetValue("approvalRequiredBeforeMutation", out var approvalRequired)
            && approvalRequired is true
            && readyPlan.TryGetValue("proposalGenerationEnabled", out var proposalGenerationEnabled)
            && proposalGenerationEnabled is false
            && proposalEntries.Count == 2
            && string.Equals(proposalEntries[0]["source"]?.ToString(), "failed command stdout/stderr", StringComparison.Ordinal)
            && missingEvidencePlan.TryGetValue("status", out var missingEvidenceStatus)
            && string.Equals(missingEvidenceStatus?.ToString(), "BLOCKED_MISSING_FAILURE_EVIDENCE", StringComparison.Ordinal)
            && noTargetPlan.TryGetValue("status", out var noTargetStatus)
            && string.Equals(noTargetStatus?.ToString(), "BLOCKED_MISSING_TARGET_FILES", StringComparison.Ordinal)
            && notRequiredPlan.TryGetValue("status", out var notRequiredStatus)
            && string.Equals(notRequiredStatus?.ToString(), "NOT_REQUIRED", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("revised patch proposal plan contract self-test failed");
            return 1;
        }

        Console.WriteLine("revised-patch-proposal-plan-contract-ok");
        return 0;
    }

    private static int SelfTestLocalModelRevisedPatchRequestContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var failedCommand = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888882"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "command.runAllowed",
            "FAILED",
            new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 1,
                ["timedOut"] = false,
                ["stdout"] = "Test Failed: AppTests.Name_should_change",
                ["stderr"] = "Expected new but found old",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            },
            "TEST_FAILED",
            "Allowlisted command exited with a non-zero status.",
            now,
            now,
            []);
        var statusResponse = failedCommand with
        {
            ToolName = "git.status",
            Status = "SUCCEEDED",
            FailureCode = null,
            Error = null,
            Output = new Dictionary<string, object?> { ["clean"] = false }
        };
        var retryDecision = BuildPatchTestRetryDecisionReport(failedCommand, statusResponse);
        var proposalPlan = BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand, ["src/App.cs"]);
        var multiFileRead = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.multi-file-read-report.v1",
            ["status"] = "SUCCEEDED",
            ["allSelectedFilesRead"] = true,
            ["readFiles"] = new List<Dictionary<string, object?>>
            {
                new()
                {
                    ["path"] = "src/App.cs",
                    ["status"] = "SUCCEEDED",
                    ["bytes"] = 42,
                    ["returnedBytes"] = 42,
                    ["truncated"] = false
                }
            }
        };
        var readyRequest = BuildLocalModelRevisedPatchRequestReport(proposalPlan, multiFileRead);
        var missingReadRequest = BuildLocalModelRevisedPatchRequestReport(proposalPlan, new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.multi-file-read-report.v1",
            ["status"] = "NO_READS",
            ["allSelectedFilesRead"] = false,
            ["readFiles"] = new List<Dictionary<string, object?>>()
        });
        var notReadyRequest = BuildLocalModelRevisedPatchRequestReport(
            BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand, []),
            multiFileRead);

        var ok = readyRequest.TryGetValue("schema", out var schema)
            && string.Equals(schema?.ToString(), "learnbot.local-agent.local-model-revised-patch-request.v1", StringComparison.Ordinal)
            && readyRequest.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "READY_MODEL_CALL_DISABLED", StringComparison.Ordinal)
            && readyRequest.TryGetValue("modelCallEnabled", out var modelCallEnabled)
            && modelCallEnabled is false
            && readyRequest.TryGetValue("mutationAllowed", out var mutationAllowed)
            && mutationAllowed is false
            && readyRequest.TryGetValue("approvalRequiredBeforeMutation", out var approvalRequired)
            && approvalRequired is true
            && readyRequest.TryGetValue("maxTargetFiles", out var maxTargetFiles)
            && maxTargetFiles is 8
            && readyRequest.TryGetValue("maxDiffChars", out var maxDiffChars)
            && maxDiffChars is 24000
            && readyRequest.TryGetValue("targetFiles", out var targetFilesRaw)
            && targetFilesRaw is List<string> targetFiles
            && targetFiles.SequenceEqual(["src/App.cs"])
            && readyRequest.TryGetValue("modelInput", out var modelInputRaw)
            && modelInputRaw is Dictionary<string, object?> modelInput
            && modelInput.TryGetValue("failureEvidence", out var failureEvidence)
            && failureEvidence is not null
            && missingReadRequest.TryGetValue("status", out var missingReadStatus)
            && string.Equals(missingReadStatus?.ToString(), "BLOCKED_MISSING_READ_EVIDENCE", StringComparison.Ordinal)
            && notReadyRequest.TryGetValue("status", out var notReadyStatus)
            && string.Equals(notReadyStatus?.ToString(), "NOT_READY", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("local model revised patch request contract self-test failed");
            return 1;
        }

        Console.WriteLine("local-model-revised-patch-request-contract-ok");
        return 0;
    }

    private static int SelfTestLocalModelRevisedPatchOutputContract()
    {
        var request = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-request.v1",
            ["status"] = "READY_MODEL_CALL_DISABLED",
            ["targetFiles"] = new List<string> { "src/App.cs" },
            ["maxDiffChars"] = 24000,
            ["modelCallEnabled"] = false,
            ["mutationAllowed"] = false,
            ["approvalRequiredBeforeMutation"] = true
        };
        var validOutput = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-output.v1",
            ["targetFiles"] = new List<string> { "src/App.cs" },
            ["unifiedDiff"] = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,3 @@
             class App {
            -    string Name = "new";
            +    string Name = "fixed";
             }
            """,
            ["rationale"] = "Fix the assertion mismatch from the failed test.",
            ["riskNotes"] = "Small single-file change."
        };
        var outOfTarget = new Dictionary<string, object?>(validOutput, StringComparer.Ordinal)
        {
            ["targetFiles"] = new List<string> { "src/Other.cs" },
            ["unifiedDiff"] = """
            --- a/src/Other.cs
            +++ b/src/Other.cs
            @@ -1 +1 @@
            -old
            +new
            """
        };
        var emptyDiff = new Dictionary<string, object?>(validOutput, StringComparer.Ordinal)
        {
            ["unifiedDiff"] = ""
        };
        var oversized = new Dictionary<string, object?>(validOutput, StringComparer.Ordinal)
        {
            ["unifiedDiff"] = new string('x', 24001)
        };

        var ready = BuildLocalModelRevisedPatchOutputValidationReport(request, validOutput);
        var blockedOutOfTarget = BuildLocalModelRevisedPatchOutputValidationReport(request, outOfTarget);
        var blockedEmpty = BuildLocalModelRevisedPatchOutputValidationReport(request, emptyDiff);
        var blockedOversized = BuildLocalModelRevisedPatchOutputValidationReport(request, oversized);
        var waiting = BuildLocalModelRevisedPatchOutputValidationReport(request, null);

        var ok = ready.TryGetValue("schema", out var schema)
            && string.Equals(schema?.ToString(), "learnbot.local-agent.local-model-revised-patch-output-validation.v1", StringComparison.Ordinal)
            && ready.TryGetValue("status", out var readyStatus)
            && string.Equals(readyStatus?.ToString(), "READY_FOR_DRY_RUN", StringComparison.Ordinal)
            && ready.TryGetValue("mutationAllowed", out var mutationAllowed)
            && mutationAllowed is false
            && ready.TryGetValue("patchApplyMutationAllowed", out var patchMutationAllowed)
            && patchMutationAllowed is false
            && ready.TryGetValue("patchApplyDryRunOnly", out var dryRunOnly)
            && dryRunOnly is true
            && ready.TryGetValue("approvalRequiredBeforeMutation", out var approvalRequired)
            && approvalRequired is true
            && ready.TryGetValue("validatedTargetFiles", out var filesRaw)
            && filesRaw is List<string> files
            && files.SequenceEqual(["src/App.cs"])
            && blockedOutOfTarget.TryGetValue("status", out var outOfTargetStatus)
            && string.Equals(outOfTargetStatus?.ToString(), "BLOCKED_OUT_OF_TARGET", StringComparison.Ordinal)
            && blockedEmpty.TryGetValue("status", out var emptyStatus)
            && string.Equals(emptyStatus?.ToString(), "BLOCKED_EMPTY_DIFF", StringComparison.Ordinal)
            && blockedOversized.TryGetValue("status", out var oversizedStatus)
            && string.Equals(oversizedStatus?.ToString(), "BLOCKED_OVERSIZED_DIFF", StringComparison.Ordinal)
            && waiting.TryGetValue("status", out var waitingStatus)
            && string.Equals(waitingStatus?.ToString(), "WAITING_FOR_MODEL_OUTPUT", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("local model revised patch output contract self-test failed");
            return 1;
        }

        Console.WriteLine("local-model-revised-patch-output-contract-ok");
        return 0;
    }

    private static int SelfTestValidatedRevisedPatchDryRunHandoffContract()
    {
        var request = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-request.v1",
            ["status"] = "READY_MODEL_CALL_DISABLED",
            ["targetFiles"] = new List<string> { "src/App.cs" },
            ["maxDiffChars"] = 24000,
            ["modelCallEnabled"] = false,
            ["mutationAllowed"] = false,
            ["approvalRequiredBeforeMutation"] = true
        };
        var output = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-output.v1",
            ["targetFiles"] = new List<string> { "src/App.cs" },
            ["unifiedDiff"] = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,3 @@
             class App {
            -    string Name = "new";
            +    string Name = "fixed";
             }
            """,
            ["rationale"] = "Fix the assertion mismatch.",
            ["riskNotes"] = "Small single-file change."
        };
        var validatedOutput = BuildLocalModelRevisedPatchOutputValidationReport(request, output);
        var ready = BuildValidatedRevisedPatchDryRunHandoffReport(validatedOutput);
        var blocked = BuildValidatedRevisedPatchDryRunHandoffReport(
            BuildLocalModelRevisedPatchOutputValidationReport(request, null));

        var ok = ready.TryGetValue("schema", out var schema)
            && string.Equals(schema?.ToString(), "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1", StringComparison.Ordinal)
            && ready.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "READY_DRY_RUN_QUEUE_DISABLED", StringComparison.Ordinal)
            && ready.TryGetValue("dryRunQueueEnabled", out var dryRunQueueEnabled)
            && dryRunQueueEnabled is false
            && ready.TryGetValue("mutationAllowed", out var mutationAllowed)
            && mutationAllowed is false
            && ready.TryGetValue("approvalRequiredBeforeMutation", out var approvalRequired)
            && approvalRequired is true
            && ready.TryGetValue("patchApplyInput", out var inputRaw)
            && inputRaw is Dictionary<string, object?> input
            && input.TryGetValue("dryRunOnly", out var dryRunOnly)
            && dryRunOnly is true
            && input.TryGetValue("mutationAllowed", out var patchMutationAllowed)
            && patchMutationAllowed is false
            && input.TryGetValue("targetFiles", out var targetFilesRaw)
            && targetFilesRaw is IReadOnlyList<string> targetFiles
            && targetFiles.SequenceEqual(["src/App.cs"])
            && blocked.TryGetValue("status", out var blockedStatus)
            && string.Equals(blockedStatus?.ToString(), "BLOCKED_OUTPUT_NOT_READY", StringComparison.Ordinal)
            && blocked.TryGetValue("patchApplyInput", out var blockedInput)
            && blockedInput is null;
        if (!ok)
        {
            Console.Error.WriteLine("validated revised patch dry-run handoff contract self-test failed");
            return 1;
        }

        Console.WriteLine("validated-revised-patch-dry-run-handoff-contract-ok");
        return 0;
    }

    private static int SelfTestPatchTestSecondAttemptContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var failedCommand = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888882"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "command.runAllowed",
            "FAILED",
            new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 1,
                ["timedOut"] = false,
                ["stdout"] = "Test Failed: AppTests.Name_should_change",
                ["stderr"] = "Expected new but found old",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            },
            "TEST_FAILED",
            "Allowlisted command exited with a non-zero status.",
            now,
            now,
            []);
        var statusResponse = failedCommand with
        {
            ToolName = "git.status",
            Status = "SUCCEEDED",
            FailureCode = null,
            Error = null,
            Output = new Dictionary<string, object?> { ["clean"] = false }
        };
        var retryDecision = BuildPatchTestRetryDecisionReport(failedCommand, statusResponse);
        var dryRunPassed = failedCommand with
        {
            RequestId = Guid.Parse("88888888-8888-8888-8888-888888888885"),
            ToolName = "patch.apply",
            Status = "REJECTED",
            FailureCode = "UNSAFE_TOOL",
            Error = "Patch dry-run passed and a local snapshot was created, but file mutation is disabled until approval.",
            Output = new Dictionary<string, object?>
            {
                ["dryRun"] = true,
                ["preflightPassed"] = true,
                ["mutationApplied"] = false,
                ["snapshotCreated"] = true,
                ["snapshotObservation"] = new Dictionary<string, object?>
                {
                    ["manifestPreview"] = new Dictionary<string, object?>
                    {
                        ["id"] = "snap-0123456789abcdef",
                        ["schema"] = "learnbot.local-agent.snapshot-manifest.v1"
                    }
                },
                ["rollbackObservation"] = new Dictionary<string, object?>
                {
                    ["tool"] = "rollback.restore",
                    ["requiresUserApproval"] = true
                },
                ["files"] = new List<Dictionary<string, object?>>
                {
                    new() { ["path"] = "src/App.cs", ["contextMatches"] = true, ["hashMatches"] = true }
                }
            }
        };
        var dryRunFailed = dryRunPassed with
        {
            Status = "REJECTED",
            FailureCode = "CONTEXT_MISMATCH",
            Error = "Patch context did not match local file: src/App.cs",
            Output = new Dictionary<string, object?>
            {
                ["dryRun"] = true,
                ["preflightPassed"] = false,
                ["mutationApplied"] = false,
                ["snapshotCreated"] = false
            }
        };
        var noRetry = BuildPatchTestRetryDecisionReport(failedCommand with
        {
            Status = "SUCCEEDED",
            FailureCode = null,
            Error = null,
            Output = new Dictionary<string, object?>
            {
                ["commandId"] = "dotnet.test",
                ["exitCode"] = 0,
                ["timedOut"] = false,
                ["stdout"] = "Passed!",
                ["stderr"] = "",
                ["truncated"] = false,
                ["arbitraryShellAllowed"] = false
            }
        }, statusResponse);

        var proposalPlan = BuildRevisedPatchProposalPlanReport(retryDecision, failedCommand, ["src/App.cs"]);
        var proposal = ExtractRevisedPatchProposalEntries(proposalPlan);
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
            ["unifiedDiff"] = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,3 @@
             class App {
            -    string Name = "new";
            +    string Name = "fixed";
             }
            """
        };
        var validatedOutput = BuildLocalModelRevisedPatchOutputValidationReport(modelRequest, modelOutput);
        var dryRunHandoff = BuildValidatedRevisedPatchDryRunHandoffReport(validatedOutput);
        var readyForApproval = BuildPatchTestSecondAttemptReport(retryDecision, dryRunPassed, proposal, dryRunHandoff);
        var replanRequired = BuildPatchTestSecondAttemptReport(retryDecision, dryRunFailed, proposal);
        var notNeeded = BuildPatchTestSecondAttemptReport(noRetry, null, []);
        var approvalRequest = BuildRevisedPatchApprovalRequestReport(
            readyForApproval,
            dryRunPassed,
            "src/App.cs",
            "diff -- src/App.cs",
            staleIndexDisclosureRequired: true);
        var blockedApprovalRequest = BuildRevisedPatchApprovalRequestReport(
            replanRequired,
            dryRunFailed,
            "src/App.cs",
            "diff -- src/App.cs",
            staleIndexDisclosureRequired: true);

        var ok = readyForApproval.TryGetValue("status", out var readyStatus)
            && string.Equals(readyStatus?.ToString(), "APPROVAL_REQUIRED", StringComparison.Ordinal)
            && readyForApproval.TryGetValue("dryRunPassed", out var dryRunPassedValue)
            && dryRunPassedValue is true
            && readyForApproval.TryGetValue("actualMutationExecuted", out var mutationExecuted)
            && mutationExecuted is false
            && readyForApproval.TryGetValue("approvalStateRequired", out var approvalState)
            && string.Equals(approvalState?.ToString(), "APPROVED", StringComparison.Ordinal)
            && readyForApproval.TryGetValue("nextStep", out var nextStep)
            && string.Equals(nextStep?.ToString(), "request_user_approval_for_revised_patch", StringComparison.Ordinal)
            && readyForApproval.TryGetValue("dryRunInputSource", out var dryRunInputSource)
            && string.Equals(dryRunInputSource?.ToString(), "validatedRevisedPatchDryRunHandoff", StringComparison.Ordinal)
            && readyForApproval.TryGetValue("dryRunHandoff", out var handoffRaw)
            && handoffRaw is Dictionary<string, object?> handoff
            && handoff.TryGetValue("consumed", out var handoffConsumed)
            && handoffConsumed is true
            && handoff.TryGetValue("targetsMatchDryRun", out var targetsMatch)
            && targetsMatch is true
            && replanRequired.TryGetValue("status", out var replanStatus)
            && string.Equals(replanStatus?.ToString(), "REPLAN_REQUIRED", StringComparison.Ordinal)
            && notNeeded.TryGetValue("status", out var notNeededStatus)
            && string.Equals(notNeededStatus?.ToString(), "NOT_NEEDED", StringComparison.Ordinal)
            && approvalRequest.TryGetValue("status", out var approvalStatus)
            && string.Equals(approvalStatus?.ToString(), "READY", StringComparison.Ordinal)
            && approvalRequest.TryGetValue("approvalRequired", out var approvalRequired)
            && approvalRequired is true
            && approvalRequest.TryGetValue("nextMutationAllowedOnlyWhen", out var nextMutationCondition)
            && string.Equals(nextMutationCondition?.ToString(), "approvalState=APPROVED", StringComparison.Ordinal)
            && approvalRequest.TryGetValue("staleIndexDisclosureRequired", out var staleIndexDisclosure)
            && staleIndexDisclosure is true
            && blockedApprovalRequest.TryGetValue("status", out var blockedApprovalStatus)
            && string.Equals(blockedApprovalStatus?.ToString(), "NOT_READY", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("patch-test second-attempt contract self-test failed");
            return 1;
        }

        Console.WriteLine("patch-test-second-attempt-contract-ok");
        return 0;
    }

    private static int SelfTestRevisedPatchApprovalRequestContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var dryRunResponse = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888885"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "patch.apply",
            "REJECTED",
            new Dictionary<string, object?>
            {
                ["dryRun"] = true,
                ["preflightPassed"] = true,
                ["mutationApplied"] = false,
                ["snapshotCreated"] = true,
                ["snapshotObservation"] = new Dictionary<string, object?>
                {
                    ["manifestPreview"] = new Dictionary<string, object?>
                    {
                        ["id"] = "snap-0123456789abcdef",
                        ["schema"] = "learnbot.local-agent.snapshot-manifest.v1"
                    }
                },
                ["rollbackObservation"] = new Dictionary<string, object?>
                {
                    ["tool"] = "rollback.restore",
                    ["requiresUserApproval"] = true
                },
                ["files"] = new List<Dictionary<string, object?>>
                {
                    new() { ["path"] = "src/App.cs", ["contextMatches"] = true, ["hashMatches"] = true }
                }
            },
            "UNSAFE_TOOL",
            "Patch dry-run passed and a local snapshot was created, but file mutation is disabled until approval.",
            now,
            now,
            []);
        var secondAttempt = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.patch-test-second-attempt.v1",
            ["status"] = "APPROVAL_REQUIRED",
            ["dryRunPassed"] = true,
            ["actualMutationExecuted"] = false,
            ["approvalStateRequired"] = "APPROVED",
            ["nextStep"] = "request_user_approval_for_revised_patch"
        };
        var request = BuildRevisedPatchApprovalRequestReport(
            secondAttempt,
            dryRunResponse,
            "src/App.cs",
            "diff -- src/App.cs",
            staleIndexDisclosureRequired: true);
        var secondAttemptNotReady = new Dictionary<string, object?>(secondAttempt, StringComparer.Ordinal)
        {
            ["status"] = "REPLAN_REQUIRED"
        };
        var notReady = BuildRevisedPatchApprovalRequestReport(
            secondAttemptNotReady,
            dryRunResponse,
            "src/App.cs",
            "diff -- src/App.cs",
            staleIndexDisclosureRequired: true);

        var ok = request.TryGetValue("schema", out var schema)
            && string.Equals(schema?.ToString(), "learnbot.local-agent.revised-patch-approval-request.v1", StringComparison.Ordinal)
            && request.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "READY", StringComparison.Ordinal)
            && request.TryGetValue("targetFiles", out var targetFilesRaw)
            && targetFilesRaw is List<string> targetFiles
            && targetFiles.Contains("src/App.cs")
            && request.TryGetValue("approvalRequired", out var approvalRequired)
            && approvalRequired is true
            && request.TryGetValue("mutationAllowedBeforeApproval", out var mutationAllowedBeforeApproval)
            && mutationAllowedBeforeApproval is false
            && request.TryGetValue("dryRunSnapshotReady", out var snapshotReady)
            && snapshotReady is true
            && request.TryGetValue("staleIndexDisclosureRequired", out var staleDisclosure)
            && staleDisclosure is true
            && request.TryGetValue("nextQueuedMutationPreconditions", out var preconditionsRaw)
            && preconditionsRaw is List<string> preconditions
            && preconditions.Contains("approvalState=APPROVED")
            && notReady.TryGetValue("status", out var notReadyStatus)
            && string.Equals(notReadyStatus?.ToString(), "NOT_READY", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("revised patch approval request contract self-test failed");
            return 1;
        }

        Console.WriteLine("revised-patch-approval-request-contract-ok");
        return 0;
    }

    private static int SelfTestReadOnlyCandidateSelectionContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var treeResponse = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888870"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "workspace.tree",
            "SUCCEEDED",
            new Dictionary<string, object?>
            {
                ["entries"] = new List<Dictionary<string, object?>>
                {
                    new() { ["path"] = "src", ["type"] = "directory" },
                    new() { ["path"] = "src/App.cs", ["type"] = "file" },
                    new() { ["path"] = "README.md", ["type"] = "file" }
                }
            },
            null,
            null,
            now,
            now,
            []);
        var searchResponse = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888869"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "workspace.search",
            "SUCCEEDED",
            new Dictionary<string, object?>
            {
                ["matches"] = new List<Dictionary<string, object?>>
                {
                    new() { ["path"] = "src/App.cs", ["line"] = 2, ["snippet"] = "string Name = \"old\";" },
                    new() { ["path"] = "src/App.cs", ["line"] = 3, ["snippet"] = "string Name = \"older\";" }
                }
            },
            null,
            null,
            now,
            now,
            []);
        var selected = BuildReadOnlyCandidateSelectionReport("src/App.cs", treeResponse, searchResponse);

        var treeOnlySearch = searchResponse with
        {
            Status = "FAILED",
            FailureCode = "TOOL_FAILED",
            Output = new Dictionary<string, object?>()
        };
        var fallback = BuildReadOnlyCandidateSelectionReport("README.md", treeResponse, treeOnlySearch);

        var ok = selected.TryGetValue("selectedFiles", out var selectedFilesRaw)
            && selectedFilesRaw is List<Dictionary<string, object?>> selectedFiles
            && selectedFiles.Count == 1
            && string.Equals(selectedFiles[0]["path"]?.ToString(), "src/App.cs", StringComparison.Ordinal)
            && string.Equals(selectedFiles[0]["source"]?.ToString(), "workspace.search", StringComparison.Ordinal)
            && selected.TryGetValue("searchMatchCount", out var searchMatchCount)
            && searchMatchCount is 2
            && fallback.TryGetValue("selectedFiles", out var fallbackFilesRaw)
            && fallbackFilesRaw is List<Dictionary<string, object?>> fallbackFiles
            && fallbackFiles.Count == 1
            && string.Equals(fallbackFiles[0]["path"]?.ToString(), "README.md", StringComparison.Ordinal)
            && string.Equals(fallbackFiles[0]["source"]?.ToString(), "workspace.tree", StringComparison.Ordinal)
            && fallback.TryGetValue("status", out var fallbackStatus)
            && string.Equals(fallbackStatus?.ToString(), "READY_WITH_TREE_FALLBACK", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("read-only candidate selection contract self-test failed");
            return 1;
        }

        Console.WriteLine("read-only-candidate-selection-contract-ok");
        return 0;
    }

    private static int SelfTestMultiFileReadReportContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var candidateSelection = new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.read-only-candidate-selection.v1",
            ["selectedFiles"] = new List<Dictionary<string, object?>>
            {
                new() { ["path"] = "src/App.cs", ["rank"] = 1, ["source"] = "workspace.search", ["nextTool"] = "file.read" },
                new() { ["path"] = "README.md", ["rank"] = 2, ["source"] = "workspace.tree", ["nextTool"] = "file.read" }
            },
            ["readOnly"] = true,
            ["mutationAllowed"] = false
        };
        var appRead = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888871"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "file.read",
            "SUCCEEDED",
            new Dictionary<string, object?>
            {
                ["relativePath"] = "src/App.cs",
                ["bytes"] = 42,
                ["returnedBytes"] = 42,
                ["truncated"] = false
            },
            null,
            null,
            now,
            now,
            []);
        var readmeRead = appRead with
        {
            RequestId = Guid.Parse("88888888-8888-8888-8888-888888888872"),
            Output = new Dictionary<string, object?>
            {
                ["relativePath"] = "README.md",
                ["bytes"] = 128,
                ["returnedBytes"] = 64,
                ["truncated"] = true
            }
        };
        var report = BuildMultiFileReadReport(candidateSelection, [appRead, readmeRead]);
        var partial = BuildMultiFileReadReport(candidateSelection, [appRead]);

        var ok = report.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "SUCCEEDED", StringComparison.Ordinal)
            && report.TryGetValue("readFiles", out var readFilesRaw)
            && readFilesRaw is List<Dictionary<string, object?>> readFiles
            && readFiles.Count == 2
            && readFiles.Any(item => string.Equals(item["path"]?.ToString(), "README.md", StringComparison.Ordinal) && item.TryGetValue("truncated", out var truncated) && truncated is true)
            && report.TryGetValue("missingSelectedFiles", out var missingRaw)
            && missingRaw is List<string> missing
            && missing.Count == 0
            && report.TryGetValue("allSelectedFilesRead", out var allRead)
            && allRead is true
            && partial.TryGetValue("status", out var partialStatus)
            && string.Equals(partialStatus?.ToString(), "PARTIAL", StringComparison.Ordinal)
            && partial.TryGetValue("missingSelectedFiles", out var partialMissingRaw)
            && partialMissingRaw is List<string> partialMissing
            && partialMissing.Contains("README.md");
        if (!ok)
        {
            Console.Error.WriteLine("multi-file read report contract self-test failed");
            return 1;
        }

        Console.WriteLine("multi-file-read-report-contract-ok");
        return 0;
    }

    private static Dictionary<string, object?> BuildReadOnlyCandidateSelectionReport(
        string targetFile,
        ToolResponse treeResponse,
        ToolResponse searchResponse)
    {
        var searchPaths = ExtractPathsFromToolOutput(searchResponse.Output, "matches")
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var treePaths = ExtractPathsFromToolOutput(treeResponse.Output, "entries")
            .Where(path => LooksLikeSourceFile(path) || string.Equals(path, targetFile, StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var selectedPaths = searchPaths.Count > 0
            ? searchPaths
            : treePaths.Where(path => string.Equals(path, targetFile, StringComparison.OrdinalIgnoreCase)).ToList();
        if (selectedPaths.Count == 0 && treePaths.Count > 0)
        {
            selectedPaths.Add(treePaths[0]);
        }

        var selectedFiles = selectedPaths
            .Take(8)
            .Select((path, index) => new Dictionary<string, object?>
            {
                ["path"] = path,
                ["rank"] = index + 1,
                ["source"] = searchPaths.Contains(path, StringComparer.OrdinalIgnoreCase) ? "workspace.search" : "workspace.tree",
                ["nextTool"] = "file.read"
            })
            .ToList();

        var status = selectedFiles.Count == 0
            ? "NO_CANDIDATES"
            : searchPaths.Count > 0 && searchResponse.Status == "SUCCEEDED"
                ? "READY"
                : "READY_WITH_TREE_FALLBACK";
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.read-only-candidate-selection.v1",
            ["status"] = status,
            ["selectionInputs"] = new[] { "workspace.tree", "workspace.search" },
            ["selectedFiles"] = selectedFiles,
            ["searchMatchCount"] = CountItems(searchResponse.Output, "matches"),
            ["treeEntryCount"] = CountItems(treeResponse.Output, "entries"),
            ["nextTool"] = "file.read",
            ["readOnly"] = true,
            ["mutationAllowed"] = false,
            ["requiresModelRanking"] = false,
            ["modelRankingEnabled"] = false
        };
    }

    private static Dictionary<string, object?> BuildMultiFileReadReport(
        Dictionary<string, object?> candidateSelection,
        IReadOnlyList<ToolResponse> readResponses)
    {
        var selectedFiles = ExtractSelectedFilePaths(candidateSelection).ToList();
        var readFiles = readResponses
            .Where(response => string.Equals(response.ToolName, "file.read", StringComparison.Ordinal))
            .Select(response =>
            {
                var path = response.Output.TryGetValue("relativePath", out var relativePath) && !string.IsNullOrWhiteSpace(relativePath?.ToString())
                    ? relativePath!.ToString()!.Replace('\\', '/')
                    : response.Output.TryGetValue("path", out var pathValue) ? pathValue?.ToString()?.Replace('\\', '/') ?? "" : "";
                return new Dictionary<string, object?>
                {
                    ["path"] = path,
                    ["status"] = response.Status,
                    ["requestId"] = response.RequestId,
                    ["bytes"] = response.Output.TryGetValue("bytes", out var bytes) ? bytes : null,
                    ["returnedBytes"] = response.Output.TryGetValue("returnedBytes", out var returnedBytes) ? returnedBytes : null,
                    ["truncated"] = response.Output.TryGetValue("truncated", out var truncated) ? truncated : null,
                    ["contentSnippet"] = TruncateForReport(
                        response.Output.TryGetValue("content", out var content) ? content?.ToString() ?? "" : "",
                        2000)
                };
            })
            .Where(item => !string.IsNullOrWhiteSpace(item["path"]?.ToString()))
            .ToList();
        var readPaths = readFiles.Select(item => item["path"]?.ToString() ?? "").ToHashSet(StringComparer.OrdinalIgnoreCase);
        var missingSelectedFiles = selectedFiles.Where(path => !readPaths.Contains(path)).ToList();
        var failedReadCount = readFiles.Count(item => !string.Equals(item["status"]?.ToString(), "SUCCEEDED", StringComparison.Ordinal));
        var allSelectedFilesRead = selectedFiles.Count > 0 && missingSelectedFiles.Count == 0 && failedReadCount == 0;
        var status = allSelectedFilesRead ? "SUCCEEDED" : readFiles.Count > 0 ? "PARTIAL" : "NO_READS";
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.multi-file-read-report.v1",
            ["status"] = status,
            ["selectedFiles"] = selectedFiles,
            ["readFiles"] = readFiles,
            ["selectedFileCount"] = selectedFiles.Count,
            ["readFileCount"] = readFiles.Count,
            ["missingSelectedFiles"] = missingSelectedFiles,
            ["failedReadCount"] = failedReadCount,
            ["allSelectedFilesRead"] = allSelectedFilesRead,
            ["readOnly"] = true,
            ["mutationAllowed"] = false,
            ["usesExistingTool"] = "file.read"
        };
    }

    private static Dictionary<string, object?> BuildPatchTestRetryDecisionReport(
        ToolResponse commandResponse,
        ToolResponse statusResponse)
    {
        var commandId = ToolOutputString(commandResponse, "commandId");
        var exitCode = ToolOutputValue(commandResponse, "exitCode");
        var timedOut = ToolOutputBool(commandResponse, "timedOut") ?? commandResponse.Status == "TIMED_OUT";
        var truncated = ToolOutputBool(commandResponse, "truncated") ?? false;
        var stdout = ToolOutputString(commandResponse, "stdout") ?? "";
        var stderr = ToolOutputString(commandResponse, "stderr") ?? "";
        var clean = ToolOutputValue(statusResponse, "clean");
        var retryableFailure = commandResponse.Status is "FAILED" or "TIMED_OUT"
            && commandResponse.FailureCode is "TEST_FAILED" or "TIMEOUT";
        var blocked = commandResponse.Status == "REJECTED"
            || commandResponse.FailureCode is "UNSAFE_TOOL" or "APPROVAL_REQUIRED" or "WORKSPACE_NOT_APPROVED" or "PATH_ESCAPE";
        var status = commandResponse.Status == "SUCCEEDED"
            ? "NO_RETRY_NEEDED"
            : retryableFailure
                ? "RETRY_RECOMMENDED"
                : blocked ? "BLOCKED" : "NEEDS_HUMAN_REVIEW";
        var retryRecommended = status == "RETRY_RECOMMENDED";
        var diagnosticSignals = DetectPatchTestDiagnosticSignals(commandResponse, stdout, stderr, timedOut, truncated);
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.patch-test-retry-decision.v1",
            ["status"] = status,
            ["retryRecommended"] = retryRecommended,
            ["nextStep"] = retryRecommended
                ? "replan_patch_from_failure_logs"
                : commandResponse.Status == "SUCCEEDED" ? "final_report" : "human_review",
            ["analysisOnly"] = true,
            ["mutationAllowed"] = false,
            ["usesExistingTool"] = "command.runAllowed",
            ["requiresNewApprovalBeforeMutation"] = retryRecommended,
            ["failureSummary"] = new Dictionary<string, object?>
            {
                ["commandId"] = commandId,
                ["commandStatus"] = commandResponse.Status,
                ["failureCode"] = commandResponse.FailureCode,
                ["error"] = commandResponse.Error,
                ["exitCode"] = exitCode,
                ["timedOut"] = timedOut,
                ["truncated"] = truncated,
                ["stdoutSnippet"] = TruncateForReport(stdout, 600),
                ["stderrSnippet"] = TruncateForReport(stderr, 600),
                ["postPatchGitClean"] = clean,
                ["diagnosticSignals"] = diagnosticSignals
            }
        };
    }

    private static Dictionary<string, object?> BuildPatchTestSecondAttemptReport(
        Dictionary<string, object?> retryDecision,
        ToolResponse? dryRunResponse,
        IReadOnlyList<Dictionary<string, object?>> revisedPatchProposal,
        Dictionary<string, object?>? dryRunHandoff = null)
    {
        var retryRecommended = retryDecision.TryGetValue("retryRecommended", out var retryValue)
            && retryValue is true;
        object? handoffStatus = null;
        var handoffReady = dryRunHandoff?.TryGetValue("status", out handoffStatus) == true
            && string.Equals(handoffStatus?.ToString(), "READY_DRY_RUN_QUEUE_DISABLED", StringComparison.Ordinal);
        var handoffTargetFiles = ExtractHandoffPatchTargetFiles(dryRunHandoff).ToList();
        var dryRunTargetFiles = ExtractDryRunTargetFiles(dryRunResponse, "").ToList();
        var handoffConsumed = handoffReady && dryRunResponse is not null;
        var handoffTargetsMatchDryRun = handoffConsumed
            && handoffTargetFiles.Count > 0
            && dryRunTargetFiles.Count > 0
            && handoffTargetFiles.OrderBy(item => item, StringComparer.OrdinalIgnoreCase)
                .SequenceEqual(dryRunTargetFiles.OrderBy(item => item, StringComparer.OrdinalIgnoreCase), StringComparer.OrdinalIgnoreCase);
        var dryRun = dryRunResponse?.Output.TryGetValue("dryRun", out var dryRunValue) == true ? dryRunValue : null;
        var preflightPassed = dryRunResponse?.Output.TryGetValue("preflightPassed", out var preflightValue) == true ? preflightValue : null;
        var mutationApplied = dryRunResponse?.Output.TryGetValue("mutationApplied", out var mutationValue) == true ? mutationValue : null;
        var snapshotCreated = dryRunResponse?.Output.TryGetValue("snapshotCreated", out var snapshotValue) == true ? snapshotValue : null;
        var dryRunPassed = dryRunResponse is not null
            && dryRun is true
            && preflightPassed is true
            && mutationApplied is false
            && dryRunResponse.FailureCode == "UNSAFE_TOOL";
        var dryRunFailed = dryRunResponse is not null && !dryRunPassed;
        var status = !retryRecommended
            ? "NOT_NEEDED"
            : dryRunPassed
                ? "APPROVAL_REQUIRED"
                : dryRunFailed ? "REPLAN_REQUIRED" : "DRY_RUN_REQUIRED";
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.patch-test-second-attempt.v1",
            ["status"] = status,
            ["trigger"] = retryRecommended ? "patchTestRetryDecision" : "none",
            ["analysisOnly"] = true,
            ["mutationAllowed"] = false,
            ["actualMutationExecuted"] = false,
            ["revisedPatchProposal"] = revisedPatchProposal,
            ["revisedPatchProposalCount"] = revisedPatchProposal.Count,
            ["dryRunTool"] = "patch.apply",
            ["dryRunInputSource"] = handoffReady ? "validatedRevisedPatchDryRunHandoff" : "direct_or_not_ready",
            ["dryRunRequired"] = retryRecommended,
            ["dryRunExecuted"] = dryRunResponse is not null,
            ["dryRunPassed"] = dryRunPassed,
            ["dryRunStatus"] = dryRunResponse?.Status,
            ["dryRunFailureCode"] = dryRunResponse?.FailureCode,
            ["dryRunError"] = dryRunResponse?.Error,
            ["dryRunHandoff"] = new Dictionary<string, object?>
            {
                ["schema"] = dryRunHandoff?.TryGetValue("schema", out var schema) == true ? schema : null,
                ["status"] = handoffStatus,
                ["ready"] = handoffReady,
                ["consumed"] = handoffConsumed,
                ["targetFiles"] = handoffTargetFiles,
                ["dryRunResponseTargetFiles"] = dryRunTargetFiles,
                ["targetsMatchDryRun"] = handoffTargetsMatchDryRun,
                ["queueEnabled"] = dryRunHandoff?.TryGetValue("dryRunQueueEnabled", out var queueEnabled) == true ? queueEnabled : null,
                ["requestPersisted"] = dryRunHandoff?.TryGetValue("dryRunRequestPersisted", out var requestPersisted) == true ? requestPersisted : null,
                ["claimable"] = dryRunHandoff?.TryGetValue("dryRunClaimable", out var claimable) == true ? claimable : null
            },
            ["preflightPassed"] = preflightPassed,
            ["snapshotCreated"] = snapshotCreated,
            ["approvalStateRequired"] = dryRunPassed ? "APPROVED" : null,
            ["nextStep"] = status switch
            {
                "APPROVAL_REQUIRED" => "request_user_approval_for_revised_patch",
                "REPLAN_REQUIRED" => "replan_revised_patch",
                "DRY_RUN_REQUIRED" => "run_patch_dry_run",
                _ => "continue_without_retry"
            },
            ["requiresNewApprovalBeforeMutation"] = dryRunPassed
        };
    }

    private static Dictionary<string, object?> BuildRevisedPatchProposalPlanReport(
        Dictionary<string, object?> retryDecision,
        ToolResponse commandResponse,
        IReadOnlyList<string> targetFiles)
    {
        var retryRecommended = retryDecision.TryGetValue("retryRecommended", out var retryValue)
            && retryValue is true;
        var normalizedTargetFiles = targetFiles
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(path => path.Replace('\\', '/'))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var stdout = ToolOutputString(commandResponse, "stdout") ?? "";
        var stderr = ToolOutputString(commandResponse, "stderr") ?? "";
        var stdoutEvidenceAvailable = !string.IsNullOrWhiteSpace(stdout);
        var stderrEvidenceAvailable = !string.IsNullOrWhiteSpace(stderr);
        var failureEvidenceAvailable = stdoutEvidenceAvailable || stderrEvidenceAvailable || !string.IsNullOrWhiteSpace(commandResponse.Error);
        var targetFilesKnown = normalizedTargetFiles.Count > 0;
        var status = !retryRecommended
            ? "NOT_REQUIRED"
            : !failureEvidenceAvailable
                ? "BLOCKED_MISSING_FAILURE_EVIDENCE"
                : !targetFilesKnown ? "BLOCKED_MISSING_TARGET_FILES" : "READY_MODEL_DISABLED";
        var ready = status == "READY_MODEL_DISABLED";
        var reason = BuildProposalReason(commandResponse, stdout, stderr);
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.revised-patch-proposal-plan.v1",
            ["status"] = status,
            ["trigger"] = retryRecommended ? "patchTestRetryDecision" : "none",
            ["commandRequestId"] = commandResponse.RequestId,
            ["commandId"] = ToolOutputString(commandResponse, "commandId"),
            ["commandStatus"] = commandResponse.Status,
            ["failureCode"] = commandResponse.FailureCode,
            ["exitCode"] = ToolOutputValue(commandResponse, "exitCode"),
            ["stdoutEvidenceAvailable"] = stdoutEvidenceAvailable,
            ["stderrEvidenceAvailable"] = stderrEvidenceAvailable,
            ["failureEvidenceAvailable"] = failureEvidenceAvailable,
            ["failureEvidenceMaxChars"] = 600,
            ["failureEvidence"] = new Dictionary<string, object?>
            {
                ["error"] = commandResponse.Error,
                ["stdoutSnippet"] = TruncateForReport(stdout, 600),
                ["stderrSnippet"] = TruncateForReport(stderr, 600)
            },
            ["targetFiles"] = normalizedTargetFiles,
            ["targetFileCount"] = normalizedTargetFiles.Count,
            ["targetFilesKnown"] = targetFilesKnown,
            ["proposalGenerationEnabled"] = false,
            ["localLlmPlanningEnabled"] = false,
            ["readOnly"] = true,
            ["mutationAllowed"] = false,
            ["dryRunOnly"] = true,
            ["dryRunRequiredBeforeApproval"] = ready,
            ["approvalRequiredBeforeMutation"] = ready,
            ["requiresUserApproval"] = ready,
            ["publicationEnabled"] = false,
            ["partialReindexEnabled"] = false,
            ["proposedTargetFiles"] = ready
                ? normalizedTargetFiles.Select(path => new Dictionary<string, object?>
                {
                    ["path"] = path,
                    ["source"] = "failed command stdout/stderr",
                    ["reason"] = reason
                }).ToList()
                : [],
            ["nextStep"] = status switch
            {
                "READY_MODEL_DISABLED" => "generate_revised_patch_proposal_with_local_model",
                "BLOCKED_MISSING_FAILURE_EVIDENCE" => "collect_failed_command_stdout_stderr",
                "BLOCKED_MISSING_TARGET_FILES" => "read_or_select_target_files",
                _ => "continue_without_retry"
            }
        };
    }

    private static IEnumerable<string> ExtractHandoffPatchTargetFiles(Dictionary<string, object?>? dryRunHandoff)
    {
        if (dryRunHandoff is null
            || !dryRunHandoff.TryGetValue("patchApplyInput", out var input)
            || input is null)
        {
            yield break;
        }
        if (input is Dictionary<string, object?> dictionary)
        {
            foreach (var item in ExtractStringList(dictionary, "targetFiles"))
            {
                yield return item;
            }
            yield break;
        }
        if (input is JsonElement element
            && element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty("targetFiles", out var targetFiles)
            && targetFiles.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in targetFiles.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
                {
                    yield return item.GetString()!.Replace('\\', '/');
                }
            }
        }
    }

    private static Dictionary<string, object?> BuildLocalModelRevisedPatchRequestReport(
        Dictionary<string, object?> proposalPlan,
        Dictionary<string, object?> multiFileRead)
    {
        const int maxTargetFiles = 8;
        const int maxFailureEvidenceChars = 600;
        const int maxFileEvidenceChars = 2000;
        const int maxInputChars = 12000;
        const int maxDiffChars = 24000;
        var proposalReady = proposalPlan.TryGetValue("status", out var proposalStatus)
            && string.Equals(proposalStatus?.ToString(), "READY_MODEL_DISABLED", StringComparison.Ordinal);
        var allSelectedFilesRead = multiFileRead.TryGetValue("allSelectedFilesRead", out var allReadValue)
            && allReadValue is true;
        var targetFiles = ExtractStringList(proposalPlan, "targetFiles")
            .Take(maxTargetFiles)
            .ToList();
        var readEvidence = ExtractReadEvidenceForModel(multiFileRead, targetFiles, maxFileEvidenceChars).ToList();
        var readEvidenceAvailable = allSelectedFilesRead && readEvidence.Count > 0;
        var status = !proposalReady
            ? "NOT_READY"
            : !readEvidenceAvailable ? "BLOCKED_MISSING_READ_EVIDENCE" : "READY_MODEL_CALL_DISABLED";
        var ready = status == "READY_MODEL_CALL_DISABLED";
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-request.v1",
            ["status"] = status,
            ["trigger"] = proposalReady ? "revisedPatchProposalPlan" : "none",
            ["modelCallEnabled"] = false,
            ["localLlmPlanningEnabled"] = false,
            ["mutationAllowed"] = false,
            ["patchApplyQueued"] = false,
            ["dryRunRequiredBeforeApproval"] = ready,
            ["approvalRequiredBeforeMutation"] = ready,
            ["requiresUserApproval"] = ready,
            ["maxTargetFiles"] = maxTargetFiles,
            ["maxFailureEvidenceChars"] = maxFailureEvidenceChars,
            ["maxFileEvidenceChars"] = maxFileEvidenceChars,
            ["maxInputChars"] = maxInputChars,
            ["maxDiffChars"] = maxDiffChars,
            ["targetFiles"] = targetFiles,
            ["targetFileCount"] = targetFiles.Count,
            ["readEvidenceAvailable"] = readEvidenceAvailable,
            ["readEvidence"] = readEvidence,
            ["modelInput"] = ready
                ? new Dictionary<string, object?>
                {
                    ["instruction"] = "Generate a unified diff that fixes the failing allowlisted command using only the provided target files and failure evidence.",
                    ["failureEvidence"] = LimitFailureEvidence(proposalPlan, maxFailureEvidenceChars),
                    ["targetFiles"] = targetFiles,
                    ["readEvidence"] = readEvidence,
                    ["constraints"] = new[]
                    {
                        "output_unified_diff_only",
                        "touch_only_target_files",
                        "max_diff_chars=24000",
                        "no_mutation",
                        "dry_run_required",
                        "user_approval_required_before_mutation"
                    }
                }
                : null,
            ["expectedModelOutput"] = ready
                ? new Dictionary<string, object?>
                {
                    ["schema"] = "learnbot.local-agent.local-model-revised-patch-output.v1",
                    ["requiredFields"] = new[] { "targetFiles", "unifiedDiff", "rationale", "riskNotes" },
                    ["unifiedDiffMaxChars"] = maxDiffChars,
                    ["nextTool"] = "patch.apply",
                    ["nextToolDryRunOnly"] = true,
                    ["nextToolMutationAllowed"] = false
                }
                : null,
            ["nextStep"] = status switch
            {
                "READY_MODEL_CALL_DISABLED" => "call_local_model_for_revised_patch_proposal",
                "BLOCKED_MISSING_READ_EVIDENCE" => "read_target_files_before_model_call",
                _ => "wait_for_ready_proposal_plan"
            }
        };
    }

    private static Dictionary<string, object?> BuildLocalModelRevisedPatchOutputValidationReport(
        Dictionary<string, object?> modelRequest,
        Dictionary<string, object?>? modelOutput)
    {
        var requestReady = modelRequest.TryGetValue("status", out var requestStatus)
            && string.Equals(requestStatus?.ToString(), "READY_MODEL_CALL_DISABLED", StringComparison.Ordinal);
        var plannedTargetFiles = ExtractStringList(modelRequest, "targetFiles")
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var maxDiffChars = ExtractInt(modelRequest, "maxDiffChars") ?? 24000;
        if (!requestReady)
        {
            return LocalModelOutputValidationResult(
                "NOT_READY",
                plannedTargetFiles,
                [],
                "",
                maxDiffChars,
                ["modelRequestNotReady"]);
        }
        if (modelOutput is null)
        {
            return LocalModelOutputValidationResult(
                "WAITING_FOR_MODEL_OUTPUT",
                plannedTargetFiles,
                [],
                "",
                maxDiffChars,
                []);
        }

        var unifiedDiff = modelOutput.TryGetValue("unifiedDiff", out var diffValue)
            ? diffValue?.ToString() ?? ""
            : "";
        var outputTargetFiles = ExtractStringList(modelOutput, "targetFiles")
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var diffTargetFiles = ExtractUnifiedDiffTargetFiles(unifiedDiff)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var touchedFiles = outputTargetFiles.Count > 0 ? outputTargetFiles : diffTargetFiles;
        var blockers = new List<string>();
        if (string.IsNullOrWhiteSpace(unifiedDiff))
        {
            blockers.Add("emptyDiff");
        }
        if (unifiedDiff.Length > maxDiffChars)
        {
            blockers.Add("oversizedDiff");
        }
        if (touchedFiles.Count == 0)
        {
            blockers.Add("noTargetFilesInOutput");
        }
        var outOfTargetFiles = touchedFiles
            .Where(path => !plannedTargetFiles.Contains(path, StringComparer.OrdinalIgnoreCase))
            .ToList();
        if (outOfTargetFiles.Count > 0)
        {
            blockers.Add("outOfTargetFiles");
        }
        var missingDiffForDeclaredTargets = outputTargetFiles
            .Where(path => diffTargetFiles.Count > 0 && !diffTargetFiles.Contains(path, StringComparer.OrdinalIgnoreCase))
            .ToList();
        if (missingDiffForDeclaredTargets.Count > 0)
        {
            blockers.Add("declaredTargetMissingFromDiff");
        }

        var status = blockers.Contains("emptyDiff")
            ? "BLOCKED_EMPTY_DIFF"
            : blockers.Contains("oversizedDiff")
                ? "BLOCKED_OVERSIZED_DIFF"
                : blockers.Contains("outOfTargetFiles") || blockers.Contains("declaredTargetMissingFromDiff")
                    ? "BLOCKED_OUT_OF_TARGET"
                    : blockers.Count > 0 ? "BLOCKED_INVALID_OUTPUT" : "READY_FOR_DRY_RUN";
        var result = LocalModelOutputValidationResult(status, plannedTargetFiles, touchedFiles, unifiedDiff, maxDiffChars, blockers);
        result["outputSchema"] = modelOutput.TryGetValue("schema", out var schema) ? schema : null;
        result["rationale"] = modelOutput.TryGetValue("rationale", out var rationale) ? TruncateForReport(rationale?.ToString() ?? "", 600) : null;
        result["riskNotes"] = modelOutput.TryGetValue("riskNotes", out var riskNotes) ? TruncateForReport(riskNotes?.ToString() ?? "", 600) : null;
        result["diffTargetFiles"] = diffTargetFiles;
        result["outputTargetFiles"] = outputTargetFiles;
        result["outOfTargetFiles"] = outOfTargetFiles;
        return result;
    }

    private static Dictionary<string, object?> LocalModelOutputValidationResult(
        string status,
        IReadOnlyList<string> plannedTargetFiles,
        IReadOnlyList<string> validatedTargetFiles,
        string unifiedDiff,
        int maxDiffChars,
        IReadOnlyList<string> blockers)
    {
        var ready = status == "READY_FOR_DRY_RUN";
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.local-model-revised-patch-output-validation.v1",
            ["status"] = status,
            ["plannedTargetFiles"] = plannedTargetFiles,
            ["validatedTargetFiles"] = ready ? validatedTargetFiles.ToList() : [],
            ["validatedTargetFileCount"] = ready ? validatedTargetFiles.Count : 0,
            ["diffPresent"] = !string.IsNullOrWhiteSpace(unifiedDiff),
            ["diffChars"] = unifiedDiff.Length,
            ["maxDiffChars"] = maxDiffChars,
            ["diffPreview"] = TruncateForReport(unifiedDiff, 600),
            ["blockers"] = blockers,
            ["modelOutputAccepted"] = ready,
            ["mutationAllowed"] = false,
            ["patchApplyQueued"] = false,
            ["patchApplyDryRunOnly"] = ready,
            ["patchApplyMutationAllowed"] = false,
            ["approvalRequiredBeforeMutation"] = ready,
            ["nextTool"] = ready ? "patch.apply" : null,
            ["nextToolInput"] = ready
                ? new Dictionary<string, object?>
                {
                    ["dryRunOnly"] = true,
                    ["mutationAllowed"] = false,
                    ["targetFiles"] = validatedTargetFiles,
                    ["diff"] = unifiedDiff
                }
                : null,
            ["nextStep"] = status switch
            {
                "READY_FOR_DRY_RUN" => "queue_patch_apply_dry_run_for_validated_revised_diff",
                "WAITING_FOR_MODEL_OUTPUT" => "wait_for_local_model_revised_patch_output",
                _ => "reject_model_output_and_replan"
            }
        };
    }

    private static Dictionary<string, object?> BuildValidatedRevisedPatchDryRunHandoffReport(
        Dictionary<string, object?> outputValidation)
    {
        var outputReady = outputValidation.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "READY_FOR_DRY_RUN", StringComparison.Ordinal);
        var nextToolInput = outputReady && outputValidation.TryGetValue("nextToolInput", out var input)
            && input is Dictionary<string, object?> dictionary
                ? dictionary
                : null;
        var targetFiles = nextToolInput is null
            ? []
            : ExtractStringList(nextToolInput, "targetFiles").ToList();
        var diff = nextToolInput is not null && nextToolInput.TryGetValue("diff", out var diffValue)
            ? diffValue?.ToString() ?? ""
            : "";
        var blockers = outputReady
            ? new List<string>()
            : new List<string> { "validatedModelOutputNotReady" };
        var ready = outputReady && nextToolInput is not null && targetFiles.Count > 0 && !string.IsNullOrWhiteSpace(diff);
        if (outputReady && nextToolInput is null)
        {
            blockers.Add("nextToolInputMissing");
        }
        if (outputReady && targetFiles.Count == 0)
        {
            blockers.Add("targetFilesMissing");
        }
        if (outputReady && string.IsNullOrWhiteSpace(diff))
        {
            blockers.Add("diffMissing");
        }

        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
            ["status"] = ready ? "READY_DRY_RUN_QUEUE_DISABLED" : "BLOCKED_OUTPUT_NOT_READY",
            ["sourceSchema"] = outputValidation.TryGetValue("schema", out var schema) ? schema : null,
            ["sourceStatus"] = status,
            ["targetFiles"] = targetFiles,
            ["targetFileCount"] = targetFiles.Count,
            ["diffChars"] = diff.Length,
            ["diffPreview"] = TruncateForReport(diff, 600),
            ["patchApplyInput"] = ready ? nextToolInput : null,
            ["patchApplyTool"] = "patch.apply",
            ["dryRunQueueEnabled"] = false,
            ["dryRunRequestPersisted"] = false,
            ["dryRunClaimable"] = false,
            ["dryRunOnly"] = ready,
            ["mutationAllowed"] = false,
            ["patchApplyMutationAllowed"] = false,
            ["approvalRequiredBeforeMutation"] = ready,
            ["requiresSnapshotBeforeMutation"] = true,
            ["blockers"] = blockers,
            ["nextStep"] = ready
                ? "persist_or_queue_patch_apply_dry_run_when_enabled"
                : "wait_for_validated_model_output"
        };
    }

    private static Dictionary<string, object?> BuildRevisedPatchApprovalRequestReport(
        Dictionary<string, object?> secondAttempt,
        ToolResponse? dryRunResponse,
        string targetFile,
        string? revisedDiff,
        bool staleIndexDisclosureRequired)
    {
        var secondAttemptReady = secondAttempt.TryGetValue("status", out var secondAttemptStatus)
            && string.Equals(secondAttemptStatus?.ToString(), "APPROVAL_REQUIRED", StringComparison.Ordinal);
        var dryRunPassed = secondAttempt.TryGetValue("dryRunPassed", out var dryRunPassedValue)
            && dryRunPassedValue is true;
        var actualMutationExecuted = secondAttempt.TryGetValue("actualMutationExecuted", out var mutationValue)
            && mutationValue is true;
        var ready = secondAttemptReady
            && dryRunPassed
            && dryRunResponse is not null
            && dryRunResponse.ToolName == "patch.apply"
            && dryRunResponse.Status == "REJECTED"
            && dryRunResponse.FailureCode == "UNSAFE_TOOL"
            && !actualMutationExecuted;
        var targetFiles = ExtractDryRunTargetFiles(dryRunResponse, targetFile).ToList();
        var approvalRequestId = ready
            ? BuildApprovalRequestId(targetFile, revisedDiff, dryRunResponse)
            : null;
        var snapshotObservation = dryRunResponse?.Output.TryGetValue("snapshotObservation", out var snapshotValue) == true
            ? snapshotValue
            : null;
        var rollbackObservation = dryRunResponse?.Output.TryGetValue("rollbackObservation", out var rollbackValue) == true
            ? rollbackValue
            : null;
        var snapshotCreated = dryRunResponse?.Output.TryGetValue("snapshotCreated", out var snapshotCreatedValue) == true
            ? snapshotCreatedValue
            : null;
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.revised-patch-approval-request.v1",
            ["status"] = ready ? "READY" : "NOT_READY",
            ["approvalRequestId"] = approvalRequestId,
            ["approvalPersistenceRequired"] = ready,
            ["approvalPersisted"] = false,
            ["approvalRequired"] = ready,
            ["approvalStateRequired"] = ready ? "APPROVED" : null,
            ["nextQueuedMutationPreconditions"] = new List<string>
            {
                "approvalRequestIdPresent",
                "approvalRequestIdMatchesPersistedRequest",
                "approvalState=APPROVED",
                "mutationAllowed=true",
                "dryRunOnly=false",
                "targetFilesMatchDryRun",
                "snapshotManifestIdPresent"
            },
            ["nextMutationAllowedOnlyWhen"] = "approvalState=APPROVED",
            ["mutationAllowedBeforeApproval"] = false,
            ["actualMutationExecuted"] = actualMutationExecuted,
            ["targetFile"] = targetFile,
            ["targetFiles"] = targetFiles,
            ["diffEvidence"] = new Dictionary<string, object?>
            {
                ["present"] = !string.IsNullOrWhiteSpace(revisedDiff),
                ["bytes"] = string.IsNullOrEmpty(revisedDiff) ? 0 : Encoding.UTF8.GetByteCount(revisedDiff),
                ["preview"] = TruncateForReport(revisedDiff ?? "", 600)
            },
            ["dryRunEvidence"] = new Dictionary<string, object?>
            {
                ["requestId"] = dryRunResponse?.RequestId,
                ["status"] = dryRunResponse?.Status,
                ["failureCode"] = dryRunResponse?.FailureCode,
                ["preflightPassed"] = dryRunResponse?.Output.TryGetValue("preflightPassed", out var preflightPassed) == true ? preflightPassed : null,
                ["mutationApplied"] = dryRunResponse?.Output.TryGetValue("mutationApplied", out var mutationApplied) == true ? mutationApplied : null,
                ["snapshotCreated"] = snapshotCreated
            },
            ["dryRunSnapshotReady"] = ready && snapshotCreated is true,
            ["snapshotObservation"] = snapshotObservation,
            ["rollbackObservation"] = rollbackObservation,
            ["ragFreshness"] = new Dictionary<string, object?>
            {
                ["statusAfterApprovedMutation"] = "STALE_UNTIL_PARTIAL_REINDEX",
                ["partialReindexRequiredAfterApproval"] = true,
                ["partialReindexEnabled"] = false,
                ["staleIndexDisclosureRequired"] = staleIndexDisclosureRequired,
                ["targetFiles"] = targetFiles
            },
            ["staleIndexDisclosureRequired"] = staleIndexDisclosureRequired,
            ["nextStep"] = ready ? "persist_user_approval_request" : "wait_for_ready_second_attempt"
        };
    }

    private static string BuildProposalReason(ToolResponse commandResponse, string stdout, string stderr)
    {
        var candidate = !string.IsNullOrWhiteSpace(stderr)
            ? stderr
            : !string.IsNullOrWhiteSpace(stdout) ? stdout : commandResponse.Error ?? "";
        return TruncateForReport(candidate.Trim(), 240);
    }

    private static List<Dictionary<string, object?>> ExtractRevisedPatchProposalEntries(
        Dictionary<string, object?> proposalPlan)
    {
        if (!proposalPlan.TryGetValue("proposedTargetFiles", out var value) || value is null)
        {
            return [];
        }
        if (value is List<Dictionary<string, object?>> list)
        {
            return list;
        }
        if (value is IEnumerable<Dictionary<string, object?>> dictionaries)
        {
            return dictionaries.ToList();
        }
        if (value is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            var entries = new List<Dictionary<string, object?>>();
            foreach (var item in element.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }
                entries.Add(new Dictionary<string, object?>
                {
                    ["path"] = item.TryGetProperty("path", out var path) && path.ValueKind == JsonValueKind.String ? path.GetString() : null,
                    ["source"] = item.TryGetProperty("source", out var source) && source.ValueKind == JsonValueKind.String ? source.GetString() : null,
                    ["reason"] = item.TryGetProperty("reason", out var reason) && reason.ValueKind == JsonValueKind.String ? reason.GetString() : null
                });
            }
            return entries;
        }
        return [];
    }

    private static IEnumerable<Dictionary<string, object?>> ExtractReadEvidenceForModel(
        Dictionary<string, object?> multiFileRead,
        IReadOnlyCollection<string> targetFiles,
        int maxChars)
    {
        if (!multiFileRead.TryGetValue("readFiles", out var value) || value is null)
        {
            yield break;
        }
        IEnumerable<Dictionary<string, object?>> readFiles = value switch
        {
            IEnumerable<Dictionary<string, object?>> dictionaries => dictionaries,
            JsonElement element when element.ValueKind == JsonValueKind.Array => ReadFileEvidenceFromJson(element),
            _ => []
        };
        foreach (var item in readFiles)
        {
            var path = item.TryGetValue("path", out var pathValue) ? pathValue?.ToString()?.Replace('\\', '/') : null;
            if (string.IsNullOrWhiteSpace(path)
                || targetFiles.Count > 0 && !targetFiles.Contains(path, StringComparer.OrdinalIgnoreCase))
            {
                continue;
            }
            yield return new Dictionary<string, object?>
            {
                ["path"] = path,
                ["status"] = item.TryGetValue("status", out var status) ? status : null,
                ["bytes"] = item.TryGetValue("bytes", out var bytes) ? bytes : null,
                ["returnedBytes"] = item.TryGetValue("returnedBytes", out var returnedBytes) ? returnedBytes : null,
                ["truncated"] = item.TryGetValue("truncated", out var truncated) ? truncated : null,
                ["contentSnippet"] = TruncateForReport(
                    item.TryGetValue("contentSnippet", out var contentSnippet)
                        ? contentSnippet?.ToString() ?? ""
                        : item.TryGetValue("content", out var content) ? content?.ToString() ?? "" : "",
                    maxChars)
            };
        }
    }

    private static IEnumerable<Dictionary<string, object?>> ReadFileEvidenceFromJson(JsonElement element)
    {
        foreach (var item in element.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
            {
                continue;
            }
            yield return new Dictionary<string, object?>
            {
                ["path"] = item.TryGetProperty("path", out var path) && path.ValueKind == JsonValueKind.String ? path.GetString() : null,
                ["status"] = item.TryGetProperty("status", out var status) && status.ValueKind == JsonValueKind.String ? status.GetString() : null,
                ["bytes"] = item.TryGetProperty("bytes", out var bytes) && bytes.ValueKind == JsonValueKind.Number ? bytes.GetInt64() : null,
                ["returnedBytes"] = item.TryGetProperty("returnedBytes", out var returnedBytes) && returnedBytes.ValueKind == JsonValueKind.Number ? returnedBytes.GetInt64() : null,
                ["truncated"] = item.TryGetProperty("truncated", out var truncated) && truncated.ValueKind is JsonValueKind.True or JsonValueKind.False ? truncated.GetBoolean() : null,
                ["content"] = item.TryGetProperty("content", out var content) && content.ValueKind == JsonValueKind.String ? content.GetString() : null
            };
        }
    }

    private static Dictionary<string, object?> LimitFailureEvidence(
        Dictionary<string, object?> proposalPlan,
        int maxChars)
    {
        var evidence = proposalPlan.TryGetValue("failureEvidence", out var raw)
            && raw is Dictionary<string, object?> dictionary
                ? dictionary
                : [];
        return new Dictionary<string, object?>
        {
            ["error"] = TruncateForReport(evidence.TryGetValue("error", out var error) ? error?.ToString() ?? "" : "", maxChars),
            ["stdoutSnippet"] = TruncateForReport(evidence.TryGetValue("stdoutSnippet", out var stdout) ? stdout?.ToString() ?? "" : "", maxChars),
            ["stderrSnippet"] = TruncateForReport(evidence.TryGetValue("stderrSnippet", out var stderr) ? stderr?.ToString() ?? "" : "", maxChars)
        };
    }

    private static IEnumerable<string> ExtractStringList(Dictionary<string, object?> source, string key)
    {
        if (!source.TryGetValue(key, out var value) || value is null)
        {
            yield break;
        }
        if (value is IEnumerable<string> strings)
        {
            foreach (var item in strings)
            {
                if (!string.IsNullOrWhiteSpace(item))
                {
                    yield return item.Replace('\\', '/');
                }
            }
            yield break;
        }
        if (value is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
                {
                    yield return item.GetString()!.Replace('\\', '/');
                }
            }
        }
    }

    private static int? ExtractInt(Dictionary<string, object?> source, string key)
    {
        if (!source.TryGetValue(key, out var value) || value is null)
        {
            return null;
        }
        return value switch
        {
            int integer => integer,
            long longValue when longValue <= int.MaxValue && longValue >= int.MinValue => (int)longValue,
            JsonElement { ValueKind: JsonValueKind.Number } element when element.TryGetInt32(out var parsed) => parsed,
            string text when int.TryParse(text, out var parsed) => parsed,
            _ => null
        };
    }

    private static IEnumerable<string> ExtractUnifiedDiffTargetFiles(string unifiedDiff)
    {
        foreach (var rawLine in unifiedDiff.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var line = rawLine.Trim();
            if (!line.StartsWith("+++ ", StringComparison.Ordinal))
            {
                continue;
            }
            var path = line[4..].Trim();
            if (path == "/dev/null")
            {
                continue;
            }
            if (path.StartsWith("b/", StringComparison.Ordinal))
            {
                path = path[2..];
            }
            if (!string.IsNullOrWhiteSpace(path))
            {
                yield return path.Replace('\\', '/');
            }
        }
    }

    private static int SelfTestRevisedPatchApprovalGateContract()
    {
        var now = DateTimeOffset.UtcNow;
        var sessionId = Guid.Parse("55555555-5555-5555-5555-555555555555");
        var userId = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var agentId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var dryRunResponse = new ToolResponse(
            sessionId,
            Guid.Parse("88888888-8888-8888-8888-888888888885"),
            userId,
            agentId,
            workspaceId,
            "USER_LOCAL_AGENT",
            "patch.apply",
            "REJECTED",
            new Dictionary<string, object?>
            {
                ["dryRun"] = true,
                ["preflightPassed"] = true,
                ["mutationApplied"] = false,
                ["snapshotCreated"] = true,
                ["files"] = new List<Dictionary<string, object?>>
                {
                    new() { ["path"] = "src/App.cs", ["contextMatches"] = true, ["hashMatches"] = true }
                }
            },
            "UNSAFE_TOOL",
            "Patch dry-run passed and a local snapshot was created, but file mutation is disabled until approval.",
            now,
            now,
            []);
        var secondAttempt = new Dictionary<string, object?>
        {
            ["status"] = "APPROVAL_REQUIRED",
            ["dryRunPassed"] = true,
            ["actualMutationExecuted"] = false
        };
        var approvalRequest = BuildRevisedPatchApprovalRequestReport(
            secondAttempt,
            dryRunResponse,
            "src/App.cs",
            "diff -- src/App.cs",
            staleIndexDisclosureRequired: true);
        var persistedApproval = BuildRevisedPatchApprovalPersistenceReport(
            approvalRequest,
            approved: true,
            approvedByUserId: userId);
        var approvedMutationInput = new Dictionary<string, object?>
        {
            ["approvalRequestId"] = approvalRequest["approvalRequestId"],
            ["mutationAllowed"] = true,
            ["dryRunOnly"] = false,
            ["targetFiles"] = new[] { "src/App.cs" },
            ["manifestId"] = "snap-0123456789abcdef"
        };
        var missingApprovalInput = new Dictionary<string, object?>(approvedMutationInput, StringComparer.Ordinal)
        {
            ["approvalRequestId"] = null
        };
        var wrongApprovalInput = new Dictionary<string, object?>(approvedMutationInput, StringComparer.Ordinal)
        {
            ["approvalRequestId"] = "apr-wrong"
        };
        var allowedGate = BuildRevisedPatchMutationGateReport(persistedApproval, approvedMutationInput);
        var missingGate = BuildRevisedPatchMutationGateReport(persistedApproval, missingApprovalInput);
        var wrongGate = BuildRevisedPatchMutationGateReport(persistedApproval, wrongApprovalInput);

        var ok = approvalRequest.TryGetValue("approvalRequestId", out var approvalRequestId)
            && !string.IsNullOrWhiteSpace(approvalRequestId?.ToString())
            && persistedApproval.TryGetValue("status", out var persistedStatus)
            && string.Equals(persistedStatus?.ToString(), "APPROVED", StringComparison.Ordinal)
            && allowedGate.TryGetValue("status", out var allowedStatus)
            && string.Equals(allowedStatus?.ToString(), "ALLOWED", StringComparison.Ordinal)
            && allowedGate.TryGetValue("mayQueueMutation", out var mayQueueMutation)
            && mayQueueMutation is true
            && missingGate.TryGetValue("status", out var missingStatus)
            && string.Equals(missingStatus?.ToString(), "BLOCKED", StringComparison.Ordinal)
            && wrongGate.TryGetValue("status", out var wrongStatus)
            && string.Equals(wrongStatus?.ToString(), "BLOCKED", StringComparison.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("revised patch approval gate contract self-test failed");
            return 1;
        }

        Console.WriteLine("revised-patch-approval-gate-contract-ok");
        return 0;
    }

    private static Dictionary<string, object?> BuildRevisedPatchApprovalPersistenceReport(
        Dictionary<string, object?> approvalRequest,
        bool approved,
        Guid approvedByUserId)
    {
        var ready = approvalRequest.TryGetValue("status", out var status)
            && string.Equals(status?.ToString(), "READY", StringComparison.Ordinal);
        var approvalRequestId = approvalRequest.TryGetValue("approvalRequestId", out var idValue)
            ? idValue?.ToString()
            : null;
        var persisted = ready && !string.IsNullOrWhiteSpace(approvalRequestId);
        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.revised-patch-approval-persistence.v1",
            ["status"] = !persisted ? "NOT_READY" : approved ? "APPROVED" : "PENDING",
            ["approvalRequestId"] = approvalRequestId,
            ["approvalPersisted"] = persisted,
            ["approved"] = approved && persisted,
            ["approvedByUserId"] = approved && persisted ? approvedByUserId : null,
            ["serverSidePersistenceRequired"] = true,
            ["mutationAllowedBeforeApproval"] = false,
            ["targetFiles"] = approvalRequest.TryGetValue("targetFiles", out var targetFiles) ? targetFiles : null,
            ["nextStep"] = approved && persisted ? "queue_approved_revised_patch_mutation" : "wait_for_user_approval"
        };
    }

    private static Dictionary<string, object?> BuildRevisedPatchMutationGateReport(
        Dictionary<string, object?> persistedApproval,
        Dictionary<string, object?> mutationInput)
    {
        var expectedApprovalId = persistedApproval.TryGetValue("approvalRequestId", out var expectedValue)
            ? expectedValue?.ToString()
            : null;
        var requestApprovalId = mutationInput.TryGetValue("approvalRequestId", out var requestValue)
            ? requestValue?.ToString()
            : null;
        var approved = persistedApproval.TryGetValue("approved", out var approvedValue)
            && approvedValue is true;
        var mutationAllowed = mutationInput.TryGetValue("mutationAllowed", out var mutationAllowedValue)
            && mutationAllowedValue is true;
        var dryRunOnly = mutationInput.TryGetValue("dryRunOnly", out var dryRunOnlyValue)
            && dryRunOnlyValue is true;
        var hasSnapshotManifest = !string.IsNullOrWhiteSpace(
            mutationInput.TryGetValue("manifestId", out var manifestId)
                ? manifestId?.ToString()
                : mutationInput.TryGetValue("snapshotManifestId", out var snapshotManifestId) ? snapshotManifestId?.ToString() : null);
        var approvalMatches = !string.IsNullOrWhiteSpace(expectedApprovalId)
            && string.Equals(expectedApprovalId, requestApprovalId, StringComparison.Ordinal);
        var allowed = approved && approvalMatches && mutationAllowed && !dryRunOnly && hasSnapshotManifest;
        var blockers = new List<string>();
        if (!approved) blockers.Add("approvalNotApproved");
        if (string.IsNullOrWhiteSpace(requestApprovalId)) blockers.Add("approvalRequestIdMissing");
        else if (!approvalMatches) blockers.Add("approvalRequestIdMismatch");
        if (!mutationAllowed) blockers.Add("mutationAllowedNotTrue");
        if (dryRunOnly) blockers.Add("dryRunOnlyMustBeFalse");
        if (!hasSnapshotManifest) blockers.Add("snapshotManifestMissing");

        return new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.revised-patch-mutation-gate.v1",
            ["status"] = allowed ? "ALLOWED" : "BLOCKED",
            ["mayQueueMutation"] = allowed,
            ["approvalRequestId"] = requestApprovalId,
            ["expectedApprovalRequestId"] = expectedApprovalId,
            ["blockers"] = blockers,
            ["serverSideGate"] = true,
            ["localAgentStillRequiresApprovalStateApproved"] = true,
            ["nextStep"] = allowed ? "queue_patch_apply_mutation" : "refuse_revised_patch_mutation"
        };
    }

    private static string? BuildApprovalRequestId(string targetFile, string? revisedDiff, ToolResponse? dryRunResponse)
    {
        if (dryRunResponse is null)
        {
            return null;
        }
        var seed = targetFile.Replace('\\', '/')
            + "\n"
            + (revisedDiff ?? "")
            + "\n"
            + dryRunResponse.RequestId;
        return "apr-" + Sha256Hex(Encoding.UTF8.GetBytes(seed))[..16].ToLowerInvariant();
    }

    private static IEnumerable<string> ExtractDryRunTargetFiles(ToolResponse? dryRunResponse, string fallbackTargetFile)
    {
        var emitted = false;
        if (dryRunResponse?.Output.TryGetValue("files", out var filesValue) == true)
        {
            if (filesValue is IEnumerable<Dictionary<string, object?>> dictionaries)
            {
                foreach (var item in dictionaries)
                {
                    if (item.TryGetValue("path", out var path) && !string.IsNullOrWhiteSpace(path?.ToString()))
                    {
                        emitted = true;
                        yield return path.ToString()!.Replace('\\', '/');
                    }
                }
            }
            else if (filesValue is IEnumerable<object?> objects && filesValue is not string)
            {
                foreach (var item in objects)
                {
                    var path = ExtractPathFromObject(item);
                    if (!string.IsNullOrWhiteSpace(path))
                    {
                        emitted = true;
                        yield return path.Replace('\\', '/');
                    }
                }
            }
            else if (filesValue is JsonElement element && element.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in element.EnumerateArray())
                {
                    if (item.ValueKind == JsonValueKind.Object
                        && item.TryGetProperty("path", out var pathElement)
                        && pathElement.ValueKind == JsonValueKind.String
                        && !string.IsNullOrWhiteSpace(pathElement.GetString()))
                    {
                        emitted = true;
                        yield return pathElement.GetString()!.Replace('\\', '/');
                    }
                }
            }
        }
        if (!emitted && !string.IsNullOrWhiteSpace(fallbackTargetFile))
        {
            yield return fallbackTargetFile.Replace('\\', '/');
        }
    }

    private static string? ExtractPathFromObject(object? item)
    {
        if (item is null)
        {
            return null;
        }
        if (item is Dictionary<string, object?> dictionary
            && dictionary.TryGetValue("path", out var path)
            && !string.IsNullOrWhiteSpace(path?.ToString()))
        {
            return path.ToString();
        }
        if (item is IDictionary<string, object?> objectDictionary
            && objectDictionary.TryGetValue("path", out var objectPath)
            && !string.IsNullOrWhiteSpace(objectPath?.ToString()))
        {
            return objectPath.ToString();
        }
        if (item is JsonElement element
            && element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty("path", out var pathElement)
            && pathElement.ValueKind == JsonValueKind.String
            && !string.IsNullOrWhiteSpace(pathElement.GetString()))
        {
            return pathElement.GetString();
        }
        return null;
    }

    private static List<string> DetectPatchTestDiagnosticSignals(
        ToolResponse commandResponse,
        string stdout,
        string stderr,
        bool timedOut,
        bool truncated)
    {
        var signals = new List<string>();
        if (commandResponse.Status == "SUCCEEDED")
        {
            signals.Add("command_succeeded");
        }
        if (string.Equals(commandResponse.FailureCode, "TEST_FAILED", StringComparison.Ordinal))
        {
            signals.Add("allowlisted_test_failed");
        }
        if (timedOut)
        {
            signals.Add("command_timed_out");
        }
        if (truncated)
        {
            signals.Add("output_truncated");
        }
        var combined = (stdout + "\n" + stderr).ToLowerInvariant();
        if (combined.Contains("expected", StringComparison.Ordinal) || combined.Contains("assert", StringComparison.Ordinal))
        {
            signals.Add("assertion_failure_text");
        }
        if (combined.Contains("exception", StringComparison.Ordinal) || combined.Contains("stack trace", StringComparison.Ordinal))
        {
            signals.Add("exception_text");
        }
        if (signals.Count == 0)
        {
            signals.Add("no_structured_signal");
        }
        return signals.Distinct(StringComparer.Ordinal).ToList();
    }

    private static object? ToolOutputValue(ToolResponse response, string key) =>
        response.Output.TryGetValue(key, out var value) ? value : null;

    private static string? ToolOutputString(ToolResponse response, string key) =>
        ToolOutputValue(response, key)?.ToString();

    private static bool? ToolOutputBool(ToolResponse response, string key)
    {
        var value = ToolOutputValue(response, key);
        return value switch
        {
            bool boolean => boolean,
            JsonElement { ValueKind: JsonValueKind.True } => true,
            JsonElement { ValueKind: JsonValueKind.False } => false,
            string text when bool.TryParse(text, out var parsed) => parsed,
            _ => null
        };
    }

    private static string TruncateForReport(string value, int maxChars) =>
        value.Length <= maxChars ? value : value[..maxChars];

    private static IEnumerable<string> ExtractSelectedFilePaths(Dictionary<string, object?> candidateSelection)
    {
        if (!candidateSelection.TryGetValue("selectedFiles", out var value) || value is null)
        {
            yield break;
        }
        if (value is IEnumerable<Dictionary<string, object?>> dictionaries)
        {
            foreach (var item in dictionaries)
            {
                if (item.TryGetValue("path", out var path) && !string.IsNullOrWhiteSpace(path?.ToString()))
                {
                    yield return path.ToString()!.Replace('\\', '/');
                }
            }
            yield break;
        }
        if (value is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Object
                    && item.TryGetProperty("path", out var pathElement)
                    && pathElement.ValueKind == JsonValueKind.String
                    && !string.IsNullOrWhiteSpace(pathElement.GetString()))
                {
                    yield return pathElement.GetString()!.Replace('\\', '/');
                }
            }
        }
    }

    private static IEnumerable<string> ExtractPathsFromToolOutput(Dictionary<string, object?> output, string key)
    {
        if (!output.TryGetValue(key, out var value) || value is null)
        {
            yield break;
        }

        if (value is IEnumerable<Dictionary<string, object?>> dictionaries)
        {
            foreach (var item in dictionaries)
            {
                if (item.TryGetValue("path", out var path) && !string.IsNullOrWhiteSpace(path?.ToString()))
                {
                    yield return path.ToString()!.Replace('\\', '/');
                }
            }
            yield break;
        }

        if (value is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Object
                    && item.TryGetProperty("path", out var pathElement)
                    && pathElement.ValueKind == JsonValueKind.String
                    && !string.IsNullOrWhiteSpace(pathElement.GetString()))
                {
                    yield return pathElement.GetString()!.Replace('\\', '/');
                }
            }
        }
    }

    private static int CountItems(Dictionary<string, object?> output, string key)
    {
        if (!output.TryGetValue(key, out var value) || value is null)
        {
            return 0;
        }
        if (value is ICollection<Dictionary<string, object?>> dictionaries)
        {
            return dictionaries.Count;
        }
        if (value is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            return element.GetArrayLength();
        }
        return 0;
    }

    private static bool LooksLikeSourceFile(string path)
    {
        var extension = Path.GetExtension(path);
        return extension is ".cs" or ".java" or ".js" or ".jsx" or ".ts" or ".tsx" or ".py" or ".go" or ".rs" or ".kt" or ".md";
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
