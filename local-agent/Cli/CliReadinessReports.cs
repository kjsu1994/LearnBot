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

    private CliM8ProductizationReport BuildCliM8ProductizationReport()
    {
        var status = BuildCliStatusReport();
        var doctor = BuildCliDoctorReport();
        var lifecycleReady = status.ConfigExists && status.LogPath.Length > 0 && status.StatePath.Length > 0;
        var setupReady = status.Configured && status.ApprovedWorkspaceCount > 0;
        var serviceReady = File.Exists(status.ExecutablePath ?? "") && status.ConfigExists && status.ApprovedWorkspaceCount > 0;
        var items = new List<CliM8ProductizationItem>
        {
            new("guidedSetup", setupReady, setupReady ? "paired config and approved workspace are present" : "run setup-plan or pair-from-web-token first"),
            new("backgroundLifecycle", lifecycleReady, lifecycleReady ? "status/log/state paths are available" : "local lifecycle paths are not ready"),
            new("doctorUx", doctor.Checks.Count > 0, "learnbot doctor returns structured checks without token secrets"),
            new("logsUx", status.LogExists, status.LogExists ? status.LogPath : "log file will appear after agent start"),
            new("windowsServicePreview", serviceReady, serviceReady ? "service-plan can preview install/start/stop commands" : "install executable, pair, and approve a workspace before service-plan"),
            new("codexLikeCommands", true, "status, doctor, login/session preview, agent start/status/logs, workspace, file, git, fix/review preview, and open are available"),
            new("signedInstaller", false, "signed MSI/EXE and auto-update remain future work"),
            new("autoUpdate", false, "auto-update remains future work")
        };
        return new CliM8ProductizationReport(
            Schema: "learnbot.local-agent.m8-productization-status.v1",
            CommandName: "learnbot",
            Version: Version,
            ReadyForInternalPilot: setupReady && doctor.Ready && lifecycleReady,
            ReadyForMatureDistribution: false,
            M8WorkEnabled: false,
            ServiceCommandExecutionEnabled: false,
            InstallerSigningEnabled: false,
            AutoUpdateEnabled: false,
            Status: status,
            DoctorReady: doctor.Ready,
            Items: items,
            NextRecommendedCommand: setupReady
                ? status.Running ? "learnbot agent status" : "learnbot agent start --transport auto"
                : "learnbot doctor",
            NextCommands: BuildM8NextCommands(status));
    }

    private CliM8DoctorReport BuildCliM8DoctorReport()
    {
        var productization = BuildCliM8ProductizationReport();
        var status = productization.Status;
        var setupReady = status.Configured && status.ApprovedWorkspaceCount > 0;
        var lifecycleReady = status.ConfigExists && !string.IsNullOrWhiteSpace(status.LogPath) && !string.IsNullOrWhiteSpace(status.StatePath);
        var servicePreviewReady = File.Exists(status.ExecutablePath ?? "") && status.ConfigExists && status.ApprovedWorkspaceCount > 0;
        var sections = new List<CliM8DoctorSection>
        {
            new("setup", setupReady ? "READY" : "NEEDS_ACTION", setupReady, setupReady ? "paired config and approved workspace are present" : "pair from a web token and approve a workspace"),
            new("lifecycle", lifecycleReady ? "READY" : "NEEDS_ACTION", lifecycleReady, lifecycleReady ? "config, state, and log paths are known" : "run status or setup before starting the background helper"),
            new("runtime", status.Running ? "RUNNING" : "STOPPED", status.Running, status.Running ? "Local Agent process appears active" : "Local Agent is not running"),
            new("logs", status.LogExists ? "READY" : "PENDING", status.LogExists, status.LogExists ? status.LogPath : "log file will appear after the agent starts"),
            new("servicePreview", servicePreviewReady ? "READY" : "NEEDS_ACTION", servicePreviewReady, servicePreviewReady ? "service-plan can preview commands without executing them" : "install executable, pair, and approve a workspace before service preview is ready"),
            new("distribution", "NOT_READY", false, "signed installer and auto-update are disabled")
        };
        return new CliM8DoctorReport(
            Schema: "learnbot.local-agent.m8-doctor.v1",
            CommandName: "learnbot",
            Version: Version,
            ReadyForInternalPilot: productization.ReadyForInternalPilot,
            ReadyForMatureDistribution: false,
            M8WorkEnabled: false,
            ServiceCommandExecutionEnabled: false,
            InstallerSigningEnabled: false,
            AutoUpdateEnabled: false,
            TokenSecretPrinted: false,
            ProductizationStatus: productization,
            Sections: sections,
            NextCommands: productization.NextCommands);
    }

    private CliWebLoginPlanReport BuildCliWebLoginPlanReport(string? loginId, string? email, bool rememberLogin)
    {
        var config = LoadConfigOrDefault();
        var serverUrl = (config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var identifierProvided = !string.IsNullOrWhiteSpace(loginId) || !string.IsNullOrWhiteSpace(email);
        var blockers = new List<string>
        {
            "CLI web login execution is disabled until a dedicated device-code or cookie session bridge is implemented."
        };
        if (!identifierProvided)
        {
            blockers.Add("login id or email is required for a future login execution.");
        }
        return new CliWebLoginPlanReport(
            Schema: "learnbot.local-agent.web-login-plan.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: "DISABLED_PREVIEW",
            ServerUrl: serverUrl,
            Method: "POST",
            Endpoint: "/api/auth/login",
            AbsoluteEndpointPreview: serverUrl + "/api/auth/login",
            DeviceSessionPlanEndpoint: "/api/auth/cli-device-session/plan",
            AbsoluteDeviceSessionPlanEndpointPreview: serverUrl + "/api/auth/cli-device-session/plan",
            DeviceSessionCreatePlanEndpoint: "/api/auth/cli-device-session/create/plan",
            AbsoluteDeviceSessionCreatePlanEndpointPreview: serverUrl + "/api/auth/cli-device-session/create/plan",
            IdentifierProvided: identifierProvided,
            RememberLogin: rememberLogin,
            PasswordCollected: false,
            NetworkCallEnabled: false,
            LoginExecutionEnabled: false,
            SessionStorageEnabled: false,
            CookiePersistenceEnabled: false,
            LocalAgentTokenUsed: false,
            TokenSecretPrinted: false,
            BodyPreview: new Dictionary<string, object?>
            {
                ["loginId"] = string.IsNullOrWhiteSpace(loginId) ? null : "<login-id>",
                ["email"] = string.IsNullOrWhiteSpace(email) ? null : "<email>",
                ["password"] = "<not-collected>",
                ["rememberLogin"] = rememberLogin
            },
            FollowUpCommands: [
                "POST /api/auth/cli-device-session/plan",
                "learnbot session create-plan",
                "POST /api/auth/cli-device-session/create/plan",
                "learnbot session status",
                "learnbot fix --goal \"<goal>\" --workspace <workspace> --repository-id <repository-id> --server-plan"
            ],
            Blockers: blockers,
            Reason: "Backend login currently sets HttpOnly auth cookies; CLI session persistence needs a dedicated bridge before password collection or token storage is enabled.");
    }

    private CliWebSessionStatusReport BuildCliWebSessionStatusReport()
    {
        var webToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        var tokenPresent = !string.IsNullOrWhiteSpace(webToken);
        var artifact = BuildCliWebSessionArtifactValidationReport();
        var secretProvider = BuildCliWebSessionSecretProviderPlanReport();
        var storedSessionAuth = BuildCliWebSessionStoredSessionAuthReadinessReport();
        return new CliWebSessionStatusReport(
            Schema: "learnbot.local-agent.web-session-status.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: tokenPresent ? "ENV_TOKEN_AVAILABLE" : "NO_WEB_SESSION",
            ServerUrl: (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/'),
            SessionPath: WebSessionPath(),
            ArtifactValidation: artifact,
            SecretProviderPlan: secretProvider,
            StoredSessionAuthReadiness: storedSessionAuth,
            ClaimPlanEndpoint: "/api/auth/cli-device-session/claim/plan",
            AbsoluteClaimPlanEndpointPreview: (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/') + "/api/auth/cli-device-session/claim/plan",
            StoredSessionExists: artifact.FileExists,
            StoredSessionReadable: false,
            StoredSessionTokenLoaded: false,
            ClaimPollingEnabled: false,
            EnvironmentWebTokenPresent: tokenPresent,
            EnvironmentWebTokenFingerprint: tokenPresent ? TokenFingerprint(webToken) : null,
            UsableForServerPlanFetch: tokenPresent,
            LocalSessionArtifactWriteEnabled: false,
            LocalSessionArtifactEncryptedRequired: true,
            LocalAgentTokenUsed: false,
            TokenSecretPrinted: false,
            LoginExecutionEnabled: false,
            SessionStorageEnabled: false,
            NextCommand: tokenPresent
                ? "learnbot fix --goal \"<goal>\" --workspace <workspace> --repository-id <repository-id> --server-plan"
                : "learnbot login --login-id <login-id>");
    }

    private CliWebSessionSecretProviderPlanReport BuildCliWebSessionSecretProviderPlanReport()
    {
        var windowsCandidate = OperatingSystem.IsWindows();
        return new CliWebSessionSecretProviderPlanReport(
            Schema: "learnbot.local-agent.web-session-secret-provider-plan.v1",
            Status: "PRODUCTION_PROVIDER_DISABLED_PREVIEW",
            Provider: "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE",
            WindowsDpapiCandidate: windowsCandidate,
            OsSecretStoreRequired: true,
            ProviderProbeEnabled: false,
            ManualNoSecretProbeAvailable: windowsCandidate,
            ProductionEncryptionEnabled: false,
            ProductionDecryptionEnabled: false,
            ProductionStoredSessionLoadingEnabled: false,
            TestOnlyProviderAcceptedForProduction: false,
            PlaintextTokenSerializationAllowed: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            FollowUpCommand: windowsCandidate
                ? "learnbot session secret-provider-probe"
                : "learnbot session artifact-reader-test-validate --test-only",
            Blockers: [
                windowsCandidate
                    ? "production DPAPI/OS secret-store provider is disabled until no-secret probe evidence is promoted into guarded encrypt, decrypt, refresh, and stored-session loading."
                    : "production OS secret-store provider is disabled and this platform still needs a supported provider abstraction."
            ],
            Reason: "This plan pins the production secret-store boundary before stored web-session loading is enabled. Test-only AES-GCM artifacts stay isolated from production.");
    }

    private CliWebSessionArtifactValidationReport BuildCliWebSessionArtifactValidationReport()
    {
        var path = WebSessionPath();
        var fileExists = File.Exists(path);
        return new CliWebSessionArtifactValidationReport(
            Schema: "learnbot.local-agent.web-session-artifact-validation.v1",
            Status: fileExists ? "VALIDATION_DISABLED_FILE_PRESENT" : "MISSING",
            Path: path,
            FileExists: fileExists,
            ReadAttempted: false,
            JsonParsed: false,
            SchemaValidated: false,
            Encrypted: false,
            EncryptionRequired: true,
            AccessTokenLoaded: false,
            RefreshTokenLoaded: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            ProductionCryptoPreviewRequirement: BuildCliWebSessionArtifactCryptoPreviewRequirement(),
            RequiredSchema: "learnbot.local-agent.web-session-artifact.v1",
            RequiredFields: [
                "schema",
                "serverUrl",
                "encryptedAccessToken",
                "encryptedRefreshToken",
                "expiresAt",
                "refreshExpiresAt",
                "createdAt"
            ],
            Blockers: fileExists
                ? ["stored web-session artifact validation is disabled until encrypted read/decrypt support is implemented"]
                : ["stored web-session artifact is missing"],
            Reason: "This validator preview does not read or decrypt the web-session artifact. It fixes the future encrypted artifact contract without loading token secrets.");
    }

    private static CliWebSessionArtifactCryptoPreviewRequirement BuildCliWebSessionArtifactCryptoPreviewRequirement() =>
        new(
            Schema: "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1",
            RequiredBeforeProductionStoredSessionLoading: true,
            ProofCommand: "learnbot session artifact-production-crypto-preview --preview-only",
            ProofSchema: "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1",
            Provider: "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE",
            PreviewOnlyRequired: true,
            AutoRunEnabled: false,
            ArtifactWriteEnabled: false,
            ArtifactReadEnabled: false,
            StoredSessionLoadingEnabled: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            Reason: "Stored web-session loading requires a successful non-writing production crypto preview proof before any future guarded artifact writer or reader can be enabled.");

    private CliWebSessionServerPlanReadinessReport BuildCliWebSessionServerPlanReadinessReport()
    {
        var session = BuildCliWebSessionStatusReport();
        var tokenPresent = session.EnvironmentWebTokenPresent;
        return new CliWebSessionServerPlanReadinessReport(
            Schema: "learnbot.local-agent.web-session-server-plan-readiness.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: tokenPresent ? "ENV_TOKEN_FALLBACK_READY" : "BLOCKED_NO_WEB_SESSION",
            ServerUrl: session.ServerUrl,
            SessionPath: session.SessionPath,
            ArtifactValidation: session.ArtifactValidation,
            SecretProviderPlan: session.SecretProviderPlan,
            StoredSessionAuthReadiness: session.StoredSessionAuthReadiness,
            StoredSessionExists: session.StoredSessionExists,
            StoredSessionReadable: false,
            StoredSessionTokenLoaded: false,
            StoredSessionTokenFingerprint: null,
            EnvironmentWebTokenPresent: tokenPresent,
            EnvironmentWebTokenFingerprint: session.EnvironmentWebTokenFingerprint,
            EnvironmentWebTokenUsableForServerPlanFetch: tokenPresent,
            StoredSessionUsableForServerPlanFetch: false,
            ServerPlanFetchFromStoredSessionEnabled: false,
            LocalSessionArtifactWriteEnabled: false,
            LocalSessionArtifactEncryptedRequired: true,
            LocalAgentTokenUsed: false,
            TokenSecretPrinted: false,
            RequestCreated: false,
            MutationAllowed: false,
            FollowUpCommand: tokenPresent
                ? "learnbot fix --goal \"<goal>\" --workspace <workspace> --repository-id <repository-id> --server-plan"
                : "learnbot session create-plan",
            Blockers: tokenPresent
                ? ["stored web-session artifact loading is disabled; using LEARNBOT_WEB_TOKEN fallback only"]
                : ["no LEARNBOT_WEB_TOKEN is present and stored web-session artifact loading is disabled"],
            Reason: "This readiness bridge keeps authenticated server-plan fetch separate from Local Agent pairing. Stored session loading stays disabled until device-code claim, encrypted artifact storage, and refresh handling are implemented.");
    }

    private CliCodexCommandPreviewReport BuildCliCodexCommandPreviewReport(
        string command,
        string goal,
        string workspacePath,
        string? repositoryIdText = null,
        string? spaceIdText = null,
        int maxSteps = 6)
    {
        var config = LoadConfigOrDefault();
        var fullWorkspacePath = Path.GetFullPath(string.IsNullOrWhiteSpace(workspacePath) ? Environment.CurrentDirectory : workspacePath);
        var matchedWorkspace = config.Workspaces
            .Where(workspace => workspace.Approved)
            .FirstOrDefault(workspace => PathEquals(workspace.Path, fullWorkspacePath));
        var paired = !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty;
        var workspaceReady = matchedWorkspace is not null;
        var goalReady = !string.IsNullOrWhiteSpace(goal);
        var repositoryReady = Guid.TryParse(repositoryIdText, out var repositoryId);
        var spaceReady = string.IsNullOrWhiteSpace(spaceIdText) || Guid.TryParse(spaceIdText, out _);
        var parsedSpaceId = Guid.TryParse(spaceIdText, out var spaceId) ? spaceId : (Guid?)null;
        var blockers = new List<string>();
        if (!paired)
        {
            blockers.Add("agent is not paired");
        }
        if (!workspaceReady)
        {
            blockers.Add("workspace is not registered and approved");
        }
        if (!goalReady)
        {
            blockers.Add("goal is required");
        }
        var submissionPlanBlockers = new List<string>(blockers);
        if (!repositoryReady)
        {
            submissionPlanBlockers.Add("repository id is required for server handoff preview");
        }
        if (!spaceReady)
        {
            submissionPlanBlockers.Add("space id must be a valid UUID when provided");
        }
        var submissionPlan = new CliCodexServerSubmissionPlan(
            Schema: "learnbot.local-agent.codex-server-submission-plan.v1",
            Method: "POST",
            Endpoint: "/api/code-agent/loop/submission-plan",
            AbsoluteEndpointPreview: $"{(config.ServerUrl ?? "http://localhost:8083").TrimEnd('/')}/api/code-agent/loop/submission-plan",
            ReadyForDisabledPlan: submissionPlanBlockers.Count == 0,
            Enabled: false,
            NetworkCallEnabled: false,
            RequestCreationEnabled: false,
            ServerConversationCreationEnabled: false,
            LoopPreviewExecutionEnabled: false,
            RequiresAuthenticatedWebSession: true,
            RequiresRepositoryAuthorization: true,
            RepositoryId: repositoryReady ? repositoryId : null,
            SpaceId: parsedSpaceId,
            AgentId: paired ? config.AgentId : null,
            WorkspaceId: matchedWorkspace?.WorkspaceId,
            BodyPreview: new Dictionary<string, object?>
            {
                ["repositoryId"] = repositoryReady ? repositoryId : "<repository-id>",
                ["spaceId"] = parsedSpaceId,
                ["instruction"] = goal,
                ["maxSteps"] = Math.Clamp(maxSteps, 1, 20)
            },
            FollowUpEndpoints: [
                "POST /api/code-agent/loop/runner/preview",
                "POST /api/code-agent/loop/runner/select-tool-preview",
                "POST /api/code-agent/loop/runner/enqueue-selected-read-only"
            ],
            Blockers: submissionPlanBlockers,
            Reason: "CLI goal submission stays disabled until web authentication, repository authorization, and review surfaces are proven.");

        return new CliCodexCommandPreviewReport(
            Schema: "learnbot.local-agent.codex-command-preview.v1",
            CommandName: "learnbot",
            Command: command,
            Version: Version,
            Goal: goal,
            WorkspacePath: fullWorkspacePath,
            WorkspaceMatched: workspaceReady,
            WorkspaceId: matchedWorkspace?.WorkspaceId,
            ReadyForPreview: paired && workspaceReady && goalReady,
            Status: blockers.Count == 0 ? "READY_PREVIEW" : "BLOCKED_PREVIEW",
            Blockers: blockers,
            ServerSubmissionPlan: submissionPlan,
            OneCyclePreview: BuildCliCodexOneCyclePreview(command, goal, fullWorkspacePath, matchedWorkspace, repositoryReady ? repositoryId : null, maxSteps),
            PlannedLoop: [
                "submit goal to web/server code-agent loop",
                "discover candidate files through approved Local Agent workspace tools",
                "read selected files before any patch proposal",
                "prepare plan, patch, dry-run, approval, apply/test, retry, final report, and freshness update through the existing guarded loop"
            ],
            SubmitEnabled: false,
            ReadOnlyPreview: true,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            ApprovalBypassAllowed: false,
            TestExecutionEnabled: false,
            RollbackExecutionEnabled: false,
            FinalPublicationEnabled: false,
            PartialReindexEnabled: false,
            TokenSecretPrinted: false,
            NextCommand: blockers.Count == 0
                ? "learnbot open"
                : !paired
                    ? "learnbot doctor"
                    : !workspaceReady
                        ? "learnbot workspace add <workspace>"
                        : $"learnbot {command} --goal \"<goal>\" --workspace \"{fullWorkspacePath}\"");
    }

    private CliCodexOneCyclePreview BuildCliCodexOneCyclePreview(
        string command,
        string goal,
        string workspacePath,
        AgentWorkspace? workspace,
        Guid? repositoryId,
        int maxSteps)
    {
        var config = LoadConfigOrDefault();
        var paired = !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty;
        var workspaceReady = workspace is not null;
        var goalReady = !string.IsNullOrWhiteSpace(goal);
        var repositoryReady = repositoryId.HasValue;
        var readyForReadOnlyPreview = paired && workspaceReady && goalReady && repositoryReady;
        return new CliCodexOneCyclePreview(
            Schema: "learnbot.local-agent.codex-one-cycle-preview.v1",
            Command: command,
            Goal: goal,
            WorkspacePath: workspacePath,
            WorkspaceId: workspace?.WorkspaceId,
            RepositoryId: repositoryId,
            MaxSteps: Math.Clamp(maxSteps, 1, 20),
            Status: readyForReadOnlyPreview ? "READY_READ_ONLY_LOOP_PREVIEW" : "BLOCKED_PREVIEW",
            ReadyForReadOnlyToolLoop: readyForReadOnlyPreview,
            ReadyForPatchTestLoop: false,
            ReadyForFinalReport: false,
            ReadyForPartialReindex: false,
            LocalAgentExecutionTarget: "USER_LOCAL_AGENT",
            ServerPlanningRequired: true,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            ApprovalRequiredBeforeMutation: true,
            ApprovalBypassAllowed: false,
            TestExecutionEnabled: false,
            RollbackExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            TokenSecretPrinted: false,
            FileDiscoveryReadPlan: BuildCliCodexFileDiscoveryReadPlan(goal, workspacePath, workspace, repositoryId, readyForReadOnlyPreview),
            Stages: [
                new("goal-input", "READY", true, goalReady, false, false, false, "accept the user goal from CLI and hand it to the server loop plan"),
                new("workspace-discovery", workspaceReady ? "READY" : "BLOCKED", true, workspaceReady, false, false, false, "resolve an approved Local Agent workspace before touching files"),
                new("file-discovery", readyForReadOnlyPreview ? "READY_PREVIEW" : "BLOCKED", true, readyForReadOnlyPreview, false, false, false, "use approved read-only Local Agent tools to list and search candidate files"),
                new("file-read", readyForReadOnlyPreview ? "READY_PREVIEW" : "BLOCKED", true, readyForReadOnlyPreview, false, false, false, "read selected files through bounded workspace tools before proposing a patch"),
                new("plan", readyForReadOnlyPreview ? "READY_PREVIEW" : "BLOCKED", true, readyForReadOnlyPreview, false, false, false, "server plans the next step from observations and repository context"),
                new("patch-dry-run", "DISABLED_UNTIL_SERVER_HANDOFF", false, false, false, true, false, "prepare and dry-run patches only after authenticated server handoff and review setup"),
                new("approval", "REQUIRED_BEFORE_MUTATION", false, false, false, true, false, "explicit user approval is required before any write, test, or rollback side effect"),
                new("apply-and-test", "DISABLED_UNTIL_APPROVED_RELEASE", false, false, false, true, false, "apply changes and run allowlisted tests only after the guarded release path is enabled"),
                new("failure-analysis-retry", "DISABLED_UNTIL_TEST_LOOP", false, false, false, true, false, "analyze failing logs and retry only through the guarded loop"),
                new("final-report", "DISABLED_UNTIL_LOOP_COMPLETION", false, false, false, false, false, "publish a final report after loop completion and evidence aggregation"),
                new("rag-freshness-update", "DISABLED_UNTIL_MUTATION_RESULT", false, false, false, false, false, "enqueue partial reindex only after accepted local file changes")
            ],
            Blockers: BuildOneCycleBlockers(paired, workspaceReady, goalReady, repositoryReady),
            NextCommand: readyForReadOnlyPreview
                ? $"learnbot {command} --goal \"{goal}\" --workspace \"{workspacePath}\" --repository-id {repositoryId} --server-plan"
                : !paired
                    ? "learnbot doctor"
                    : !workspaceReady
                        ? "learnbot workspace add <workspace>"
                        : !repositoryReady
                            ? $"learnbot {command} --goal \"{goal}\" --workspace \"{workspacePath}\" --repository-id <repository-id>"
                            : $"learnbot {command} --goal \"<goal>\" --workspace \"{workspacePath}\"",
            Reason: "This is the user-perceived one-cycle contract for Codex-like local work. It exposes the full intended loop while keeping request creation, mutation, tests, final publication, and partial reindex disabled until authenticated server handoff and approval gates are real.");
    }

    private static CliCodexFileDiscoveryReadPlan BuildCliCodexFileDiscoveryReadPlan(
        string goal,
        string workspacePath,
        AgentWorkspace? workspace,
        Guid? repositoryId,
        bool readyForReadOnlyPreview)
    {
        var queryHints = GoalQueryHints(goal);
        return new CliCodexFileDiscoveryReadPlan(
            Schema: "learnbot.local-agent.codex-file-discovery-read-plan.v1",
            Status: readyForReadOnlyPreview ? "READY_DRY_RUN_PLAN" : "BLOCKED_PREVIEW",
            WorkspacePath: workspacePath,
            WorkspaceId: workspace?.WorkspaceId,
            RepositoryId: repositoryId,
            DryRunOnly: true,
            PlanPrepared: readyForReadOnlyPreview,
            ToolExecutionEnabled: false,
            FileDiscoveryPlanEnabled: readyForReadOnlyPreview,
            FileReadPlanEnabled: readyForReadOnlyPreview,
            FileTreeExecutionEnabled: false,
            FileSearchExecutionEnabled: false,
            FileReadExecutionEnabled: false,
            GitStatusExecutionEnabled: false,
            FileContentRead: false,
            FileBytesLoaded: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            TokenSecretPrinted: false,
            CandidateTools: [
                "file.tree",
                "file.search",
                "file.read",
                "git.status"
            ],
            PathHints: [
                ".",
                "src",
                "test",
                "tests",
                "frontend/src",
                "backend/src"
            ],
            QueryHints: queryHints,
            MaxCandidateFiles: 12,
            MaxReadFiles: 6,
            MaxReadBytesPerFile: DefaultMaxReadBytes,
            RequestEnvelopePreviews: BuildCliCodexReadOnlyRequestEnvelopePreviews(workspace?.WorkspaceId, queryHints),
            PlannedSteps: [
                new("tree", "file.tree", ".", "inspect top-level and common source/test directories before selecting files", false, false),
                new("search", "file.search", string.Join(" ", queryHints), "search for goal-derived terms across approved workspace files", false, false),
                new("status", "git.status", null, "capture repository branch and dirty state as read-only context", false, false),
                new("read", "file.read", "<selected-candidate-files>", "read only bounded selected files after discovery narrows candidates", false, false)
            ],
            Blockers: readyForReadOnlyPreview
                ? ["tool execution remains disabled in this dry-run plan"]
                : ["pairing, approved workspace, goal, and repository id are required before the read-only discovery/read plan is ready"],
            Reason: "This is a dry-run-only plan for the first local Codex-style read loop. It names bounded read-only tools and search hints without listing files, reading contents, creating requests, or mutating code.");
    }

    private static IReadOnlyList<CliCodexReadOnlyRequestEnvelopePreview> BuildCliCodexReadOnlyRequestEnvelopePreviews(
        Guid? workspaceId,
        IReadOnlyList<string> queryHints)
    {
        object workspaceIdValue = workspaceId.HasValue ? workspaceId.Value : "<workspace-id>";
        return
        [
            new(
                Schema: "learnbot.local-agent.codex-read-only-request-envelope-preview.v1",
                ToolName: "workspace.tree",
                ExecutionTarget: "USER_LOCAL_AGENT",
                ApprovalState: "NOT_REQUIRED",
                RequestCreationEnabled: false,
                EnqueueEnabled: false,
                Claimable: false,
                ExecutionEnabled: false,
                SideEffectful: false,
                RequiresApproval: false,
                MutationAllowed: false,
                FileContentRead: false,
                TokenSecretPrinted: false,
                InputPreview: new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceIdValue,
                    ["path"] = ".",
                    ["maxEntries"] = DefaultMaxTreeEntries,
                    ["maxDepth"] = DefaultMaxTreeDepth
                },
                Reason: "Preview only. This would inspect the approved workspace tree before file selection, but no request is created or claimed."),
            new(
                Schema: "learnbot.local-agent.codex-read-only-request-envelope-preview.v1",
                ToolName: "workspace.search",
                ExecutionTarget: "USER_LOCAL_AGENT",
                ApprovalState: "NOT_REQUIRED",
                RequestCreationEnabled: false,
                EnqueueEnabled: false,
                Claimable: false,
                ExecutionEnabled: false,
                SideEffectful: false,
                RequiresApproval: false,
                MutationAllowed: false,
                FileContentRead: false,
                TokenSecretPrinted: false,
                InputPreview: new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceIdValue,
                    ["query"] = string.Join(" ", queryHints),
                    ["path"] = ".",
                    ["maxMatches"] = DefaultMaxSearchMatches,
                    ["maxFiles"] = DefaultMaxSearchFiles,
                    ["maxBytesPerFile"] = DefaultMaxSearchFileBytes
                },
                Reason: "Preview only. This would search approved workspace text for goal-derived terms without making mutation claimable."),
            new(
                Schema: "learnbot.local-agent.codex-read-only-request-envelope-preview.v1",
                ToolName: "git.status",
                ExecutionTarget: "USER_LOCAL_AGENT",
                ApprovalState: "NOT_REQUIRED",
                RequestCreationEnabled: false,
                EnqueueEnabled: false,
                Claimable: false,
                ExecutionEnabled: false,
                SideEffectful: false,
                RequiresApproval: false,
                MutationAllowed: false,
                FileContentRead: false,
                TokenSecretPrinted: false,
                InputPreview: new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceIdValue
                },
                Reason: "Preview only. This would capture repository status and identity as read-only context.")
        ];
    }

    private static IReadOnlyList<string> GoalQueryHints(string goal)
    {
        var hints = goal
            .Split([' ', '\t', '\r', '\n', '.', ',', ';', ':', '/', '\\', '-', '_', '"', '\''], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(term => term.Length >= 3)
            .Select(term => term.Length > 40 ? term[..40] : term)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(8)
            .ToList();
        return hints.Count == 0 ? ["TODO", "test", "error"] : hints;
    }

    private static IReadOnlyList<string> BuildOneCycleBlockers(bool paired, bool workspaceReady, bool goalReady, bool repositoryReady)
    {
        var blockers = new List<string>();
        if (!paired)
        {
            blockers.Add("agent is not paired");
        }
        if (!workspaceReady)
        {
            blockers.Add("workspace is not registered and approved");
        }
        if (!goalReady)
        {
            blockers.Add("goal is required");
        }
        if (!repositoryReady)
        {
            blockers.Add("repository id is required for server handoff preview");
        }
        blockers.Add("authenticated server handoff is required before request creation");
        blockers.Add("patch/test/final-report/partial-reindex execution remains disabled until explicit approval and release gates are implemented");
        return blockers;
    }

    private static IReadOnlyList<CliM8NextCommand> BuildM8NextCommands(CliStatusReport status)
    {
        if (!status.Configured)
        {
            return
            [
                new("diagnose", "learnbot doctor", true, "show missing pairing/workspace checks"),
                new("pair", "scripts/local-agent.ps1 -Action pair-from-web-token -PairingAgentId <agent-id> -PairingToken <pairing-token> -WorkspacePath <workspace>", false, "requires a web-issued pairing token; placeholder secrets only"),
                new("verify", "learnbot m8 status", true, "re-run readiness after pairing")
            ];
        }
        if (status.ApprovedWorkspaceCount == 0)
        {
            return
            [
                new("diagnose", "learnbot doctor", true, "show missing workspace checks"),
                new("workspace", "learnbot workspace add <workspace>", true, "register an approved local workspace root"),
                new("verify", "learnbot m8 status", true, "re-run readiness after workspace registration")
            ];
        }
        if (!status.Running)
        {
            return
            [
                new("diagnose", "learnbot doctor", true, "confirm paired config and workspace readiness"),
                new("start", "scripts/local-agent.ps1 -Action m8-lifecycle-run -Transport auto", true, "guard background start with status/log observations and duplicate-start prevention"),
                new("verify", "learnbot m8 status", true, "confirm running state")
            ];
        }
        return
        [
            new("status", "learnbot agent status", true, "confirm active transport and workspace count"),
            new("logs", "learnbot agent logs --tail 80", true, "inspect recent Local Agent activity"),
            new("servicePreview", "scripts/local-agent.ps1 -Action service-plan", true, "preview Windows Service readiness without executing service commands"),
            new("open", "learnbot open", true, "open the web review surface")
        ];
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

internal sealed record CliM8ProductizationReport(
    string Schema,
    string CommandName,
    string Version,
    bool ReadyForInternalPilot,
    bool ReadyForMatureDistribution,
    bool M8WorkEnabled,
    bool ServiceCommandExecutionEnabled,
    bool InstallerSigningEnabled,
    bool AutoUpdateEnabled,
    CliStatusReport Status,
    bool DoctorReady,
    IReadOnlyList<CliM8ProductizationItem> Items,
    string NextRecommendedCommand,
    IReadOnlyList<CliM8NextCommand> NextCommands);

internal sealed record CliM8ProductizationItem(string Name, bool Ready, string Message);

internal sealed record CliM8NextCommand(string Phase, string Command, bool Enabled, string Reason);

internal sealed record CliM8DoctorReport(
    string Schema,
    string CommandName,
    string Version,
    bool ReadyForInternalPilot,
    bool ReadyForMatureDistribution,
    bool M8WorkEnabled,
    bool ServiceCommandExecutionEnabled,
    bool InstallerSigningEnabled,
    bool AutoUpdateEnabled,
    bool TokenSecretPrinted,
    CliM8ProductizationReport ProductizationStatus,
    IReadOnlyList<CliM8DoctorSection> Sections,
    IReadOnlyList<CliM8NextCommand> NextCommands);

internal sealed record CliM8DoctorSection(string Name, string Status, bool Ready, string Message);

internal sealed record CliWebLoginPlanReport(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string ServerUrl,
    string Method,
    string Endpoint,
    string AbsoluteEndpointPreview,
    string DeviceSessionPlanEndpoint,
    string AbsoluteDeviceSessionPlanEndpointPreview,
    string DeviceSessionCreatePlanEndpoint,
    string AbsoluteDeviceSessionCreatePlanEndpointPreview,
    bool IdentifierProvided,
    bool RememberLogin,
    bool PasswordCollected,
    bool NetworkCallEnabled,
    bool LoginExecutionEnabled,
    bool SessionStorageEnabled,
    bool CookiePersistenceEnabled,
    bool LocalAgentTokenUsed,
    bool TokenSecretPrinted,
    IReadOnlyDictionary<string, object?> BodyPreview,
    IReadOnlyList<string> FollowUpCommands,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionStatusReport(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string ServerUrl,
    string SessionPath,
    CliWebSessionArtifactValidationReport ArtifactValidation,
    CliWebSessionSecretProviderPlanReport SecretProviderPlan,
    CliWebSessionStoredSessionAuthReadinessReport StoredSessionAuthReadiness,
    string ClaimPlanEndpoint,
    string AbsoluteClaimPlanEndpointPreview,
    bool StoredSessionExists,
    bool StoredSessionReadable,
    bool StoredSessionTokenLoaded,
    bool ClaimPollingEnabled,
    bool EnvironmentWebTokenPresent,
    string? EnvironmentWebTokenFingerprint,
    bool UsableForServerPlanFetch,
    bool LocalSessionArtifactWriteEnabled,
    bool LocalSessionArtifactEncryptedRequired,
    bool LocalAgentTokenUsed,
    bool TokenSecretPrinted,
    bool LoginExecutionEnabled,
    bool SessionStorageEnabled,
    string NextCommand);

internal sealed record CliWebSessionSecretProviderPlanReport(
    string Schema,
    string Status,
    string Provider,
    bool WindowsDpapiCandidate,
    bool OsSecretStoreRequired,
    bool ProviderProbeEnabled,
    bool ManualNoSecretProbeAvailable,
    bool ProductionEncryptionEnabled,
    bool ProductionDecryptionEnabled,
    bool ProductionStoredSessionLoadingEnabled,
    bool TestOnlyProviderAcceptedForProduction,
    bool PlaintextTokenSerializationAllowed,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    string FollowUpCommand,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionArtifactValidationReport(
    string Schema,
    string Status,
    string Path,
    bool FileExists,
    bool ReadAttempted,
    bool JsonParsed,
    bool SchemaValidated,
    bool Encrypted,
    bool EncryptionRequired,
    bool AccessTokenLoaded,
    bool RefreshTokenLoaded,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    CliWebSessionArtifactCryptoPreviewRequirement ProductionCryptoPreviewRequirement,
    string RequiredSchema,
    IReadOnlyList<string> RequiredFields,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionArtifactCryptoPreviewRequirement(
    string Schema,
    bool RequiredBeforeProductionStoredSessionLoading,
    string ProofCommand,
    string ProofSchema,
    string Provider,
    bool PreviewOnlyRequired,
    bool AutoRunEnabled,
    bool ArtifactWriteEnabled,
    bool ArtifactReadEnabled,
    bool StoredSessionLoadingEnabled,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    string Reason);

internal sealed record CliWebSessionServerPlanReadinessReport(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string ServerUrl,
    string SessionPath,
    CliWebSessionArtifactValidationReport ArtifactValidation,
    CliWebSessionSecretProviderPlanReport SecretProviderPlan,
    CliWebSessionStoredSessionAuthReadinessReport StoredSessionAuthReadiness,
    bool StoredSessionExists,
    bool StoredSessionReadable,
    bool StoredSessionTokenLoaded,
    string? StoredSessionTokenFingerprint,
    bool EnvironmentWebTokenPresent,
    string? EnvironmentWebTokenFingerprint,
    bool EnvironmentWebTokenUsableForServerPlanFetch,
    bool StoredSessionUsableForServerPlanFetch,
    bool ServerPlanFetchFromStoredSessionEnabled,
    bool LocalSessionArtifactWriteEnabled,
    bool LocalSessionArtifactEncryptedRequired,
    bool LocalAgentTokenUsed,
    bool TokenSecretPrinted,
    bool RequestCreated,
    bool MutationAllowed,
    string FollowUpCommand,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliCodexCommandPreviewReport(
    string Schema,
    string CommandName,
    string Command,
    string Version,
    string Goal,
    string WorkspacePath,
    bool WorkspaceMatched,
    Guid? WorkspaceId,
    bool ReadyForPreview,
    string Status,
    IReadOnlyList<string> Blockers,
    CliCodexServerSubmissionPlan ServerSubmissionPlan,
    CliCodexOneCyclePreview OneCyclePreview,
    IReadOnlyList<string> PlannedLoop,
    bool SubmitEnabled,
    bool ReadOnlyPreview,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    bool ApprovalBypassAllowed,
    bool TestExecutionEnabled,
    bool RollbackExecutionEnabled,
    bool FinalPublicationEnabled,
    bool PartialReindexEnabled,
    bool TokenSecretPrinted,
    string NextCommand);

internal sealed record CliCodexServerPlanFetchResult(
    string Schema,
    string CommandName,
    string Command,
    string Version,
    string Status,
    CliWebSessionServerPlanReadinessReport WebSessionReadiness,
    CliCodexOneCyclePreview OneCyclePreview,
    CliCodexReadOnlyServerBridge ReadOnlyServerBridge,
    bool Attempted,
    bool NetworkCallEnabled,
    bool UsedLocalAgentToken,
    bool WebTokenProvided,
    bool TokenSecretPrinted,
    bool RequestCreated,
    bool MutationAllowed,
    string Endpoint,
    string Method,
    CliCodexServerSubmissionPlan ServerSubmissionPlan,
    IReadOnlyList<string> Blockers,
    int? HttpStatusCode,
    object? ServerResponse,
    string? Error);

internal sealed record CliCodexReadOnlyServerBridge(
    string Schema,
    string Status,
    string FetchStatus,
    bool OneCycleReadyForReadOnlyToolLoop,
    bool AuthenticatedServerPlanReady,
    bool ServerPlanFetchAttempted,
    bool ServerPlanFetched,
    bool ServerPlanNetworkCallEnabled,
    bool EnvironmentTokenFallbackUsed,
    bool StoredSessionAuthUsed,
    bool StoredSessionAuthEnabled,
    bool RequestCreationEnabled,
    bool RunnerPreviewFetchEnabled,
    string RunnerPreviewEndpoint,
    string SelectToolPreviewEndpoint,
    string EnqueueSelectedReadOnlyEndpoint,
    CliCodexFileDiscoveryReadPlan FileDiscoveryReadPlan,
    bool FileDiscoveryPlanEnabled,
    bool FileReadPlanEnabled,
    bool PatchDryRunEnabled,
    bool MutationAllowed,
    bool TokenSecretPrinted,
    IReadOnlyList<string> OrderedReadOnlyStages,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliCodexOneCyclePreview(
    string Schema,
    string Command,
    string Goal,
    string WorkspacePath,
    Guid? WorkspaceId,
    Guid? RepositoryId,
    int MaxSteps,
    string Status,
    bool ReadyForReadOnlyToolLoop,
    bool ReadyForPatchTestLoop,
    bool ReadyForFinalReport,
    bool ReadyForPartialReindex,
    string LocalAgentExecutionTarget,
    bool ServerPlanningRequired,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    bool ApprovalRequiredBeforeMutation,
    bool ApprovalBypassAllowed,
    bool TestExecutionEnabled,
    bool RollbackExecutionEnabled,
    bool FinalReportPublicationEnabled,
    bool PartialReindexEnabled,
    bool TokenSecretPrinted,
    CliCodexFileDiscoveryReadPlan FileDiscoveryReadPlan,
    IReadOnlyList<CliCodexOneCycleStage> Stages,
    IReadOnlyList<string> Blockers,
    string NextCommand,
    string Reason);

internal sealed record CliCodexOneCycleStage(
    string Name,
    string Status,
    bool ReadOnly,
    bool Ready,
    bool RequestCreationEnabled,
    bool RequiresApproval,
    bool MutationAllowed,
    string Description);

internal sealed record CliCodexFileDiscoveryReadPlan(
    string Schema,
    string Status,
    string WorkspacePath,
    Guid? WorkspaceId,
    Guid? RepositoryId,
    bool DryRunOnly,
    bool PlanPrepared,
    bool ToolExecutionEnabled,
    bool FileDiscoveryPlanEnabled,
    bool FileReadPlanEnabled,
    bool FileTreeExecutionEnabled,
    bool FileSearchExecutionEnabled,
    bool FileReadExecutionEnabled,
    bool GitStatusExecutionEnabled,
    bool FileContentRead,
    bool FileBytesLoaded,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    bool TokenSecretPrinted,
    IReadOnlyList<string> CandidateTools,
    IReadOnlyList<string> PathHints,
    IReadOnlyList<string> QueryHints,
    int MaxCandidateFiles,
    int MaxReadFiles,
    int MaxReadBytesPerFile,
    IReadOnlyList<CliCodexReadOnlyRequestEnvelopePreview> RequestEnvelopePreviews,
    IReadOnlyList<CliCodexFileDiscoveryReadPlanStep> PlannedSteps,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliCodexReadOnlyRequestEnvelopePreview(
    string Schema,
    string ToolName,
    string ExecutionTarget,
    string ApprovalState,
    bool RequestCreationEnabled,
    bool EnqueueEnabled,
    bool Claimable,
    bool ExecutionEnabled,
    bool SideEffectful,
    bool RequiresApproval,
    bool MutationAllowed,
    bool FileContentRead,
    bool TokenSecretPrinted,
    IReadOnlyDictionary<string, object?> InputPreview,
    string Reason);

internal sealed record CliCodexFileDiscoveryReadPlanStep(
    string Name,
    string ToolName,
    string? InputHint,
    string Purpose,
    bool ExecutionEnabled,
    bool MutationAllowed);

internal sealed record CliCodexReadOnlyObservationReport(
    string Schema,
    string Status,
    string Command,
    string Goal,
    string WorkspacePath,
    Guid? WorkspaceId,
    Guid? RepositoryId,
    bool Requested,
    bool ReadyForExecution,
    bool ExecutionAttempted,
    bool ToolExecutionEnabled,
    bool RequestCreationEnabled,
    bool FileContentRead,
    bool SearchSnippetsRedacted,
    bool MutationAllowed,
    bool TokenSecretPrinted,
    CliCodexFileDiscoveryReadPlan FileDiscoveryReadPlan,
    IReadOnlyList<CliCodexReadOnlyObservation> Observations,
    CliCodexReadOnlyCandidateSelection CandidateSelection,
    CliCodexSelectedFileReadReport SelectedFileRead,
    CliCodexPatchIntentPreview PatchIntentPreview,
    CliCodexPatchProposalPreview PatchProposalPreview,
    CliCodexDiffSourceInputPreview DiffSourceInputPreview,
    CliCodexPlannerDiffOutputPreview PlannerDiffOutputPreview,
    CliCodexGeneratedDiffAcceptancePreview GeneratedDiffAcceptancePreview,
    CliCodexPlannerDiffValidationHandoffPreview PlannerDiffValidationHandoffPreview,
    CliCodexDiffSourceValidationPreview DiffSourceValidationPreview,
    CliCodexPatchDryRunRequestEnvelopePreview PatchDryRunRequestEnvelopePreview,
    CliCodexPatchDryRunPreflightPreview PatchDryRunPreflightPreview,
    CliCodexPatchDryRunApprovalHandoffPreview PatchDryRunApprovalHandoffPreview,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliCodexReadOnlyObservation(
    string ToolName,
    string Status,
    string? FailureCode,
    string? Error,
    bool Executed,
    bool ReadOnly,
    bool FileContentRead,
    bool SearchSnippetsRedacted,
    bool MutationAllowed,
    IReadOnlyDictionary<string, object?> OutputSummary);

internal sealed record CliCodexReadOnlyCandidateSelection(
    string Schema,
    string Status,
    IReadOnlyList<string> SelectionInputs,
    IReadOnlyList<CliCodexSelectedFile> SelectedFiles,
    int SelectedFileCount,
    int SearchMatchCount,
    int TreeEntryCount,
    int MaxReadFiles,
    int MaxReadBytesPerFile,
    string NextTool,
    bool ReadOnly,
    bool FileReadPlanPrepared,
    bool FileReadExecutionEnabled,
    bool FileContentRead,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    bool RequiresModelRanking,
    bool ModelRankingEnabled,
    string Reason);

internal sealed record CliCodexSelectedFile(
    string Path,
    int Rank,
    string Source,
    string NextTool);

internal sealed record CliCodexSelectedFileReadReport(
    string Schema,
    string Status,
    bool Requested,
    bool ReadyForExecution,
    bool ExecutionAttempted,
    bool FileReadExecutionEnabled,
    bool FileContentRead,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    int MaxReadFiles,
    int MaxReadBytesPerFile,
    int SelectedFileCount,
    int ReadFileCount,
    IReadOnlyList<CliCodexSelectedFileRead> Files,
    IReadOnlyList<string> MissingSelectedFiles,
    string Reason);

internal sealed record CliCodexSelectedFileRead(
    string Path,
    int Rank,
    string Source,
    string Status,
    string? FailureCode,
    string? Error,
    long? Bytes,
    int? ReturnedBytes,
    bool Truncated,
    string? Content);

internal sealed record CliCodexPatchIntentPreview(
    string Schema,
    string Status,
    string Goal,
    bool PlanningInputPrepared,
    int ReadFileCount,
    IReadOnlyList<string> TargetFiles,
    int TotalReturnedBytes,
    bool AnyFileTruncated,
    string NextTool,
    bool DryRunOnly,
    bool DiffGenerated,
    bool PatchDryRunExecutionEnabled,
    bool RequestCreationEnabled,
    bool ApprovalRequiredBeforeMutation,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool FinalReportPublicationEnabled,
    bool PartialReindexEnabled,
    bool LocalModelPlanningEnabled,
    string Reason);

internal sealed record CliCodexPatchProposalPreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    bool ProposalPrepared,
    string DiffSource,
    bool DiffGenerated,
    string? DiffPreview,
    bool UnifiedDiffRequired,
    bool DryRunOnly,
    bool PatchApplyInputPrepared,
    bool PatchDryRunExecutionEnabled,
    bool RequestCreationEnabled,
    bool ApprovalRequiredBeforeMutation,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool LocalModelPlanningEnabled,
    bool ServerPlannerEnabled,
    string Reason);

internal sealed record CliCodexDiffSourceInputPreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    string RequestedSource,
    bool SourceRequested,
    bool SourceRecognized,
    bool SourceEnabled,
    bool DiffFilePathProvided,
    string? DiffFilePathPreview,
    bool DiffFileReadEnabled,
    bool DiffTextProvided,
    bool DiffTextAccepted,
    bool DiffBodyLoaded,
    bool DiffForwardedToValidation,
    bool LocalModelPlanningEnabled,
    bool ServerPlannerEnabled,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    IReadOnlyList<string> SupportedSources,
    string Reason);

internal sealed record CliCodexPlannerDiffOutputPreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    string RequestedSource,
    bool PlannerSourceRequested,
    bool PlannerSourceRecognized,
    bool ReadContextRequired,
    bool ReadContextReady,
    bool PlannerExecutionEnabled,
    bool LocalModelPlanningEnabled,
    bool ServerPlannerEnabled,
    bool OutputEnvelopePrepared,
    bool UnifiedDiffRequired,
    bool DiffGenerated,
    bool DiffBodyIncluded,
    bool DiffForwardedToValidation,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    IReadOnlyDictionary<string, object?> OutputEnvelopePreview,
    string Reason);

internal sealed record CliCodexGeneratedDiffAcceptancePreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    string RequestedSource,
    bool PlannerOutputEnvelopePrepared,
    bool ExplicitPreviewSwitchEnabled,
    bool GeneratedDiffProvided,
    bool GeneratedDiffAccepted,
    int GeneratedDiffBytes,
    int MaxGeneratedDiffBytes,
    bool DiffFileReadEnabled,
    bool InlineDiffAccepted,
    bool ForwardToValidationPreview,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    string? DiffPreview,
    string? Blocker,
    string Reason);

internal sealed record CliCodexPlannerDiffValidationHandoffPreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    string RequestedSource,
    bool PlannerOutputRequired,
    bool PlannerOutputEnvelopePrepared,
    bool DiffBodyAvailable,
    bool ValidationInputPrepared,
    bool ValidationForwardingEnabled,
    bool ValidationAttempted,
    bool DiffValidationPassed,
    bool PatchApplyInputPrepared,
    bool RequestCreationEnabled,
    bool MutationAllowed,
    string? Blocker,
    string Reason);

internal sealed record CliCodexDiffSourceValidationPreview(
    string Schema,
    string Status,
    string Goal,
    IReadOnlyList<string> TargetFiles,
    bool DiffProvided,
    bool DiffParsed,
    string? ParseError,
    IReadOnlyList<string> TouchedFiles,
    IReadOnlyList<string> RejectedFiles,
    bool DiffTouchesOnlyTargetFiles,
    string? DiffPreview,
    bool UnifiedDiffRequired,
    bool DryRunOnly,
    bool PatchApplyInputPrepared,
    bool PatchDryRunExecutionEnabled,
    bool RequestCreationEnabled,
    bool ApprovalRequiredBeforeMutation,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool LocalModelPlanningEnabled,
    bool ServerPlannerEnabled,
    string Reason);

internal sealed record CliCodexPatchDryRunRequestEnvelopePreview(
    string Schema,
    string Status,
    string Goal,
    Guid? WorkspaceId,
    string ToolName,
    string ExecutionTarget,
    string ApprovalState,
    IReadOnlyList<string> TargetFiles,
    bool DiffValidationRequired,
    bool DiffValidationPassed,
    bool RequestEnvelopePrepared,
    bool PatchApplyInputPrepared,
    bool DryRunOnly,
    bool SnapshotCreationRequiredForFullDryRun,
    bool SnapshotCreationEnabled,
    bool PatchDryRunExecutionEnabled,
    bool RequestCreationEnabled,
    bool EnqueueEnabled,
    bool Claimable,
    bool ApprovalRequiredBeforeDryRun,
    bool ApprovalRequiredBeforeMutation,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool FinalReportPublicationEnabled,
    bool PartialReindexEnabled,
    IReadOnlyDictionary<string, object?>? RequestEnvelopePreview,
    string? Blocker,
    string Reason);

internal sealed record CliCodexPatchDryRunPreflightPreview(
    string Schema,
    string Status,
    string Goal,
    string ToolName,
    IReadOnlyList<string> TargetFiles,
    bool Requested,
    bool ReadyForExecution,
    bool ExecutionAttempted,
    bool DiffValidationRequired,
    bool DiffValidationPassed,
    bool NonWritingPreflightOnly,
    bool FileReadAttempted,
    bool ContextValidationAttempted,
    bool PreflightPassed,
    IReadOnlyList<IReadOnlyDictionary<string, object?>> Files,
    bool SnapshotCreated,
    bool MutationApplied,
    bool PatchApplyInputPrepared,
    bool PatchDryRunExecutionEnabled,
    bool RequestCreationEnabled,
    bool ApprovalRequiredBeforeMutation,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool FinalReportPublicationEnabled,
    bool PartialReindexEnabled,
    string? FailureCode,
    string? Error,
    string Reason);

internal sealed record CliCodexPatchDryRunApprovalHandoffPreview(
    string Schema,
    string Status,
    string Goal,
    Guid? WorkspaceId,
    Guid? RepositoryId,
    string ToolName,
    string ExecutionTarget,
    string ApprovalKind,
    string ApprovalState,
    IReadOnlyList<string> TargetFiles,
    bool DiffValidationPassed,
    bool RequestEnvelopePrepared,
    bool NonWritingPreflightRequired,
    bool NonWritingPreflightPassed,
    bool ApprovalHandoffPrepared,
    bool DryRunApprovalRequired,
    bool MutationApprovalRequired,
    bool RequestCreationEnabled,
    bool ApprovalRequestCreationEnabled,
    bool EnqueueEnabled,
    bool Claimable,
    bool SnapshotCreationEnabled,
    bool PatchDryRunExecutionEnabled,
    bool MutationAllowed,
    bool TestExecutionEnabled,
    bool FinalReportPublicationEnabled,
    bool PartialReindexEnabled,
    IReadOnlyDictionary<string, object?>? HandoffPreview,
    string? Blocker,
    string Reason);

internal sealed record CliWebSessionPlanFetchResult(
    string Schema,
    string CommandName,
    string Version,
    string PlanKind,
    string Status,
    string ServerUrl,
    bool Attempted,
    bool NetworkCallEnabled,
    bool FallbackUsed,
    bool UsedLocalAgentToken,
    bool TokenSecretPrinted,
    bool RequestCreated,
    bool DeviceCodeIssued,
    bool SessionClaimed,
    bool AccessTokenIssued,
    bool RefreshTokenIssued,
    bool CookiePersistenceEnabled,
    bool LocalSessionArtifactWritten,
    string Endpoint,
    string Method,
    IReadOnlyDictionary<string, object?> LocalPlan,
    int? HttpStatusCode,
    object? ServerResponse,
    string? Error);

internal sealed record CliWebSessionArtifactWriterPreflightResult(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string ServerUrl,
    string SessionPath,
    bool ClaimResultAccepted,
    bool AccessTokenPresent,
    bool RefreshTokenPresent,
    bool ExpiresAtPresent,
    bool RefreshExpiresAtPresent,
    bool ExpiryFieldsValid,
    bool PlaintextTokenSerializationAllowed,
    bool PlaintextTokenSerializationRequested,
    bool EncryptionRequired,
    string EncryptionProvider,
    bool EncryptionProviderProbeEnabled,
    bool AtomicReplaceRequired,
    IReadOnlyDictionary<string, object?> ArtifactBodyPreview,
    IReadOnlyList<string> RequiredClaimResultFields,
    IReadOnlyList<string> MissingOrInvalidFields,
    bool ArtifactWriterPreflightPassed,
    bool ArtifactWriteRequested,
    bool ArtifactWriterExecutionEnabled,
    bool LocalSessionArtifactWritten,
    bool LocalAgentTokenUsed,
    bool TokenSecretPrinted,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionArtifactWriterTestWriteResult(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string SessionPath,
    bool TestOnlyMode,
    CliWebSessionArtifactWriterPreflightResult Preflight,
    bool ArtifactWriterExecutionEnabled,
    bool LocalSessionArtifactWritten,
    bool AtomicReplaceUsed,
    string EncryptionProvider,
    bool PlaintextTokenSerializationAllowed,
    bool PlaintextTokenSerializationDetected,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    long? BytesWritten,
    string? ArtifactSha256,
    IReadOnlyList<string> Blockers,
    string? Error,
    string Reason);

internal sealed record CliWebSessionArtifactReaderTestValidateResult(
    string Schema,
    string CommandName,
    string Version,
    string Status,
    string SessionPath,
    bool TestOnlyMode,
    bool FileExists,
    bool ReadAttempted,
    bool JsonParsed,
    bool SchemaValidated,
    bool EncryptionProviderAccepted,
    bool DecryptionAttempted,
    bool DecryptionSucceeded,
    string? AccessTokenFingerprint,
    string? RefreshTokenFingerprint,
    bool PlaintextTokenSerializationDetected,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    bool ProductionStoredSessionLoaded,
    IReadOnlyList<string> Blockers,
    string? Error,
    string Reason);

internal sealed record CliCodexServerSubmissionPlan(
    string Schema,
    string Method,
    string Endpoint,
    string AbsoluteEndpointPreview,
    bool ReadyForDisabledPlan,
    bool Enabled,
    bool NetworkCallEnabled,
    bool RequestCreationEnabled,
    bool ServerConversationCreationEnabled,
    bool LoopPreviewExecutionEnabled,
    bool RequiresAuthenticatedWebSession,
    bool RequiresRepositoryAuthorization,
    Guid? RepositoryId,
    Guid? SpaceId,
    Guid? AgentId,
    Guid? WorkspaceId,
    IReadOnlyDictionary<string, object?> BodyPreview,
    IReadOnlyList<string> FollowUpEndpoints,
    IReadOnlyList<string> Blockers,
    string Reason);
