using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

var app = new LearnBotLocalAgent();
return await app.Run(args);

internal sealed partial class LearnBotLocalAgent
{
    private const string Version = "0.1.0";
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public async Task<int> Run(string[] args)
    {
        if (args.Length == 0)
        {
            Help();
            return 0;
        }
        ApplyConfigOverride(args);

        return args[0].ToLowerInvariant() switch
        {
            "pair" => await Pair(args[1..]),
            "agent" => await Agent(args[1..]),
            "workspace" => await Workspace(args[1..]),
            "service" => await Service(args[1..]),
            "file" => FileCommand(args[1..]),
            "git" => await GitCommand(args[1..]),
            "status" => AgentStatus(),
            "doctor" => Doctor(),
            "m8" => M8(args[1..]),
            "login" => await Login(args[1..]),
            "session" => await Session(args[1..]),
            "fix" => await CodexCommandPreview("fix", args[1..]),
            "review" => await CodexCommandPreview("review", args[1..]),
            "open" => Open(),
            "self-test" => await SelfTest(args[1..]),
            "help" or "--help" or "-h" => Help(),
            _ => Unknown(args[0])
        };
    }

    private async Task<int> Pair(string[] args)
    {
        var server = GetOption(args, "--server") ?? "http://localhost:8083";
        var token = GetOption(args, "--token");
        var agentId = GetOption(args, "--agent-id");
        var transport = NormalizeTransport(GetOption(args, "--transport") ?? "polling");
        var workspace = GetOption(args, "--workspace");
        if (string.IsNullOrWhiteSpace(token) && string.IsNullOrWhiteSpace(agentId))
        {
            var webToken = GetOption(args, "--web-token")
                ?? Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN")
                ?? await ReadStoredWebAccessTokenWithRefresh(server.TrimEnd('/'));
            if (string.IsNullOrWhiteSpace(webToken))
            {
                Console.Error.WriteLine("Web session is required for automatic pairing. Run learnbot login, pass --web-token, or use manual --agent-id/--token pairing.");
                return 2;
            }
            try
            {
                using var client = new HttpClient { BaseAddress = new Uri(server.TrimEnd('/')) };
                client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
                client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
                using var response = await client.PostAsync("/api/local-agents/pairing-token", Json(new { label = Environment.MachineName }));
                var body = await response.Content.ReadAsStringAsync();
                if (!response.IsSuccessStatusCode)
                {
                    Console.Error.WriteLine("Failed to issue Local Agent pairing token: HTTP " + (int)response.StatusCode);
                    return 1;
                }
                using var json = JsonDocument.Parse(body);
                token = json.RootElement.GetProperty("token").GetString();
                agentId = json.RootElement.GetProperty("agentId").GetString();
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
            {
                Console.Error.WriteLine("Failed to issue Local Agent pairing token: " + ex.Message);
                return 1;
            }
        }
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(agentId) || !Guid.TryParse(agentId, out var parsedAgentId))
        {
            Console.Error.WriteLine("Usage: learnbot pair --server http://localhost:8083 [--workspace <path>] [--transport polling|websocket|auto] or learnbot pair --agent-id <agent-id> --token <pairing-token>");
            return 2;
        }

        var config = LoadConfigOrDefault();
        var candidate = new AgentConfig
        {
            ServerUrl = server.TrimEnd('/'),
            AgentId = parsedAgentId,
            Token = token,
            Version = Version,
            Transport = transport,
            Workspaces = config.Workspaces
        };
        await SendHeartbeat(candidate);
        SaveConfig(candidate);
        if (!string.IsNullOrWhiteSpace(workspace))
        {
            return await WorkspaceAdd(workspace);
        }
        Console.WriteLine("paired");
        return 0;
    }

    private async Task<int> Agent(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Usage: learnbot agent start|status|stop|logs");
            return 2;
        }
        return args[0].ToLowerInvariant() switch
        {
            "start" => await AgentStart(args[1..]),
            "status" => AgentStatus(),
            "token" => AgentToken(),
            "stop" => AgentStop(),
            "logs" => AgentLogs(args[1..]),
            _ => Unknown("agent " + args[0])
        };
    }

    private async Task<int> AgentStart(string[] args, CancellationToken cancellationToken = default)
    {
        var config = RequireConfig();
        var once = args.Contains("--once", StringComparer.OrdinalIgnoreCase);
        var intervalSeconds = Math.Clamp(ParseInt(GetOption(args, "--interval-seconds"), 15), 5, 300);
        var transport = NormalizeTransport(GetOption(args, "--transport") ?? config.Transport);
        var activeTransport = transport == "polling" ? "polling" : "starting";
        var webSocketFailures = 0;
        DateTimeOffset? nextWebSocketRetryAt = null;
        var finalStatus = "stopped";
        var finalEvent = once ? "completed once" : "stopped";
        WriteRunState("running", null, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
        Log(once ? $"agent start once transport={transport}" : $"agent start transport={transport} intervalSeconds={intervalSeconds}");
        Console.WriteLine(once
            ? $"agent running once; transport={transport}"
            : $"agent running; transport={transport}; durable polling every {intervalSeconds}s");

        try
        {
            do
            {
                try
                {
                    var shouldTryWebSocket = transport != "polling"
                        && (nextWebSocketRetryAt is null || DateTimeOffset.UtcNow >= nextWebSocketRetryAt.Value);
                    var usedWebSocketHeartbeat = shouldTryWebSocket
                        && await TryRunWebSocketOnce(
                            config,
                            transport,
                            once ? TimeSpan.FromSeconds(2) : TimeSpan.FromSeconds(Math.Min(intervalSeconds, 5)));
                    if (usedWebSocketHeartbeat)
                    {
                        activeTransport = "websocket";
                        webSocketFailures = 0;
                        nextWebSocketRetryAt = null;
                        WriteRunState("running", "websocket heartbeat", transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                    }
                    else
                    {
                        if (transport == "polling")
                        {
                            activeTransport = "polling";
                            await SendHeartbeat(config, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                            WriteRunState("running", "heartbeat", transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                        }
                        else if (shouldTryWebSocket)
                        {
                            webSocketFailures++;
                            var retryDelay = WebSocketRetryDelay(webSocketFailures);
                            nextWebSocketRetryAt = DateTimeOffset.UtcNow.Add(retryDelay);
                            activeTransport = "polling-fallback";
                            await SendHeartbeat(config, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                            WriteRunState(
                                "running",
                                $"websocket unavailable; polling fallback; retry in {(int)retryDelay.TotalSeconds}s",
                                transport,
                                activeTransport,
                                webSocketFailures,
                                nextWebSocketRetryAt);
                        }
                        else
                        {
                            activeTransport = "polling-fallback";
                            await SendHeartbeat(config, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                            WriteRunState(
                                "running",
                                "polling fallback; websocket retry scheduled",
                                transport,
                                activeTransport,
                                webSocketFailures,
                                nextWebSocketRetryAt);
                        }
                    }
                    await PollOnce(config);
                    WriteRunState("running", "poll", transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                    if (once) break;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    finalStatus = "stopped";
                    finalEvent = "service stop requested";
                    break;
                }
                catch (Exception ex)
                {
                    Log("agent loop failed: " + ex.Message);
                    WriteRunState(once ? "failed" : "running", "error: " + ex.Message, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                    if (once)
                    {
                        finalStatus = "failed";
                        finalEvent = "error: " + ex.Message;
                        return 1;
                    }
                }
                await Task.Delay(TimeSpan.FromSeconds(intervalSeconds), cancellationToken);
            } while (!cancellationToken.IsCancellationRequested);
        }
        finally
        {
            WriteRunState(finalStatus, finalEvent, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
            Log(once ? "agent stopped after one poll" : "agent stopped");
        }

        return 0;
    }

    private async Task<int> Service(string[] args)
    {
        if (args.Length == 0 || !"run".Equals(args[0], StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine("Usage: learnbot service run [--interval-seconds 15] [--transport polling|websocket|auto]");
            return 2;
        }

        var serviceArgs = args[1..];
        var builder = Host.CreateDefaultBuilder(serviceArgs)
            .UseWindowsService(options => options.ServiceName = "LearnBot Local Agent")
            .ConfigureServices(services =>
            {
                services.AddHostedService(_ => new LearnBotAgentWindowsService(serviceArgs));
            });
        await builder.Build().RunAsync();
        return 0;
    }

    private static void ApplyConfigOverride(string[] args)
    {
        var configPath = GetOption(args, "--config");
        if (!string.IsNullOrWhiteSpace(configPath))
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.GetFullPath(configPath));
        }
    }

    private sealed class LearnBotAgentWindowsService(string[] args) : BackgroundService
    {
        protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        {
            var agent = new LearnBotLocalAgent();
            await agent.AgentStart(args, stoppingToken);
        }
    }

    private int AgentStatus()
    {
        Console.WriteLine(JsonSerializer.Serialize(BuildCliStatusReport(), JsonOptions));
        return 0;
    }

    private int AgentToken()
    {
        var config = LoadConfigOrDefault();
        var paired = !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty;
        Console.WriteLine(JsonSerializer.Serialize(new
        {
            paired,
            config.ServerUrl,
            config.AgentId,
            tokenFingerprint = paired ? TokenFingerprint(config.Token) : null,
            tokenSecretVisible = false,
            manageInWeb = $"{(config.ServerUrl ?? "http://localhost:8083").TrimEnd('/')}/code",
            guidance = paired
                ? "Manage and revoke this Local Agent token from the LearnBot Code workspace."
                : "Pair this agent with learnbot pair --server <url> --agent-id <agent-id> --token <pairing-token>."
        }, JsonOptions));
        return 0;
    }

    private int AgentStop()
    {
        Console.WriteLine("No background service is installed yet. Stop the foreground learnbot agent process with Ctrl+C.");
        return 0;
    }

    private int AgentLogs(string[] args)
    {
        var tail = Math.Clamp(ParseInt(GetOption(args, "--tail"), 80), 1, 1000);
        var path = LogPath();
        if (!File.Exists(path))
        {
            Console.WriteLine("No Local Agent log file exists yet.");
            Console.WriteLine(path);
            return 0;
        }

        foreach (var line in File.ReadLines(path).TakeLast(tail))
        {
            Console.WriteLine(line);
        }
        return 0;
    }

    private async Task<int> Workspace(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Usage: learnbot workspace add <path>|list");
            return 2;
        }

        return args[0].ToLowerInvariant() switch
        {
            "add" => await WorkspaceAdd(args.Length > 1 ? args[1] : "."),
            "list" => WorkspaceList(),
            _ => Unknown("workspace " + args[0])
        };
    }

    private async Task<int> WorkspaceAdd(string path)
    {
        var config = RequireConfig(allowUnpaired: true);
        var fullPath = Path.GetFullPath(path);
        if (!Directory.Exists(fullPath))
        {
            Console.Error.WriteLine("Workspace path does not exist.");
            return 2;
        }

        if (config.Workspaces.All(workspace => !PathEquals(workspace.Path, fullPath)))
        {
            config.Workspaces.Add(new AgentWorkspace(Guid.NewGuid(), Path.GetFileName(fullPath), fullPath, true));
            SaveConfig(config);
        }
        if (!string.IsNullOrWhiteSpace(config.Token)) await SendHeartbeat(config);
        Console.WriteLine(fullPath);
        return 0;
    }

    private int WorkspaceList()
    {
        var config = LoadConfigOrDefault();
        Console.WriteLine(JsonSerializer.Serialize(config.Workspaces, JsonOptions));
        return 0;
    }

    private int Doctor()
    {
        Console.WriteLine(JsonSerializer.Serialize(BuildCliDoctorReport(), JsonOptions));
        return 0;
    }

    private int M8(string[] args)
    {
        if (args.Length == 0 || string.Equals(args[0], "status", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliM8ProductizationReport(), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "doctor", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliM8DoctorReport(), JsonOptions));
            return 0;
        }
        return Unknown("m8 " + args[0]);
    }

    private async Task<int> Login(string[] args)
    {
        if (args.Contains("--plan", StringComparer.OrdinalIgnoreCase) || args.Contains("--preview", StringComparer.OrdinalIgnoreCase))
        {
            return LoginPlan(args);
        }
        if (args.Contains("--browser", StringComparer.OrdinalIgnoreCase) || args.Contains("--device", StringComparer.OrdinalIgnoreCase))
        {
            return await LoginWithDeviceFlow(args);
        }
        var config = LoadConfigOrDefault();
        var server = (GetOption(args, "--server") ?? config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var jsonOutput = args.Contains("--json", StringComparer.OrdinalIgnoreCase);
        var loginId = GetOption(args, "--login-id") ?? GetOption(args, "--email");
        if (string.IsNullOrWhiteSpace(loginId))
        {
            Console.Error.Write("LearnBot login id: ");
            loginId = Console.ReadLine();
        }
        if (string.IsNullOrWhiteSpace(loginId))
        {
            Console.Error.WriteLine("Login id is required.");
            return 2;
        }
        var password = GetOption(args, "--password") ?? ReadSecret("LearnBot password: ");
        if (string.IsNullOrWhiteSpace(password))
        {
            Console.Error.WriteLine("Password is required.");
            return 2;
        }
        var rememberLogin = !args.Contains("--no-remember", StringComparer.OrdinalIgnoreCase);
        try
        {
            using var client = new HttpClient
            {
                BaseAddress = new Uri(server),
                Timeout = TimeSpan.FromMinutes(30)
            };
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var response = await client.PostAsync("/api/auth/cli-login", Json(new
            {
                loginId,
                password,
                rememberLogin
            }));
            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode)
            {
                Console.Error.WriteLine("Login failed: HTTP " + (int)response.StatusCode);
                return 1;
            }
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            var accessToken = root.GetProperty("token").GetString();
            var refreshToken = root.GetProperty("refreshToken").GetString();
            var expiresAt = root.GetProperty("expiresAt").GetString();
            var refreshExpiresAt = root.GetProperty("refreshExpiresAt").GetString();
            var stored = TryWriteStoredWebSession(server, accessToken, refreshToken, expiresAt, refreshExpiresAt, out var storeError);
            config.ServerUrl = server;
            SaveConfig(config);
            if (jsonOutput)
            {
                Console.WriteLine(JsonSerializer.Serialize(new
                {
                    schema = "learnbot.local-agent.web-login-result.v1",
                    status = stored ? "SUCCEEDED" : "TOKEN_RECEIVED_STORAGE_FAILED",
                    serverUrl = server,
                    webSessionStored = stored,
                    tokenSecretPrinted = false,
                    error = storeError,
                    next = stored ? "learnbot fix \"<goal>\"" : "Set LEARNBOT_WEB_TOKEN for this shell or retry login."
                }, JsonOptions));
            }
            else if (stored)
            {
                Console.WriteLine("logged in");
                Console.WriteLine("Now run: learnbot fix \"<what to change>\"");
            }
            else
            {
                Console.Error.WriteLine("Login succeeded, but local session storage failed: " + storeError);
            }
            return stored ? 0 : 1;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or UriFormatException)
        {
            Console.Error.WriteLine("CLI login failed: " + ex.Message);
            return 1;
        }
    }

    private async Task<int> LoginWithDeviceFlow(string[] args)
    {
        var config = LoadConfigOrDefault();
        var server = (GetOption(args, "--server") ?? config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var timeoutSeconds = Math.Clamp(ParseInt(GetOption(args, "--timeout-seconds"), 600), 60, 900);
        try
        {
            using var client = new HttpClient { BaseAddress = new Uri(server) };
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var create = await client.PostAsync("/api/auth/cli-device-session/create", Json(new
            {
                clientName = Environment.MachineName,
                cliVersion = Version
            }));
            var createBody = await create.Content.ReadAsStringAsync();
            if (!create.IsSuccessStatusCode)
            {
                Console.Error.WriteLine("Failed to create CLI login session: HTTP " + (int)create.StatusCode);
                return 1;
            }
            using var createJson = JsonDocument.Parse(createBody);
            var root = createJson.RootElement;
            var deviceCode = root.GetProperty("deviceCode").GetString();
            var userCode = root.GetProperty("userCode").GetString();
            var intervalSeconds = root.TryGetProperty("intervalSeconds", out var intervalElement)
                ? Math.Clamp(intervalElement.GetInt32(), 2, 30)
                : 5;
            var approvalPath = root.TryGetProperty("verificationUriCompletePath", out var pathElement)
                ? pathElement.GetString()
                : "/settings/local-agent/device";
            var approvalUrl = server + approvalPath;
            Console.Error.WriteLine("Open this URL in your logged-in browser to approve the CLI session:");
            Console.Error.WriteLine(approvalUrl);
            Console.Error.WriteLine("User code: " + userCode);
            if (!args.Contains("--no-open", StringComparer.OrdinalIgnoreCase))
            {
                TryOpenUrl(approvalUrl);
            }

            var deadline = DateTimeOffset.UtcNow.AddSeconds(timeoutSeconds);
            while (DateTimeOffset.UtcNow < deadline)
            {
                await Task.Delay(TimeSpan.FromSeconds(intervalSeconds));
                using var poll = await client.PostAsync("/api/auth/cli-device-session/claim-result", Json(new { deviceCode }));
                var pollBody = await poll.Content.ReadAsStringAsync();
                if (!poll.IsSuccessStatusCode)
                {
                    Console.Error.WriteLine("Failed to poll CLI login session: HTTP " + (int)poll.StatusCode);
                    return 1;
                }
                using var pollJson = JsonDocument.Parse(pollBody);
                var pollRoot = pollJson.RootElement;
                var status = pollRoot.TryGetProperty("status", out var statusElement) ? statusElement.GetString() : "";
                if (string.Equals(status, "PENDING_BROWSER_APPROVAL", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                if (!string.Equals(status, "APPROVED", StringComparison.OrdinalIgnoreCase))
                {
                    Console.WriteLine(JsonSerializer.Serialize(new
                    {
                        schema = "learnbot.local-agent.web-login-result.v1",
                        status = status ?? "FAILED",
                        serverUrl = server,
                        webSessionStored = false,
                        tokenSecretPrinted = false,
                        fallback = "Use LEARNBOT_WEB_TOKEN or run learnbot login again."
                    }, JsonOptions));
                    return 1;
                }

                var accessToken = pollRoot.GetProperty("accessToken").GetString();
                var refreshToken = pollRoot.GetProperty("refreshToken").GetString();
                var expiresAt = pollRoot.GetProperty("expiresAt").GetString();
                var refreshExpiresAt = pollRoot.GetProperty("refreshExpiresAt").GetString();
                var stored = TryWriteStoredWebSession(server, accessToken, refreshToken, expiresAt, refreshExpiresAt, out var storeError);
                config.ServerUrl = server;
                SaveConfig(config);
                Console.WriteLine(JsonSerializer.Serialize(new
                {
                    schema = "learnbot.local-agent.web-login-result.v1",
                    status = stored ? "SUCCEEDED" : "TOKEN_RECEIVED_STORAGE_FAILED",
                    serverUrl = server,
                    webSessionStored = stored,
                    tokenSecretPrinted = false,
                    error = storeError,
                    next = stored
                        ? "learnbot pair --workspace <path> --transport auto"
                        : "Set LEARNBOT_WEB_TOKEN for this shell or retry on Windows with DPAPI available."
                }, JsonOptions));
                return stored ? 0 : 1;
            }

            Console.WriteLine(JsonSerializer.Serialize(new
            {
                schema = "learnbot.local-agent.web-login-result.v1",
                status = "TIMED_OUT",
                serverUrl = server,
                webSessionStored = false,
                tokenSecretPrinted = false,
                fallback = "Run learnbot login again or pass --web-token."
            }, JsonOptions));
            return 1;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or UriFormatException)
        {
            Console.Error.WriteLine("CLI login failed: " + ex.Message);
            return 1;
        }
    }

    private int LoginPlan(string[] args)
    {
        var loginId = GetOption(args, "--login-id");
        var email = GetOption(args, "--email");
        var rememberLogin = args.Contains("--remember", StringComparer.OrdinalIgnoreCase);
        Console.WriteLine(JsonSerializer.Serialize(BuildCliWebLoginPlanReport(loginId, email, rememberLogin), JsonOptions));
        return 0;
    }

    private async Task<int> Session(string[] args)
    {
        if (args.Length == 0 || string.Equals(args[0], "status", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionStatusReport(), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "plan", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(await FetchCliWebSessionPlan("device-session", args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "create-plan", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(await FetchCliWebSessionPlan("device-session-create", args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "claim-plan", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(await FetchCliWebSessionPlan("claim", args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "claim-result-plan", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(await FetchCliWebSessionPlan("claim-result", args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-writer-preflight", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionArtifactWriterPreflightResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-writer-test-write", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionArtifactWriterTestWriteResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-reader-test-validate", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionArtifactReaderTestValidateResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-production-crypto-preview", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionProductionArtifactCryptoPreviewResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-production-writer-preview", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionProductionArtifactWriterPreviewResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "artifact-production-reader-preview", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionProductionArtifactReaderPreviewResult(args[1..]), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "stored-session-auth-readiness", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionStoredSessionAuthReadinessReport(), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "secret-provider-plan", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionSecretProviderPlanReport(), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "secret-provider-probe", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionSecretProviderProbeResult(), JsonOptions));
            return 0;
        }
        if (string.Equals(args[0], "server-plan-readiness", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(BuildCliWebSessionServerPlanReadinessReport(), JsonOptions));
            return 0;
        }
        return Unknown("session " + args[0]);
    }

    private async Task<int> CodexCommandPreview(string command, string[] args)
    {
        var goal = GetOption(args, "--goal") ?? PositionalText(args);
        var workspace = GetOption(args, "--workspace") ?? Environment.CurrentDirectory;
        var repositoryId = GetOption(args, "--repository-id");
        var spaceId = GetOption(args, "--space-id");
        var maxSteps = Math.Clamp(ParseInt(GetOption(args, "--max-steps"), 6), 1, 20);
        var jsonOutput = args.Contains("--json", StringComparer.OrdinalIgnoreCase);
        var previewOnly = args.Contains("--preview", StringComparer.OrdinalIgnoreCase);
        var preview = BuildCliCodexCommandPreviewReport(command, goal, workspace, repositoryId, spaceId, maxSteps);
        if (args.Contains("--observe-read-only", StringComparer.OrdinalIgnoreCase))
        {
            var readSelected = args.Contains("--read-selected", StringComparer.OrdinalIgnoreCase);
            var diffSource = GetOption(args, "--diff-source");
            var diffFile = GetOption(args, "--diff-file");
            var diffTextProvided = GetOption(args, "--diff-text") is not null;
            var acceptGeneratedDiffPreview = args.Contains("--accept-generated-diff-preview", StringComparer.OrdinalIgnoreCase);
            var generatedDiffPreview = GetOption(args, "--generated-diff");
            var runNonWritingPreflightPreview = args.Contains("--run-nonwriting-preflight-preview", StringComparer.OrdinalIgnoreCase);
            Console.WriteLine(JsonSerializer.Serialize(BuildCliCodexReadOnlyObservationReport(preview, readSelected, diffSource, diffFile, diffTextProvided, acceptGeneratedDiffPreview, generatedDiffPreview, runNonWritingPreflightPreview), JsonOptions));
            return 0;
        }
        if (previewOnly)
        {
            Console.WriteLine(JsonSerializer.Serialize(preview, JsonOptions));
            return 0;
        }
        if (args.Contains("--server-plan", StringComparer.OrdinalIgnoreCase) || !previewOnly)
        {
            var webToken = GetOption(args, "--web-token")
                ?? Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN")
                ?? await ReadStoredWebAccessTokenWithRefresh(LoadConfigOrDefault().ServerUrl);
            if (string.IsNullOrWhiteSpace(webToken))
            {
                if (jsonOutput)
                {
                    Console.WriteLine(JsonSerializer.Serialize(await FetchCliCodexServerPlan(
                        preview,
                        webToken,
                        null,
                        autoLoop: true,
                        noApply: false,
                        TimeSpan.FromSeconds(300),
                        TimeSpan.FromSeconds(600)), JsonOptions));
                }
                else
                {
                    Console.Error.WriteLine("로그인이 필요합니다. 먼저 실행하세요: learnbot login");
                }
                return 1;
            }
            if (string.IsNullOrWhiteSpace(goal))
            {
                Console.Error.WriteLine($"사용법: learnbot {command} \"원하는 작업\"");
                return 2;
            }
            var prepared = await PrepareCodexServerRun(command, goal, workspace, repositoryId, spaceId, maxSteps, webToken);
            if (!prepared.Success)
            {
                if (jsonOutput && prepared.Result is not null)
                {
                    Console.WriteLine(JsonSerializer.Serialize(prepared.Result, JsonOptions));
                }
                else
                {
                    Console.Error.WriteLine(prepared.Message);
                }
                return 1;
            }
            preview = prepared.Preview!;
            var autoLoop = !args.Contains("--no-auto-loop", StringComparer.OrdinalIgnoreCase);
            var noApply = args.Contains("--no-apply", StringComparer.OrdinalIgnoreCase);
            var pollTimeoutSeconds = Math.Clamp(ParseInt(GetOption(args, "--poll-timeout"), 300), 5, 3600);
            var approvalTimeoutSeconds = Math.Clamp(ParseInt(GetOption(args, "--approval-timeout"), 600), 5, 7200);
            CliCodexReadOnlyObservationReport? approvalHandoffPreview = null;
            if (args.Contains("--include-approval-handoff-preview", StringComparer.OrdinalIgnoreCase))
            {
                var readSelected = args.Contains("--read-selected", StringComparer.OrdinalIgnoreCase);
                var diffSource = GetOption(args, "--diff-source");
                var diffFile = GetOption(args, "--diff-file");
                var diffTextProvided = GetOption(args, "--diff-text") is not null;
                var acceptGeneratedDiffPreview = args.Contains("--accept-generated-diff-preview", StringComparer.OrdinalIgnoreCase);
                var generatedDiffPreview = GetOption(args, "--generated-diff");
                var runNonWritingPreflightPreview = args.Contains("--run-nonwriting-preflight-preview", StringComparer.OrdinalIgnoreCase);
                approvalHandoffPreview = BuildCliCodexReadOnlyObservationReport(preview, readSelected, diffSource, diffFile, diffTextProvided, acceptGeneratedDiffPreview, generatedDiffPreview, runNonWritingPreflightPreview);
            }
            var result = await FetchCliCodexServerPlan(
                preview,
                webToken,
                approvalHandoffPreview?.PatchDryRunApprovalHandoffPreview,
                autoLoop,
                noApply,
                TimeSpan.FromSeconds(pollTimeoutSeconds),
                TimeSpan.FromSeconds(approvalTimeoutSeconds));
            if (jsonOutput)
            {
                Console.WriteLine(JsonSerializer.Serialize(result, JsonOptions));
            }
            else
            {
                PrintCodexSummary(result);
            }
            return result.Status is "RUN_CREATED" ? 0 : 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(preview, JsonOptions));
        return 0;
    }

    private async Task<CliCodexPrepareResult> PrepareCodexServerRun(
        string command,
        string goal,
        string workspacePath,
        string? repositoryId,
        string? spaceId,
        int maxSteps,
        string webToken)
    {
        var config = LoadConfigOrDefault();
        if (string.IsNullOrWhiteSpace(config.Token) || config.AgentId == Guid.Empty)
        {
            return new CliCodexPrepareResult(false, "Local Agent pairing is required. Run: learnbot pair --workspace .", null, null);
        }

        var workspaceRoot = ResolveCommandWorkspaceRoot(workspacePath);
        if (!Directory.Exists(workspaceRoot))
        {
            return new CliCodexPrepareResult(false, "Workspace path does not exist: " + workspaceRoot, null, null);
        }

        var workspace = await EnsureWorkspaceRegistered(config, workspaceRoot);
        Guid? resolvedRepositoryId = Guid.TryParse(repositoryId, out var parsedRepositoryId) ? parsedRepositoryId : null;
        if (resolvedRepositoryId is null)
        {
            try
            {
                var repository = await ResolveOrCreateLocalRepository(config, webToken, workspace, workspaceRoot, spaceId);
                resolvedRepositoryId = repository.Id;
            }
            catch (Exception ex) when (ex is HttpRequestException or JsonException or InvalidOperationException or UriFormatException)
            {
                var preview = BuildCliCodexCommandPreviewReport(command, goal, workspaceRoot, repositoryId, spaceId, maxSteps);
                return new CliCodexPrepareResult(false, "Repository auto-registration failed: " + ex.Message, preview, null);
            }
        }

        var preparedPreview = BuildCliCodexCommandPreviewReport(command, goal, workspaceRoot, resolvedRepositoryId.ToString(), spaceId, maxSteps);
        if (!preparedPreview.ServerSubmissionPlan.Enabled)
        {
            return new CliCodexPrepareResult(false, string.Join(Environment.NewLine, preparedPreview.Blockers), preparedPreview, null);
        }
        return new CliCodexPrepareResult(true, "ready", preparedPreview, null);
    }

    private async Task<AgentWorkspace> EnsureWorkspaceRegistered(AgentConfig config, string workspaceRoot)
    {
        var fullPath = Path.GetFullPath(workspaceRoot);
        var existing = config.Workspaces.FirstOrDefault(workspace => workspace.Approved && PathEquals(workspace.Path, fullPath));
        if (existing is not null)
        {
            return existing;
        }
        var workspace = new AgentWorkspace(Guid.NewGuid(), Path.GetFileName(fullPath), fullPath, true);
        config.Workspaces.Add(workspace);
        SaveConfig(config);
        if (!string.IsNullOrWhiteSpace(config.Token))
        {
            await SendHeartbeat(config);
        }
        return workspace;
    }

    private async Task<CliLocalRepositoryRef> ResolveOrCreateLocalRepository(
        AgentConfig config,
        string webToken,
        AgentWorkspace workspace,
        string workspaceRoot,
        string? spaceId)
    {
        var server = (config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        using var client = new HttpClient { BaseAddress = new Uri(server) };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
        var repositories = await GetJsonElement(client, "/api/code/repositories");
        var normalizedWorkspace = NormalizeComparablePath(workspaceRoot);
        if (repositories.ValueKind == JsonValueKind.Array)
        {
            foreach (var repository in repositories.EnumerateArray())
            {
                var localPath = TryGetString(repository, "localPath");
                if (!string.IsNullOrWhiteSpace(localPath)
                    && string.Equals(NormalizeComparablePath(localPath), normalizedWorkspace, StringComparison.OrdinalIgnoreCase)
                    && TryGetGuid(repository, "id") is Guid existingId)
                {
                    return new CliLocalRepositoryRef(existingId);
                }
            }
        }

        var git = ReadLocalGitIdentity(workspaceRoot);
        var created = await PostJsonElement(client, "/api/code/repositories/local", new
        {
            localPath = workspaceRoot,
            name = string.IsNullOrWhiteSpace(Path.GetFileName(workspaceRoot)) ? "local-workspace" : Path.GetFileName(workspaceRoot),
            branch = string.IsNullOrWhiteSpace(git.Branch) ? "HEAD" : git.Branch,
            headCommit = git.HeadCommit,
            gitRemote = git.RemoteUrl,
            workspaceId = workspace.WorkspaceId,
            spaceId = Guid.TryParse(spaceId, out var parsedSpaceId) ? parsedSpaceId : (Guid?)null
        });
        var id = TryGetGuid(created, "id");
        if (id is null)
        {
            throw new InvalidOperationException("server did not return repository id");
        }
        return new CliLocalRepositoryRef(id.Value);
    }

    private string ResolveCommandWorkspaceRoot(string workspacePath)
    {
        var fullPath = Path.GetFullPath(string.IsNullOrWhiteSpace(workspacePath) ? Environment.CurrentDirectory : workspacePath);
        var gitRoot = RunGitIdentity(fullPath, "rev-parse", "--show-toplevel");
        if (!string.IsNullOrWhiteSpace(gitRoot.Value) && Directory.Exists(gitRoot.Value))
        {
            return Path.GetFullPath(gitRoot.Value);
        }
        return fullPath;
    }

    private CliLocalGitIdentity ReadLocalGitIdentity(string workspaceRoot)
    {
        var branch = RunGitIdentity(workspaceRoot, "branch", "--show-current").Value;
        var headCommit = RunGitIdentity(workspaceRoot, "rev-parse", "HEAD").Value;
        var remoteName = RunGitIdentity(workspaceRoot, "config", "--get", "branch." + (branch ?? "") + ".remote").Value;
        var remoteUrl = !string.IsNullOrWhiteSpace(remoteName)
            ? RunGitIdentity(workspaceRoot, "config", "--get", "remote." + remoteName + ".url").Value
            : RunGitIdentity(workspaceRoot, "config", "--get", "remote.origin.url").Value;
        return new CliLocalGitIdentity(branch, headCommit, remoteUrl);
    }

    private static string NormalizeComparablePath(string path)
    {
        try
        {
            return Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return path.Trim().TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }
    }

    private static void PrintCodexSummary(CliCodexServerPlanFetchResult result)
    {
        Console.WriteLine("status: " + result.Status);
        if (result.AutoLoop is not null)
        {
            Console.WriteLine("loop: " + result.AutoLoop.Status);
            if (result.AutoLoop.ApprovalRequestId is not null)
            {
                Console.WriteLine("approval: " + result.AutoLoop.ApprovalState);
                if (!string.IsNullOrWhiteSpace(result.AutoLoop.ApprovalUrl))
                {
                    Console.WriteLine(result.AutoLoop.ApprovalUrl);
                }
            }
            if (result.AutoLoop.MutationApplied)
            {
                Console.WriteLine("patch applied");
            }
            if (result.AutoLoop.TimedOut)
            {
                Console.WriteLine("timed out waiting for approval or completion");
            }
        }
        if (result.Blockers.Count > 0)
        {
            Console.WriteLine("blocked:");
            foreach (var blocker in result.Blockers)
            {
                Console.WriteLine("- " + blocker);
            }
        }
        if (!string.IsNullOrWhiteSpace(result.Error))
        {
            Console.WriteLine("error: " + result.Error);
        }
    }

    private CliCodexReadOnlyObservationReport BuildCliCodexReadOnlyObservationReport(
        CliCodexCommandPreviewReport preview,
        bool readSelectedFiles = false,
        string? diffSource = null,
        string? diffFile = null,
        bool diffTextProvided = false,
        bool acceptGeneratedDiffPreview = false,
        string? generatedDiffPreview = null,
        bool runNonWritingPreflightPreview = false)
    {
        var blockers = preview.OneCyclePreview.ReadyForReadOnlyToolLoop
            ? new List<string>()
            : preview.OneCyclePreview.Blockers.ToList();
        if (preview.WorkspaceId is null)
        {
            blockers.Add("workspace id is required before read-only observation execution");
        }

        var ready = preview.OneCyclePreview.ReadyForReadOnlyToolLoop && preview.WorkspaceId is not null;
        var observations = new List<CliCodexReadOnlyObservation>();
        CliCodexReadOnlyCandidateSelection? candidateSelection = null;
        CliCodexSelectedFileReadReport? selectedFileRead = null;
        CliCodexPatchIntentPreview? patchIntentPreview = null;
        CliCodexPatchProposalPreview? patchProposalPreview = null;
        CliCodexDiffSourceInputPreview? diffSourceInputPreview = null;
        CliCodexPlannerDiffOutputPreview? plannerDiffOutputPreview = null;
        CliCodexGeneratedDiffAcceptancePreview? generatedDiffAcceptancePreview = null;
        CliCodexPlannerDiffValidationHandoffPreview? plannerDiffValidationHandoffPreview = null;
        CliCodexDiffSourceValidationPreview? diffSourceValidationPreview = null;
        CliCodexPatchDryRunRequestEnvelopePreview? patchDryRunRequestEnvelopePreview = null;
        CliCodexPatchDryRunPreflightPreview? patchDryRunPreflightPreview = null;
        CliCodexPatchDryRunApprovalHandoffPreview? patchDryRunApprovalHandoffPreview = null;
        if (ready)
        {
            var config = LoadConfigOrDefault();
            var query = string.Join(" ", preview.OneCyclePreview.FileDiscoveryReadPlan.QueryHints);
            var workspaceId = preview.WorkspaceId!.Value;
            var treeRequest = BuildToolRequest(workspaceId, new Dictionary<string, object?>
            {
                ["path"] = ".",
                ["maxEntries"] = DefaultMaxTreeEntries,
                ["maxDepth"] = DefaultMaxTreeDepth
            });
            var searchRequest = BuildToolRequest(workspaceId, new Dictionary<string, object?>
            {
                ["query"] = query,
                ["path"] = ".",
                ["maxMatches"] = DefaultMaxSearchMatches,
                ["maxFiles"] = DefaultMaxSearchFiles,
                ["maxBytesPerFile"] = DefaultMaxSearchFileBytes
            });
            var statusRequest = BuildToolRequest(workspaceId, new Dictionary<string, object?>());

            var treeResponse = HandleTool(config, Guid.NewGuid(), treeRequest, "workspace.tree");
            var searchResponse = HandleTool(config, Guid.NewGuid(), searchRequest, "workspace.search");
            var statusResponse = HandleTool(config, Guid.NewGuid(), statusRequest, "git.status");
            observations.Add(ToReadOnlyObservation(treeResponse));
            observations.Add(ToReadOnlyObservation(searchResponse));
            observations.Add(ToReadOnlyObservation(statusResponse));
            candidateSelection = BuildCliCodexReadOnlyCandidateSelection(treeResponse, searchResponse, preview.OneCyclePreview.FileDiscoveryReadPlan);
            selectedFileRead = BuildCliCodexSelectedFileReadReport(config, preview.WorkspaceId!.Value, candidateSelection, readSelectedFiles);
            patchIntentPreview = BuildCliCodexPatchIntentPreview(preview.Goal, selectedFileRead);
            patchProposalPreview = BuildCliCodexPatchProposalPreview(patchIntentPreview);
            diffSourceInputPreview = BuildCliCodexDiffSourceInputPreview(patchProposalPreview, diffSource, diffFile, diffTextProvided);
            plannerDiffOutputPreview = BuildCliCodexPlannerDiffOutputPreview(patchProposalPreview, diffSourceInputPreview);
            generatedDiffAcceptancePreview = BuildCliCodexGeneratedDiffAcceptancePreview(plannerDiffOutputPreview, acceptGeneratedDiffPreview, generatedDiffPreview);
            diffSourceValidationPreview = BuildCliCodexDiffSourceValidationPreview(patchProposalPreview, generatedDiffAcceptancePreview.GeneratedDiffAccepted ? generatedDiffPreview : null);
            plannerDiffValidationHandoffPreview = BuildCliCodexPlannerDiffValidationHandoffPreview(plannerDiffOutputPreview, generatedDiffAcceptancePreview, diffSourceValidationPreview);
            patchDryRunRequestEnvelopePreview = BuildCliCodexPatchDryRunRequestEnvelopePreview(diffSourceValidationPreview, preview.WorkspaceId);
            patchDryRunPreflightPreview = runNonWritingPreflightPreview
                ? BuildCliCodexPatchDryRunPreflightPreview(config, workspaceId, diffSourceValidationPreview, generatedDiffAcceptancePreview.GeneratedDiffAccepted ? generatedDiffPreview : null)
                : BuildCliCodexPatchDryRunPreflightPreview(null, null, diffSourceValidationPreview);
            patchDryRunApprovalHandoffPreview = BuildCliCodexPatchDryRunApprovalHandoffPreview(
                patchDryRunRequestEnvelopePreview,
                patchDryRunPreflightPreview,
                preview.OneCyclePreview.RepositoryId);
        }

        var succeeded = ready && observations.All(item => item.Status == "SUCCEEDED");
        candidateSelection ??= BuildBlockedCliCodexReadOnlyCandidateSelection(preview.OneCyclePreview.FileDiscoveryReadPlan);
        selectedFileRead ??= BuildBlockedCliCodexSelectedFileReadReport(candidateSelection, readSelectedFiles);
        patchIntentPreview ??= BuildCliCodexPatchIntentPreview(preview.Goal, selectedFileRead);
        patchProposalPreview ??= BuildCliCodexPatchProposalPreview(patchIntentPreview);
        diffSourceInputPreview ??= BuildCliCodexDiffSourceInputPreview(patchProposalPreview, diffSource, diffFile, diffTextProvided);
        plannerDiffOutputPreview ??= BuildCliCodexPlannerDiffOutputPreview(patchProposalPreview, diffSourceInputPreview);
        generatedDiffAcceptancePreview ??= BuildCliCodexGeneratedDiffAcceptancePreview(plannerDiffOutputPreview, acceptGeneratedDiffPreview, generatedDiffPreview);
        diffSourceValidationPreview ??= BuildCliCodexDiffSourceValidationPreview(patchProposalPreview, generatedDiffAcceptancePreview.GeneratedDiffAccepted ? generatedDiffPreview : null);
        plannerDiffValidationHandoffPreview ??= BuildCliCodexPlannerDiffValidationHandoffPreview(plannerDiffOutputPreview, generatedDiffAcceptancePreview, diffSourceValidationPreview);
        patchDryRunRequestEnvelopePreview ??= BuildCliCodexPatchDryRunRequestEnvelopePreview(diffSourceValidationPreview, preview.WorkspaceId);
        patchDryRunPreflightPreview ??= BuildCliCodexPatchDryRunPreflightPreview(null, null, diffSourceValidationPreview);
        patchDryRunApprovalHandoffPreview ??= BuildCliCodexPatchDryRunApprovalHandoffPreview(
            patchDryRunRequestEnvelopePreview,
            patchDryRunPreflightPreview,
            preview.OneCyclePreview.RepositoryId);
        return new CliCodexReadOnlyObservationReport(
            Schema: "learnbot.local-agent.codex-read-only-observation.v1",
            Status: ready ? succeeded ? "OBSERVED_READ_ONLY_CONTEXT" : "OBSERVATION_FAILED" : "BLOCKED_PREVIEW",
            Command: preview.Command,
            Goal: preview.Goal,
            WorkspacePath: preview.WorkspacePath,
            WorkspaceId: preview.WorkspaceId,
            RepositoryId: preview.OneCyclePreview.RepositoryId,
            Requested: true,
            ReadyForExecution: ready,
            ExecutionAttempted: ready,
            ToolExecutionEnabled: ready,
            RequestCreationEnabled: false,
            FileContentRead: selectedFileRead.FileContentRead,
            SearchSnippetsRedacted: true,
            MutationAllowed: false,
            TokenSecretPrinted: false,
            FileDiscoveryReadPlan: preview.OneCyclePreview.FileDiscoveryReadPlan,
            Observations: observations,
            CandidateSelection: candidateSelection,
            SelectedFileRead: selectedFileRead,
            PatchIntentPreview: patchIntentPreview,
            PatchProposalPreview: patchProposalPreview,
            DiffSourceInputPreview: diffSourceInputPreview,
            PlannerDiffOutputPreview: plannerDiffOutputPreview,
            GeneratedDiffAcceptancePreview: generatedDiffAcceptancePreview,
            PlannerDiffValidationHandoffPreview: plannerDiffValidationHandoffPreview,
            DiffSourceValidationPreview: diffSourceValidationPreview,
            PatchDryRunRequestEnvelopePreview: patchDryRunRequestEnvelopePreview,
            PatchDryRunPreflightPreview: patchDryRunPreflightPreview,
            PatchDryRunApprovalHandoffPreview: patchDryRunApprovalHandoffPreview,
            Blockers: ready ? observations.Where(item => item.Status != "SUCCEEDED").Select(item => $"{item.ToolName}: {item.Error ?? item.FailureCode ?? item.Status}").ToList() : blockers,
            Reason: readSelectedFiles
                ? "This executes read-only discovery observations and bounded selected file reads for a Codex-like local cycle. It validates the future diff-source boundary only as a disabled preview and does not create server requests, apply patches, run tests, mutate files, publish final reports, or enqueue partial reindex."
                : "This executes only the first read-only discovery observations for a Codex-like local cycle. It does not create server requests, read selected file contents, apply patches, run tests, mutate files, publish final reports, or enqueue partial reindex.");
    }

    private static JsonElement BuildToolRequest(Guid workspaceId, Dictionary<string, object?> input) =>
        JsonSerializer.SerializeToElement(new Dictionary<string, object?>
        {
            ["sessionId"] = Guid.NewGuid(),
            ["userId"] = Guid.Empty,
            ["executionTarget"] = "USER_LOCAL_AGENT",
            ["workspaceId"] = workspaceId,
            ["input"] = input
        }, JsonOptions);

    private static CliCodexReadOnlyObservation ToReadOnlyObservation(ToolResponse response) =>
        new(
            ToolName: response.ToolName,
            Status: response.Status,
            FailureCode: response.FailureCode,
            Error: response.Error,
            Executed: true,
            ReadOnly: true,
            FileContentRead: false,
            SearchSnippetsRedacted: response.ToolName == "workspace.search",
            MutationAllowed: false,
            OutputSummary: SanitizeReadOnlyObservationOutput(response.ToolName, response.Output));

    private static IReadOnlyDictionary<string, object?> SanitizeReadOnlyObservationOutput(string toolName, Dictionary<string, object?> output)
    {
        if (toolName == "workspace.search")
        {
            return new Dictionary<string, object?>
            {
                ["workspaceId"] = output.GetValueOrDefault("workspaceId"),
                ["relativePath"] = output.GetValueOrDefault("relativePath"),
                ["query"] = output.GetValueOrDefault("query"),
                ["matchCount"] = output.GetValueOrDefault("matchCount"),
                ["scannedFiles"] = output.GetValueOrDefault("scannedFiles"),
                ["skippedFiles"] = output.GetValueOrDefault("skippedFiles"),
                ["maxMatches"] = output.GetValueOrDefault("maxMatches"),
                ["maxFiles"] = output.GetValueOrDefault("maxFiles"),
                ["maxBytesPerFile"] = output.GetValueOrDefault("maxBytesPerFile"),
                ["truncated"] = output.GetValueOrDefault("truncated"),
                ["matchedPaths"] = ExtractSearchMatchedPaths(output),
                ["snippetsIncluded"] = false
            };
        }
        if (toolName == "workspace.tree")
        {
            return new Dictionary<string, object?>
            {
                ["workspaceId"] = output.GetValueOrDefault("workspaceId"),
                ["relativePath"] = output.GetValueOrDefault("relativePath"),
                ["entryCount"] = output.GetValueOrDefault("entryCount"),
                ["maxEntries"] = output.GetValueOrDefault("maxEntries"),
                ["maxDepth"] = output.GetValueOrDefault("maxDepth"),
                ["truncated"] = output.GetValueOrDefault("truncated"),
                ["entries"] = output.GetValueOrDefault("entries")
            };
        }
        if (toolName == "git.status")
        {
            return new Dictionary<string, object?>
            {
                ["workspaceId"] = output.GetValueOrDefault("workspaceId"),
                ["branch"] = output.GetValueOrDefault("branch"),
                ["clean"] = output.GetValueOrDefault("clean"),
                ["changes"] = output.GetValueOrDefault("changes"),
                ["identityComplete"] = output.GetValueOrDefault("identityComplete"),
                ["repositoryIdentity"] = output.GetValueOrDefault("repositoryIdentity")
            };
        }
        return output;
    }

    private static IReadOnlyList<string> ExtractSearchMatchedPaths(Dictionary<string, object?> output)
    {
        if (!output.TryGetValue("matches", out var value) || value is not IEnumerable<Dictionary<string, object?>> matches)
        {
            return [];
        }
        return matches
            .Select(match => match.TryGetValue("path", out var path) ? path?.ToString() : null)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(20)
            .Cast<string>()
            .ToList();
    }

    private static CliCodexReadOnlyCandidateSelection BuildCliCodexReadOnlyCandidateSelection(
        ToolResponse treeResponse,
        ToolResponse searchResponse,
        CliCodexFileDiscoveryReadPlan plan)
    {
        var searchPaths = ExtractSearchPathsFromToolResponse(searchResponse)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var treePaths = ExtractTreeFilePathsFromToolResponse(treeResponse)
            .Where(LooksLikeSourceFile)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var selectedPaths = searchResponse.Status == "SUCCEEDED" && searchPaths.Count > 0
            ? searchPaths
            : treePaths;
        var selectedFiles = selectedPaths
            .Take(plan.MaxReadFiles)
            .Select((path, index) => new CliCodexSelectedFile(
                Path: path,
                Rank: index + 1,
                Source: searchPaths.Contains(path, StringComparer.OrdinalIgnoreCase) ? "workspace.search" : "workspace.tree",
                NextTool: "file.read"))
            .ToList();
        var status = selectedFiles.Count == 0
            ? "NO_CANDIDATES"
            : searchResponse.Status == "SUCCEEDED" && searchPaths.Count > 0
                ? "READY_FILE_READ_PLAN"
                : "READY_WITH_TREE_FALLBACK";
        return new CliCodexReadOnlyCandidateSelection(
            Schema: "learnbot.local-agent.codex-read-only-candidate-selection.v1",
            Status: status,
            SelectionInputs: ["workspace.tree", "workspace.search", "git.status"],
            SelectedFiles: selectedFiles,
            SelectedFileCount: selectedFiles.Count,
            SearchMatchCount: CountCliSearchMatches(searchResponse),
            TreeEntryCount: CountCliTreeEntries(treeResponse),
            MaxReadFiles: plan.MaxReadFiles,
            MaxReadBytesPerFile: plan.MaxReadBytesPerFile,
            NextTool: "file.read",
            ReadOnly: true,
            FileReadPlanPrepared: selectedFiles.Count > 0,
            FileReadExecutionEnabled: false,
            FileContentRead: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            RequiresModelRanking: false,
            ModelRankingEnabled: false,
            Reason: "Candidate selection ranks sanitized discovery observations into bounded file.read candidates, but selected file contents remain unread until the next guarded step.");
    }

    private static CliCodexReadOnlyCandidateSelection BuildBlockedCliCodexReadOnlyCandidateSelection(CliCodexFileDiscoveryReadPlan plan) =>
        new(
            Schema: "learnbot.local-agent.codex-read-only-candidate-selection.v1",
            Status: "BLOCKED_PREVIEW",
            SelectionInputs: ["workspace.tree", "workspace.search", "git.status"],
            SelectedFiles: [],
            SelectedFileCount: 0,
            SearchMatchCount: 0,
            TreeEntryCount: 0,
            MaxReadFiles: plan.MaxReadFiles,
            MaxReadBytesPerFile: plan.MaxReadBytesPerFile,
            NextTool: "file.read",
            ReadOnly: true,
            FileReadPlanPrepared: false,
            FileReadExecutionEnabled: false,
            FileContentRead: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            RequiresModelRanking: false,
            ModelRankingEnabled: false,
            Reason: "Candidate selection is blocked until the read-only observations run successfully.");

    private static IReadOnlyList<string> ExtractSearchPathsFromToolResponse(ToolResponse response)
    {
        if (!response.Output.TryGetValue("matches", out var value) || value is not IEnumerable<Dictionary<string, object?>> matches)
        {
            return [];
        }
        return matches
            .Select(match => match.TryGetValue("path", out var path) ? path?.ToString() : null)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Cast<string>()
            .ToList();
    }

    private static IReadOnlyList<string> ExtractTreeFilePathsFromToolResponse(ToolResponse response)
    {
        if (!response.Output.TryGetValue("entries", out var value) || value is not IEnumerable<Dictionary<string, object?>> entries)
        {
            return [];
        }
        return entries
            .Where(entry => !entry.TryGetValue("type", out var type) || string.Equals(type?.ToString(), "file", StringComparison.OrdinalIgnoreCase))
            .Select(entry => entry.TryGetValue("path", out var path) ? path?.ToString() : null)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Cast<string>()
            .ToList();
    }

    private static int CountCliSearchMatches(ToolResponse response) =>
        response.Output.TryGetValue("matches", out var value) && value is IEnumerable<Dictionary<string, object?>> matches
            ? matches.Count()
            : 0;

    private static int CountCliTreeEntries(ToolResponse response) =>
        response.Output.TryGetValue("entries", out var value) && value is IEnumerable<Dictionary<string, object?>> entries
            ? entries.Count()
            : 0;

    private CliCodexSelectedFileReadReport BuildCliCodexSelectedFileReadReport(
        AgentConfig config,
        Guid workspaceId,
        CliCodexReadOnlyCandidateSelection candidateSelection,
        bool readSelectedFiles)
    {
        if (!readSelectedFiles)
        {
            return BuildBlockedCliCodexSelectedFileReadReport(candidateSelection, requested: false);
        }
        if (!candidateSelection.FileReadPlanPrepared || candidateSelection.SelectedFiles.Count == 0)
        {
            return BuildBlockedCliCodexSelectedFileReadReport(candidateSelection, requested: true);
        }

        var readFiles = new List<CliCodexSelectedFileRead>();
        foreach (var selected in candidateSelection.SelectedFiles.Take(candidateSelection.MaxReadFiles))
        {
            var request = BuildToolRequest(workspaceId, new Dictionary<string, object?>
            {
                ["path"] = selected.Path,
                ["maxBytes"] = candidateSelection.MaxReadBytesPerFile
            });
            var response = HandleTool(config, Guid.NewGuid(), request, "file.read");
            readFiles.Add(ToSelectedFileRead(selected, response));
        }

        var succeededCount = readFiles.Count(item => item.Status == "SUCCEEDED");
        return new CliCodexSelectedFileReadReport(
            Schema: "learnbot.local-agent.codex-selected-file-read.v1",
            Status: succeededCount == readFiles.Count ? "SUCCEEDED" : succeededCount > 0 ? "PARTIAL" : "FAILED",
            Requested: true,
            ReadyForExecution: true,
            ExecutionAttempted: true,
            FileReadExecutionEnabled: true,
            FileContentRead: succeededCount > 0,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            MaxReadFiles: candidateSelection.MaxReadFiles,
            MaxReadBytesPerFile: candidateSelection.MaxReadBytesPerFile,
            SelectedFileCount: candidateSelection.SelectedFileCount,
            ReadFileCount: succeededCount,
            Files: readFiles,
            MissingSelectedFiles: candidateSelection.SelectedFiles
                .Select(file => file.Path)
                .Except(readFiles.Where(file => file.Status == "SUCCEEDED").Select(file => file.Path), StringComparer.OrdinalIgnoreCase)
                .ToList(),
            Reason: "Selected files were read through bounded file.read only after explicit --read-selected. No server request was created and no mutation was allowed.");
    }

    private static CliCodexSelectedFileReadReport BuildBlockedCliCodexSelectedFileReadReport(
        CliCodexReadOnlyCandidateSelection candidateSelection,
        bool requested) =>
        new(
            Schema: "learnbot.local-agent.codex-selected-file-read.v1",
            Status: requested ? "BLOCKED_PREVIEW" : "NOT_REQUESTED",
            Requested: requested,
            ReadyForExecution: false,
            ExecutionAttempted: false,
            FileReadExecutionEnabled: false,
            FileContentRead: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            MaxReadFiles: candidateSelection.MaxReadFiles,
            MaxReadBytesPerFile: candidateSelection.MaxReadBytesPerFile,
            SelectedFileCount: candidateSelection.SelectedFileCount,
            ReadFileCount: 0,
            Files: [],
            MissingSelectedFiles: candidateSelection.SelectedFiles.Select(file => file.Path).ToList(),
            Reason: requested
                ? "Selected file read is blocked until candidate selection is ready."
                : "Selected file read was not requested. Pass --read-selected with --observe-read-only to read bounded selected file contents.");

    private static CliCodexSelectedFileRead ToSelectedFileRead(CliCodexSelectedFile selected, ToolResponse response)
    {
        response.Output.TryGetValue("content", out var content);
        return new CliCodexSelectedFileRead(
            Path: selected.Path,
            Rank: selected.Rank,
            Source: selected.Source,
            Status: response.Status,
            FailureCode: response.FailureCode,
            Error: response.Error,
            Bytes: TryLongOutput(response.Output, "bytes"),
            ReturnedBytes: TryIntOutput(response.Output, "returnedBytes"),
            Truncated: response.Output.TryGetValue("truncated", out var truncated) && truncated is bool truncatedBool && truncatedBool,
            Content: response.Status == "SUCCEEDED" ? content?.ToString() : null);
    }

    private static CliCodexPatchIntentPreview BuildCliCodexPatchIntentPreview(string goal, CliCodexSelectedFileReadReport selectedFileRead)
    {
        var readFiles = selectedFileRead.Files
            .Where(file => file.Status == "SUCCEEDED")
            .ToList();
        var ready = selectedFileRead.Status == "SUCCEEDED" && readFiles.Count > 0;
        return new CliCodexPatchIntentPreview(
            Schema: "learnbot.local-agent.codex-patch-intent-preview.v1",
            Status: ready ? "READY_PATCH_INTENT_PREVIEW" : "READ_REQUIRED",
            Goal: goal,
            PlanningInputPrepared: ready,
            ReadFileCount: readFiles.Count,
            TargetFiles: readFiles.Select(file => file.Path).ToList(),
            TotalReturnedBytes: readFiles.Sum(file => file.ReturnedBytes ?? 0),
            AnyFileTruncated: readFiles.Any(file => file.Truncated),
            NextTool: "patch.apply",
            DryRunOnly: true,
            DiffGenerated: false,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            LocalModelPlanningEnabled: false,
            Reason: ready
                ? "Selected file contents are available as planning input, but no diff is generated and patch.apply dry-run execution remains disabled."
                : "Patch intent preview requires bounded selected file reads first.");
    }

    private static CliCodexPatchProposalPreview BuildCliCodexPatchProposalPreview(CliCodexPatchIntentPreview intent)
    {
        var readyForProposal = intent.PlanningInputPrepared && intent.TargetFiles.Count > 0;
        return new CliCodexPatchProposalPreview(
            Schema: "learnbot.local-agent.codex-patch-proposal-preview.v1",
            Status: readyForProposal ? "AWAITING_DIFF_SOURCE" : "READ_REQUIRED",
            Goal: intent.Goal,
            TargetFiles: intent.TargetFiles,
            ProposalPrepared: readyForProposal,
            DiffSource: "NONE_PLACEHOLDER_REQUIRED",
            DiffGenerated: false,
            DiffPreview: null,
            UnifiedDiffRequired: true,
            DryRunOnly: true,
            PatchApplyInputPrepared: false,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            LocalModelPlanningEnabled: false,
            ServerPlannerEnabled: false,
            Reason: readyForProposal
                ? "Read context is available, but no local-model or server-planner diff source is enabled yet; patch.apply dry-run input is therefore not prepared."
                : "Patch proposal preview requires a ready patch intent from bounded selected file reads.");
    }

    private static CliCodexDiffSourceInputPreview BuildCliCodexDiffSourceInputPreview(
        CliCodexPatchProposalPreview proposal,
        string? requestedSource,
        string? diffFile,
        bool diffTextProvided)
    {
        var supported = new[] { "local-model", "server-planner", "inline", "file" };
        var normalizedSource = string.IsNullOrWhiteSpace(requestedSource)
            ? "none"
            : requestedSource.Trim().ToLowerInvariant();
        var sourceRecognized = normalizedSource == "none"
            || supported.Contains(normalizedSource, StringComparer.OrdinalIgnoreCase);
        var sourceRequested = normalizedSource != "none" || !string.IsNullOrWhiteSpace(diffFile) || diffTextProvided;
        var status = !proposal.ProposalPrepared
            ? "READ_REQUIRED"
            : !sourceRequested
                ? "DIFF_SOURCE_NOT_PROVIDED"
                : !sourceRecognized
                    ? "BLOCKED_UNSUPPORTED_DIFF_SOURCE"
                    : "DIFF_SOURCE_DISABLED_PREVIEW";
        return new CliCodexDiffSourceInputPreview(
            Schema: "learnbot.local-agent.codex-diff-source-input-preview.v1",
            Status: status,
            Goal: proposal.Goal,
            TargetFiles: proposal.TargetFiles,
            RequestedSource: normalizedSource,
            SourceRequested: sourceRequested,
            SourceRecognized: sourceRecognized,
            SourceEnabled: false,
            DiffFilePathProvided: !string.IsNullOrWhiteSpace(diffFile),
            DiffFilePathPreview: string.IsNullOrWhiteSpace(diffFile) ? null : diffFile,
            DiffFileReadEnabled: false,
            DiffTextProvided: diffTextProvided,
            DiffTextAccepted: false,
            DiffBodyLoaded: false,
            DiffForwardedToValidation: false,
            LocalModelPlanningEnabled: false,
            ServerPlannerEnabled: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            SupportedSources: supported,
            Reason: status switch
            {
                "READ_REQUIRED" => "Diff-source input requires bounded selected file reads and a ready patch proposal first.",
                "DIFF_SOURCE_NOT_PROVIDED" => "No diff source was provided. Future local-model, server-planner, inline, or file sources must pass this boundary before validation.",
                "BLOCKED_UNSUPPORTED_DIFF_SOURCE" => "The requested diff source is not supported. Supported sources are local-model, server-planner, inline, and file.",
                _ => "Diff-source metadata was received, but source execution, file reads, inline diff acceptance, validation forwarding, request creation, and mutation remain disabled."
            });
    }

    private static CliCodexPlannerDiffOutputPreview BuildCliCodexPlannerDiffOutputPreview(
        CliCodexPatchProposalPreview proposal,
        CliCodexDiffSourceInputPreview input)
    {
        var plannerRequested = input.RequestedSource is "local-model" or "server-planner";
        var readContextReady = proposal.ProposalPrepared && proposal.TargetFiles.Count > 0;
        var status = !readContextReady
            ? "READ_REQUIRED"
            : !plannerRequested
                ? "PLANNER_SOURCE_NOT_REQUESTED"
                : "PLANNER_OUTPUT_DISABLED_PREVIEW";
        return new CliCodexPlannerDiffOutputPreview(
            Schema: "learnbot.local-agent.codex-planner-diff-output-preview.v1",
            Status: status,
            Goal: proposal.Goal,
            TargetFiles: proposal.TargetFiles,
            RequestedSource: input.RequestedSource,
            PlannerSourceRequested: plannerRequested,
            PlannerSourceRecognized: plannerRequested,
            ReadContextRequired: true,
            ReadContextReady: readContextReady,
            PlannerExecutionEnabled: false,
            LocalModelPlanningEnabled: false,
            ServerPlannerEnabled: false,
            OutputEnvelopePrepared: plannerRequested && readContextReady,
            UnifiedDiffRequired: true,
            DiffGenerated: false,
            DiffBodyIncluded: false,
            DiffForwardedToValidation: false,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            OutputEnvelopePreview: new Dictionary<string, object?>
            {
                ["schema"] = "learnbot.local-agent.codex-planner-diff-output-envelope.v1",
                ["source"] = input.RequestedSource,
                ["goal"] = proposal.Goal,
                ["targetFiles"] = proposal.TargetFiles,
                ["unifiedDiffRequired"] = true,
                ["diff"] = "<disabled-unified-diff-placeholder>",
                ["diffGenerated"] = false,
                ["forwardToValidationEnabled"] = false,
                ["mutationAllowed"] = false
            },
            Reason: status switch
            {
                "READ_REQUIRED" => "Planner diff output requires bounded selected file reads and a ready patch proposal first.",
                "PLANNER_SOURCE_NOT_REQUESTED" => "No local-model or server-planner diff source was requested, so no planner output envelope is prepared.",
                _ => "Planner diff output envelope shape is prepared, but local model calls, server planner calls, diff generation, validation forwarding, request creation, and mutation remain disabled."
            });
    }

    private static CliCodexGeneratedDiffAcceptancePreview BuildCliCodexGeneratedDiffAcceptancePreview(
        CliCodexPlannerDiffOutputPreview planner,
        bool explicitPreviewSwitchEnabled,
        string? generatedDiff)
    {
        var plannerOutputReady = planner.OutputEnvelopePrepared && planner.PlannerSourceRequested;
        var generatedDiffProvided = !string.IsNullOrWhiteSpace(generatedDiff);
        var generatedDiffBytes = generatedDiffProvided ? Encoding.UTF8.GetByteCount(generatedDiff!) : 0;
        var withinSizeLimit = generatedDiffBytes <= AbsoluteMaxPatchBytes;
        var accepted = planner.ReadContextReady
            && plannerOutputReady
            && explicitPreviewSwitchEnabled
            && generatedDiffProvided
            && withinSizeLimit;
        var status = !planner.ReadContextReady
            ? "READ_REQUIRED"
            : !plannerOutputReady
                ? "PLANNER_SOURCE_REQUIRED"
                : !generatedDiffProvided
                    ? "GENERATED_DIFF_NOT_PROVIDED"
                    : !explicitPreviewSwitchEnabled
                        ? "EXPLICIT_SWITCH_REQUIRED"
                        : !withinSizeLimit
                            ? "BLOCKED_SIZE_LIMIT"
                            : "ACCEPTED_FOR_VALIDATION_PREVIEW";

        return new CliCodexGeneratedDiffAcceptancePreview(
            Schema: "learnbot.local-agent.codex-generated-diff-acceptance-preview.v1",
            Status: status,
            Goal: planner.Goal,
            TargetFiles: planner.TargetFiles,
            RequestedSource: planner.RequestedSource,
            PlannerOutputEnvelopePrepared: planner.OutputEnvelopePrepared,
            ExplicitPreviewSwitchEnabled: explicitPreviewSwitchEnabled,
            GeneratedDiffProvided: generatedDiffProvided,
            GeneratedDiffAccepted: accepted,
            GeneratedDiffBytes: generatedDiffBytes,
            MaxGeneratedDiffBytes: AbsoluteMaxPatchBytes,
            DiffFileReadEnabled: false,
            InlineDiffAccepted: false,
            ForwardToValidationPreview: accepted,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            DiffPreview: accepted
                ? generatedDiff!.Length > 1200 ? generatedDiff[..1200] : generatedDiff
                : null,
            Blocker: status switch
            {
                "READ_REQUIRED" => "bounded selected file reads and patch proposal context are required",
                "PLANNER_SOURCE_REQUIRED" => "a local-model or server-planner output envelope is required",
                "GENERATED_DIFF_NOT_PROVIDED" => "a generated in-memory unified diff is required",
                "EXPLICIT_SWITCH_REQUIRED" => "the explicit generated-diff preview switch is required",
                "BLOCKED_SIZE_LIMIT" => "generated diff exceeds the preview size limit",
                _ => null
            },
            Reason: status switch
            {
                "READ_REQUIRED" => "Generated diff acceptance requires bounded selected file reads and a ready planner output envelope first.",
                "PLANNER_SOURCE_REQUIRED" => "Generated diff acceptance is limited to local-model or server-planner output envelopes.",
                "GENERATED_DIFF_NOT_PROVIDED" => "The planner output envelope is ready, but no generated in-memory diff body was provided.",
                "EXPLICIT_SWITCH_REQUIRED" => "A generated diff body was provided, but it is not accepted unless --accept-generated-diff-preview is present.",
                "BLOCKED_SIZE_LIMIT" => "The generated diff body is larger than the bounded preview limit, so it is not forwarded to validation.",
                _ => "The generated in-memory planner diff is accepted for validation preview only. Diff-file reads, inline diff acceptance, request creation, mutation, tests, final publication, and partial reindex remain disabled."
            });
    }

    private static CliCodexPlannerDiffValidationHandoffPreview BuildCliCodexPlannerDiffValidationHandoffPreview(
        CliCodexPlannerDiffOutputPreview planner,
        CliCodexGeneratedDiffAcceptancePreview acceptance,
        CliCodexDiffSourceValidationPreview validation)
    {
        var plannerOutputReady = planner.OutputEnvelopePrepared && planner.PlannerSourceRequested;
        var diffAvailable = acceptance.GeneratedDiffAccepted;
        var status = !planner.ReadContextReady
            ? "READ_REQUIRED"
            : !plannerOutputReady
                ? "PLANNER_OUTPUT_REQUIRED"
                : !diffAvailable
                    ? "HANDOFF_DISABLED_NO_DIFF"
                    : validation.DiffTouchesOnlyTargetFiles
                        ? "HANDOFF_VALIDATED_PREVIEW"
                        : "HANDOFF_VALIDATION_BLOCKED";
        return new CliCodexPlannerDiffValidationHandoffPreview(
            Schema: "learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1",
            Status: status,
            Goal: planner.Goal,
            TargetFiles: planner.TargetFiles,
            RequestedSource: planner.RequestedSource,
            PlannerOutputRequired: true,
            PlannerOutputEnvelopePrepared: planner.OutputEnvelopePrepared,
            DiffBodyAvailable: diffAvailable,
            ValidationInputPrepared: diffAvailable,
            ValidationForwardingEnabled: diffAvailable,
            ValidationAttempted: diffAvailable,
            DiffValidationPassed: validation.DiffTouchesOnlyTargetFiles,
            PatchApplyInputPrepared: validation.PatchApplyInputPrepared,
            RequestCreationEnabled: false,
            MutationAllowed: false,
            Blocker: status switch
            {
                "READ_REQUIRED" => "bounded selected file reads and patch proposal context are required",
                "PLANNER_OUTPUT_REQUIRED" => "a local-model or server-planner output envelope is required",
                "HANDOFF_DISABLED_NO_DIFF" => acceptance.Blocker ?? "planner output envelope is present but no accepted diff body is available",
                "HANDOFF_VALIDATION_BLOCKED" => validation.ParseError ?? "generated diff failed validation",
                _ => null
            },
            Reason: status switch
            {
                "READ_REQUIRED" => "Planner-to-validation handoff requires bounded selected file reads and a ready patch proposal first.",
                "PLANNER_OUTPUT_REQUIRED" => "Planner-to-validation handoff is skipped until a local-model or server-planner output envelope is requested.",
                "HANDOFF_DISABLED_NO_DIFF" => "Planner output envelope shape is ready, but no generated diff body has passed the explicit acceptance preview, so validation input is not prepared.",
                "HANDOFF_VALIDATION_BLOCKED" => "The accepted generated diff was forwarded to validation preview, but it did not pass target/path parsing guards.",
                _ => "The accepted generated diff was forwarded to validation preview and passed selected-target guards. Request creation, snapshot-writing dry-run, mutation, tests, final publication, and partial reindex remain disabled."
            });
    }

    private static CliCodexDiffSourceValidationPreview BuildCliCodexDiffSourceValidationPreview(
        CliCodexPatchProposalPreview proposal,
        string? proposedUnifiedDiff = null)
    {
        if (!proposal.ProposalPrepared)
        {
            return new CliCodexDiffSourceValidationPreview(
                Schema: "learnbot.local-agent.codex-diff-source-validation-preview.v1",
                Status: "READ_REQUIRED",
                Goal: proposal.Goal,
                TargetFiles: proposal.TargetFiles,
                DiffProvided: false,
                DiffParsed: false,
                ParseError: null,
                TouchedFiles: [],
                RejectedFiles: [],
                DiffTouchesOnlyTargetFiles: false,
                DiffPreview: null,
                UnifiedDiffRequired: true,
                DryRunOnly: true,
                PatchApplyInputPrepared: false,
                PatchDryRunExecutionEnabled: false,
                RequestCreationEnabled: false,
                ApprovalRequiredBeforeMutation: true,
                MutationAllowed: false,
                TestExecutionEnabled: false,
                LocalModelPlanningEnabled: false,
                ServerPlannerEnabled: false,
                Reason: "Diff source validation requires a ready patch proposal from bounded selected file reads first.");
        }

        if (string.IsNullOrWhiteSpace(proposedUnifiedDiff))
        {
            return new CliCodexDiffSourceValidationPreview(
                Schema: "learnbot.local-agent.codex-diff-source-validation-preview.v1",
                Status: "DIFF_SOURCE_REQUIRED",
                Goal: proposal.Goal,
                TargetFiles: proposal.TargetFiles,
                DiffProvided: false,
                DiffParsed: false,
                ParseError: null,
                TouchedFiles: [],
                RejectedFiles: [],
                DiffTouchesOnlyTargetFiles: false,
                DiffPreview: null,
                UnifiedDiffRequired: true,
                DryRunOnly: true,
                PatchApplyInputPrepared: false,
                PatchDryRunExecutionEnabled: false,
                RequestCreationEnabled: false,
                ApprovalRequiredBeforeMutation: true,
                MutationAllowed: false,
                TestExecutionEnabled: false,
                LocalModelPlanningEnabled: false,
                ServerPlannerEnabled: false,
                Reason: "A future local-model or server-planner unified diff must be supplied before patch.apply dry-run input can be prepared.");
        }

        var parsed = ParseUnifiedDiff(proposedUnifiedDiff);
        var touchedFiles = parsed.Success
            ? parsed.Files.Select(file => file.Path.Replace('\\', '/')).Distinct(StringComparer.OrdinalIgnoreCase).ToList()
            : new List<string>();
        var rejectedFiles = touchedFiles
            .Where(file => !proposal.TargetFiles.Contains(file, StringComparer.OrdinalIgnoreCase))
            .ToList();
        var accepted = parsed.Success && touchedFiles.Count > 0 && rejectedFiles.Count == 0;
        return new CliCodexDiffSourceValidationPreview(
            Schema: "learnbot.local-agent.codex-diff-source-validation-preview.v1",
            Status: accepted
                ? "VALID_DIFF_SOURCE_PREVIEW"
                : parsed.Success ? "BLOCKED_TARGET_MISMATCH" : "BLOCKED_INVALID_DIFF",
            Goal: proposal.Goal,
            TargetFiles: proposal.TargetFiles,
            DiffProvided: true,
            DiffParsed: parsed.Success,
            ParseError: parsed.Error,
            TouchedFiles: touchedFiles,
            RejectedFiles: rejectedFiles,
            DiffTouchesOnlyTargetFiles: accepted,
            DiffPreview: proposedUnifiedDiff.Length > 1200 ? proposedUnifiedDiff[..1200] : proposedUnifiedDiff,
            UnifiedDiffRequired: true,
            DryRunOnly: true,
            PatchApplyInputPrepared: accepted,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            LocalModelPlanningEnabled: false,
            ServerPlannerEnabled: false,
            Reason: accepted
                ? "The supplied unified diff parses and touches only selected target files, so patch.apply dry-run input may be prepared in a later guarded step; execution remains disabled here."
                : parsed.Success
                    ? "The supplied unified diff touches files outside the selected target set, so patch.apply dry-run input is not prepared."
                    : "The supplied diff is not a valid unified diff, so patch.apply dry-run input is not prepared.");
    }

    private static CliCodexPatchDryRunRequestEnvelopePreview BuildCliCodexPatchDryRunRequestEnvelopePreview(
        CliCodexDiffSourceValidationPreview validation,
        Guid? workspaceId)
    {
        var validationPassed = validation.PatchApplyInputPrepared && validation.DiffTouchesOnlyTargetFiles;
        var status = validation.Status == "READ_REQUIRED"
            ? "READ_REQUIRED"
            : !validationPassed
                ? "DIFF_VALIDATION_REQUIRED"
                : "DRY_RUN_REQUEST_ENVELOPE_PREPARED";
        IReadOnlyDictionary<string, object?>? envelope = null;
        if (validationPassed)
        {
            envelope = new Dictionary<string, object?>
            {
                ["schema"] = "learnbot.local-agent.patch-apply-dry-run-request-envelope.v1",
                ["toolName"] = "patch.apply",
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["approvalState"] = "REQUIRED_BEFORE_SNAPSHOT_DRY_RUN",
                ["workspaceId"] = workspaceId?.ToString() ?? "<workspace-id>",
                ["input"] = new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceId?.ToString() ?? "<workspace-id>",
                    ["dryRunOnly"] = true,
                    ["allowMutation"] = false,
                    ["targetFiles"] = validation.TargetFiles,
                    ["diffPreview"] = validation.DiffPreview,
                    ["maxPatchBytes"] = AbsoluteMaxPatchBytes
                },
                ["requestCreationEnabled"] = false,
                ["enqueueEnabled"] = false,
                ["claimable"] = false,
                ["snapshotCreationEnabled"] = false,
                ["patchDryRunExecutionEnabled"] = false,
                ["mutationAllowed"] = false
            };
        }

        return new CliCodexPatchDryRunRequestEnvelopePreview(
            Schema: "learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1",
            Status: status,
            Goal: validation.Goal,
            WorkspaceId: workspaceId,
            ToolName: "patch.apply",
            ExecutionTarget: "USER_LOCAL_AGENT",
            ApprovalState: validationPassed ? "REQUIRED_BEFORE_SNAPSHOT_DRY_RUN" : "NOT_PREPARED",
            TargetFiles: validation.TargetFiles,
            DiffValidationRequired: true,
            DiffValidationPassed: validationPassed,
            RequestEnvelopePrepared: validationPassed,
            PatchApplyInputPrepared: validation.PatchApplyInputPrepared,
            DryRunOnly: true,
            SnapshotCreationRequiredForFullDryRun: validationPassed,
            SnapshotCreationEnabled: false,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            EnqueueEnabled: false,
            Claimable: false,
            ApprovalRequiredBeforeDryRun: true,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            RequestEnvelopePreview: envelope,
            Blocker: status switch
            {
                "READ_REQUIRED" => "bounded selected file reads and patch proposal context are required",
                "DIFF_VALIDATION_REQUIRED" => "a validated generated diff touching only selected target files is required",
                _ => null
            },
            Reason: status switch
            {
                "READ_REQUIRED" => "patch.apply dry-run request envelope preview requires selected file reads, patch proposal context, and diff validation first.",
                "DIFF_VALIDATION_REQUIRED" => "patch.apply dry-run request envelope preview is blocked until the accepted generated diff passes validation.",
                _ => "patch.apply dry-run request envelope shape is prepared from the validated generated diff, but request creation, enqueue, claim, snapshot creation, dry-run execution, mutation, tests, final publication, and partial reindex remain disabled."
            });
    }

    private CliCodexPatchDryRunPreflightPreview BuildCliCodexPatchDryRunPreflightPreview(
        AgentConfig? config,
        Guid? workspaceId,
        CliCodexDiffSourceValidationPreview validation,
        string? proposedUnifiedDiff = null)
    {
        if (!validation.PatchApplyInputPrepared || !validation.DiffTouchesOnlyTargetFiles)
        {
            return new CliCodexPatchDryRunPreflightPreview(
                Schema: "learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1",
                Status: "DIFF_VALIDATION_REQUIRED",
                Goal: validation.Goal,
                ToolName: "patch.apply",
                TargetFiles: validation.TargetFiles,
                Requested: false,
                ReadyForExecution: false,
                ExecutionAttempted: false,
                DiffValidationRequired: true,
                DiffValidationPassed: false,
                NonWritingPreflightOnly: true,
                FileReadAttempted: false,
                ContextValidationAttempted: false,
                PreflightPassed: false,
                Files: [],
                SnapshotCreated: false,
                MutationApplied: false,
                PatchApplyInputPrepared: false,
                PatchDryRunExecutionEnabled: false,
                RequestCreationEnabled: false,
                ApprovalRequiredBeforeMutation: true,
                MutationAllowed: false,
                TestExecutionEnabled: false,
                FinalReportPublicationEnabled: false,
                PartialReindexEnabled: false,
                FailureCode: null,
                Error: null,
                Reason: "patch.apply preflight requires a validated unified diff that touches only selected target files.");
        }

        if (config is null || workspaceId is null || string.IsNullOrWhiteSpace(proposedUnifiedDiff))
        {
            return new CliCodexPatchDryRunPreflightPreview(
                Schema: "learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1",
                Status: "PREFLIGHT_INPUT_REQUIRED",
                Goal: validation.Goal,
                ToolName: "patch.apply",
                TargetFiles: validation.TargetFiles,
                Requested: false,
                ReadyForExecution: false,
                ExecutionAttempted: false,
                DiffValidationRequired: true,
                DiffValidationPassed: true,
                NonWritingPreflightOnly: true,
                FileReadAttempted: false,
                ContextValidationAttempted: false,
                PreflightPassed: false,
                Files: [],
                SnapshotCreated: false,
                MutationApplied: false,
                PatchApplyInputPrepared: true,
                PatchDryRunExecutionEnabled: false,
                RequestCreationEnabled: false,
                ApprovalRequiredBeforeMutation: true,
                MutationAllowed: false,
                TestExecutionEnabled: false,
                FinalReportPublicationEnabled: false,
                PartialReindexEnabled: false,
                FailureCode: null,
                Error: null,
                Reason: "Validated diff shape is available, but explicit workspace config and diff body are required before non-writing preflight can run.");
        }

        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_WORKSPACE", workspace.FailureCode, workspace.Error);
        }

        var parsed = ParseUnifiedDiff(proposedUnifiedDiff);
        if (!parsed.Success)
        {
            return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_INVALID_DIFF", "TOOL_FAILED", parsed.Error ?? "Invalid unified diff.");
        }

        var fileResults = new List<IReadOnlyDictionary<string, object?>>();
        foreach (var file in parsed.Files)
        {
            if (!validation.TargetFiles.Contains(file.Path, StringComparer.OrdinalIgnoreCase))
            {
                return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_TARGET_MISMATCH", "PATH_ESCAPE", "Patch modifies a file outside selected target files: " + file.Path, fileResults);
            }

            var target = Path.GetFullPath(Path.Combine(workspace.Root!, file.Path));
            if (!IsWithin(workspace.Root!, target))
            {
                return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_PATH_ESCAPE", "PATH_ESCAPE", "Patch path escapes the approved workspace: " + file.Path, fileResults);
            }
            if (!File.Exists(target))
            {
                return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_TARGET_MISSING", "TOOL_FAILED", "Patch target file was not found: " + file.Path, fileResults);
            }

            var bytes = File.ReadAllBytes(target);
            if (bytes.Any(value => value == 0))
            {
                return BuildFailedCliCodexPatchDryRunPreflight(validation, "BLOCKED_BINARY_FILE", "TOOL_FAILED", "Binary files are not supported by patch.apply preflight: " + file.Path, fileResults);
            }

            var content = Encoding.UTF8.GetString(bytes);
            var hunkResults = file.Hunks.Select(hunk => DryRunHunk(SplitLines(content), hunk)).ToList();
            var contextMatches = hunkResults.All(item => item.ContextMatches);
            fileResults.Add(new Dictionary<string, object?>
            {
                ["path"] = file.Path,
                ["absolutePath"] = target,
                ["actualSha256"] = Sha256Hex(bytes),
                ["bytes"] = bytes.LongLength,
                ["contextMatches"] = contextMatches,
                ["hunks"] = hunkResults.Select(item => new Dictionary<string, object?>
                {
                    ["oldStart"] = item.OldStart,
                    ["oldLineCount"] = item.OldLineCount,
                    ["contextMatches"] = item.ContextMatches,
                    ["message"] = item.Message
                }).ToList()
            });

            if (!contextMatches)
            {
                return BuildFailedCliCodexPatchDryRunPreflight(validation, "CONTEXT_MISMATCH", "CONTEXT_MISMATCH", "Patch context did not match local file: " + file.Path, fileResults);
            }
        }

        return new CliCodexPatchDryRunPreflightPreview(
            Schema: "learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1",
            Status: "PREFLIGHT_PASSED",
            Goal: validation.Goal,
            ToolName: "patch.apply",
            TargetFiles: validation.TargetFiles,
            Requested: true,
            ReadyForExecution: true,
            ExecutionAttempted: true,
            DiffValidationRequired: true,
            DiffValidationPassed: true,
            NonWritingPreflightOnly: true,
            FileReadAttempted: true,
            ContextValidationAttempted: true,
            PreflightPassed: true,
            Files: fileResults,
            SnapshotCreated: false,
            MutationApplied: false,
            PatchApplyInputPrepared: true,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            FailureCode: null,
            Error: null,
            Reason: "Validated diff context matches local target files. This is a non-writing preflight only; snapshot creation, patch.apply execution, mutation, tests, final report, and partial reindex remain disabled.");
    }

    private static CliCodexPatchDryRunApprovalHandoffPreview BuildCliCodexPatchDryRunApprovalHandoffPreview(
        CliCodexPatchDryRunRequestEnvelopePreview requestEnvelope,
        CliCodexPatchDryRunPreflightPreview preflight,
        Guid? repositoryId)
    {
        var envelopeReady = requestEnvelope.RequestEnvelopePrepared;
        var preflightPassed = preflight.PreflightPassed;
        var status = requestEnvelope.Status == "READ_REQUIRED"
            ? "READ_REQUIRED"
            : !envelopeReady
                ? "DRY_RUN_ENVELOPE_REQUIRED"
                : !preflight.ExecutionAttempted
                    ? "NONWRITING_PREFLIGHT_REQUIRED"
                    : !preflightPassed
                        ? "NONWRITING_PREFLIGHT_FAILED"
                        : "APPROVAL_HANDOFF_PREPARED";
        var handoffPrepared = status == "APPROVAL_HANDOFF_PREPARED";
        IReadOnlyDictionary<string, object?>? handoff = null;
        if (handoffPrepared)
        {
            handoff = new Dictionary<string, object?>
            {
                ["schema"] = "learnbot.local-agent.patch-dry-run-approval-handoff.v1",
                ["repositoryId"] = repositoryId?.ToString() ?? "<repository-id>",
                ["workspaceId"] = requestEnvelope.WorkspaceId?.ToString() ?? "<workspace-id>",
                ["toolName"] = "patch.apply",
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["approvalKind"] = "SNAPSHOT_WRITING_DRY_RUN",
                ["approvalState"] = "AWAITING_USER_APPROVAL",
                ["targetFiles"] = requestEnvelope.TargetFiles,
                ["requestEnvelopeStatus"] = requestEnvelope.Status,
                ["nonWritingPreflightStatus"] = preflight.Status,
                ["requestEnvelopePreview"] = requestEnvelope.RequestEnvelopePreview,
                ["requestCreationEnabled"] = false,
                ["approvalRequestCreationEnabled"] = false,
                ["enqueueEnabled"] = false,
                ["claimable"] = false,
                ["snapshotCreationEnabled"] = false,
                ["patchDryRunExecutionEnabled"] = false,
                ["mutationAllowed"] = false
            };
        }

        return new CliCodexPatchDryRunApprovalHandoffPreview(
            Schema: "learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1",
            Status: status,
            Goal: requestEnvelope.Goal,
            WorkspaceId: requestEnvelope.WorkspaceId,
            RepositoryId: repositoryId,
            ToolName: "patch.apply",
            ExecutionTarget: "USER_LOCAL_AGENT",
            ApprovalKind: "SNAPSHOT_WRITING_DRY_RUN",
            ApprovalState: handoffPrepared ? "AWAITING_USER_APPROVAL" : "NOT_PREPARED",
            TargetFiles: requestEnvelope.TargetFiles,
            DiffValidationPassed: requestEnvelope.DiffValidationPassed,
            RequestEnvelopePrepared: envelopeReady,
            NonWritingPreflightRequired: true,
            NonWritingPreflightPassed: preflightPassed,
            ApprovalHandoffPrepared: handoffPrepared,
            DryRunApprovalRequired: true,
            MutationApprovalRequired: true,
            RequestCreationEnabled: false,
            ApprovalRequestCreationEnabled: false,
            EnqueueEnabled: false,
            Claimable: false,
            SnapshotCreationEnabled: false,
            PatchDryRunExecutionEnabled: false,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            HandoffPreview: handoff,
            Blocker: status switch
            {
                "READ_REQUIRED" => "bounded selected file reads and patch proposal context are required",
                "DRY_RUN_ENVELOPE_REQUIRED" => "validated patch.apply dry-run request envelope is required",
                "NONWRITING_PREFLIGHT_REQUIRED" => "explicit non-writing preflight must pass before approval handoff",
                "NONWRITING_PREFLIGHT_FAILED" => preflight.Error ?? preflight.FailureCode ?? "non-writing preflight failed",
                _ => null
            },
            Reason: status switch
            {
                "READ_REQUIRED" => "Approval handoff requires the read-only discovery/read and patch proposal context first.",
                "DRY_RUN_ENVELOPE_REQUIRED" => "Approval handoff is blocked until a validated generated diff prepares the patch.apply dry-run request envelope.",
                "NONWRITING_PREFLIGHT_REQUIRED" => "Approval handoff is blocked until the validated diff passes the explicit non-writing local context preflight.",
                "NONWRITING_PREFLIGHT_FAILED" => "Approval handoff is blocked because the non-writing preflight did not pass local file context validation.",
                _ => "Snapshot-writing patch.apply dry-run approval handoff is prepared from the validated request envelope and non-writing preflight result, but request creation, approval persistence, enqueue, claim, snapshot creation, dry-run execution, mutation, tests, final publication, and partial reindex remain disabled."
            });
    }

    private static CliCodexPatchDryRunPreflightPreview BuildFailedCliCodexPatchDryRunPreflight(
        CliCodexDiffSourceValidationPreview validation,
        string status,
        string? failureCode,
        string? error,
        IReadOnlyList<IReadOnlyDictionary<string, object?>>? files = null) =>
        new(
            Schema: "learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1",
            Status: status,
            Goal: validation.Goal,
            ToolName: "patch.apply",
            TargetFiles: validation.TargetFiles,
            Requested: true,
            ReadyForExecution: true,
            ExecutionAttempted: true,
            DiffValidationRequired: true,
            DiffValidationPassed: validation.PatchApplyInputPrepared,
            NonWritingPreflightOnly: true,
            FileReadAttempted: files is not null && files.Count > 0,
            ContextValidationAttempted: files is not null && files.Count > 0,
            PreflightPassed: false,
            Files: files ?? [],
            SnapshotCreated: false,
            MutationApplied: false,
            PatchApplyInputPrepared: validation.PatchApplyInputPrepared,
            PatchDryRunExecutionEnabled: false,
            RequestCreationEnabled: false,
            ApprovalRequiredBeforeMutation: true,
            MutationAllowed: false,
            TestExecutionEnabled: false,
            FinalReportPublicationEnabled: false,
            PartialReindexEnabled: false,
            FailureCode: failureCode,
            Error: error,
            Reason: "patch.apply non-writing preflight failed before any snapshot, mutation, test, final report, or partial reindex execution.");

    private static long? TryLongOutput(Dictionary<string, object?> output, string key) =>
        output.TryGetValue(key, out var value)
            ? value switch
            {
                long longValue => longValue,
                int intValue => intValue,
                _ => null
            }
            : null;

    private static int? TryIntOutput(Dictionary<string, object?> output, string key) =>
        output.TryGetValue(key, out var value)
            ? value switch
            {
                int intValue => intValue,
                long longValue when longValue <= int.MaxValue && longValue >= int.MinValue => (int)longValue,
                _ => null
            }
            : null;

    private int FileCommand(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Usage: learnbot file read --workspace-id <workspace-id> --path <relative-path>");
            return 2;
        }
        return args[0].ToLowerInvariant() switch
        {
            "read" => FileReadCommand(args[1..]),
            "tree" => FileTreeCommand(args[1..]),
            "search" => FileSearchCommand(args[1..]),
            _ => Unknown("file " + args[0])
        };
    }

    private int FileReadCommand(string[] args)
    {
        var workspaceIdText = GetOption(args, "--workspace-id");
        var path = GetOption(args, "--path");
        if (!Guid.TryParse(workspaceIdText, out var workspaceId) || string.IsNullOrWhiteSpace(path))
        {
            Console.Error.WriteLine("Usage: learnbot file read --workspace-id <workspace-id> --path <relative-path>");
            return 2;
        }

        var result = ReadWorkspaceFile(LoadConfigOrDefault(), workspaceId, path, DefaultMaxReadBytes);
        if (!result.Success)
        {
            Console.Error.WriteLine(result.Error);
            return 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(result.Output, JsonOptions));
        return 0;
    }

    private int FileSearchCommand(string[] args)
    {
        var workspaceIdText = GetOption(args, "--workspace-id");
        var query = GetOption(args, "--query");
        if (!Guid.TryParse(workspaceIdText, out var workspaceId) || string.IsNullOrWhiteSpace(query))
        {
            Console.Error.WriteLine("Usage: learnbot file search --workspace-id <workspace-id> --query <text> [--path <relative-path>] [--max-matches <count>] [--max-files <count>]");
            return 2;
        }

        var result = SearchWorkspaceText(
            LoadConfigOrDefault(),
            workspaceId,
            query,
            GetOption(args, "--path") ?? ".",
            ParseInt(GetOption(args, "--max-matches"), DefaultMaxSearchMatches),
            ParseInt(GetOption(args, "--max-files"), DefaultMaxSearchFiles),
            ParseInt(GetOption(args, "--max-bytes-per-file"), DefaultMaxSearchFileBytes));
        if (!result.Success)
        {
            Console.Error.WriteLine(result.Error);
            return 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(result.Output, JsonOptions));
        return 0;
    }

    private int FileTreeCommand(string[] args)
    {
        var workspaceIdText = GetOption(args, "--workspace-id");
        if (!Guid.TryParse(workspaceIdText, out var workspaceId))
        {
            Console.Error.WriteLine("Usage: learnbot file tree --workspace-id <workspace-id> [--path <relative-path>] [--max-entries <count>] [--max-depth <depth>]");
            return 2;
        }

        var result = ReadWorkspaceTree(
            LoadConfigOrDefault(),
            workspaceId,
            GetOption(args, "--path") ?? ".",
            ParseInt(GetOption(args, "--max-entries"), DefaultMaxTreeEntries),
            ParseInt(GetOption(args, "--max-depth"), DefaultMaxTreeDepth));
        if (!result.Success)
        {
            Console.Error.WriteLine(result.Error);
            return 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(result.Output, JsonOptions));
        return 0;
    }

    private async Task<int> GitCommand(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Usage: learnbot git status|diff --workspace-id <workspace-id>");
            return 2;
        }
        return args[0].ToLowerInvariant() switch
        {
            "status" => await GitStatusCommand(args[1..]),
            "diff" => await GitDiffCommand(args[1..]),
            _ => Unknown("git " + args[0])
        };
    }

    private async Task<int> GitStatusCommand(string[] args)
    {
        var workspaceIdText = GetOption(args, "--workspace-id");
        if (!Guid.TryParse(workspaceIdText, out var workspaceId))
        {
            Console.Error.WriteLine("Usage: learnbot git status --workspace-id <workspace-id>");
            return 2;
        }

        var result = await ReadGitStatus(LoadConfigOrDefault(), workspaceId);
        if (!result.Success)
        {
            Console.Error.WriteLine(result.Error);
            return 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(result.Output, JsonOptions));
        return 0;
    }

    private async Task<int> GitDiffCommand(string[] args)
    {
        var workspaceIdText = GetOption(args, "--workspace-id");
        if (!Guid.TryParse(workspaceIdText, out var workspaceId))
        {
            Console.Error.WriteLine("Usage: learnbot git diff --workspace-id <workspace-id> [--path <relative-path>] [--max-bytes <bytes>]");
            return 2;
        }

        var result = await ReadGitDiff(
            LoadConfigOrDefault(),
            workspaceId,
            GetOption(args, "--path"),
            ParseInt(GetOption(args, "--max-bytes"), DefaultMaxDiffBytes));
        if (!result.Success)
        {
            Console.Error.WriteLine(result.Error);
            return 1;
        }
        Console.WriteLine(JsonSerializer.Serialize(result.Output, JsonOptions));
        return 0;
    }

    private int Open()
    {
        var server = LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083";
        Process.Start(new ProcessStartInfo(server) { UseShellExecute = true });
        return 0;
    }

    private async Task SendHeartbeat(
        AgentConfig config,
        string? configuredTransport = null,
        string? activeTransport = null,
        int? webSocketFailureCount = null,
        DateTimeOffset? nextWebSocketRetryAt = null)
    {
        using var client = Client(config);
        using var response = await client.PostAsync(
            "/api/local-agents/heartbeat",
            Json(HeartbeatPayload(config, configuredTransport, activeTransport, webSocketFailureCount, nextWebSocketRetryAt)));
        response.EnsureSuccessStatusCode();
    }

    private async Task<bool> TryRunWebSocketOnce(AgentConfig config, string transport, TimeSpan receiveWindow)
    {
        if (transport == "polling")
        {
            return false;
        }

        var websocketUrl = WebSocketUrl(config);
        try
        {
            using var socket = new ClientWebSocket();
            socket.Options.SetRequestHeader("X-Local-Agent-Token", config.Token ?? "");
            socket.Options.SetRequestHeader("User-Agent", $"learnbot-local-agent/{Version}");
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await socket.ConnectAsync(new Uri(websocketUrl), timeout.Token);
            await SendWebSocketEnvelope(socket, config.AgentId, "hello", null, HeartbeatPayload(config, transport, "websocket", 0, null), timeout.Token);
            var response = await ReceiveWebSocketText(socket, timeout.Token);
            if (string.IsNullOrWhiteSpace(response))
            {
                Log($"websocket hello received empty response url={websocketUrl}; falling back to polling");
                return false;
            }
            using var document = JsonDocument.Parse(response);
            var type = document.RootElement.TryGetProperty("type", out var typeElement) ? typeElement.GetString() : null;
            if (type == "tool.ack" || type == "pong")
            {
                Log($"websocket hello acknowledged url={websocketUrl}");
                Console.WriteLine("websocket heartbeat acknowledged; polling fallback remains active");
                await ReceiveWebSocketRequests(config, socket, receiveWindow);
                return true;
            }
            Log($"websocket hello was not acknowledged type={type ?? "unknown"} url={websocketUrl}; falling back to polling");
        }
        catch (Exception ex) when (ex is WebSocketException or HttpRequestException or OperationCanceledException or UriFormatException or JsonException or InvalidOperationException)
        {
            Log($"websocket connect failed url={websocketUrl}; falling back to polling: {ex.Message}");
            if (transport == "websocket")
            {
                Console.WriteLine("websocket transport failed; falling back to polling");
            }
        }
        return false;
    }

    private async Task<bool> PollOnce(AgentConfig config, bool quiet = false)
    {
        using var client = Client(config);
        using var response = await client.GetAsync("/api/local-agents/tools/next");
        if (response.StatusCode == System.Net.HttpStatusCode.NoContent)
        {
            Log("poll no tool request");
            if (!quiet) Console.WriteLine("no tool request");
            return false;
        }
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var requestId = document.RootElement.GetProperty("requestId").GetGuid();
        var request = document.RootElement.GetProperty("request");
        var toolName = request.GetProperty("toolName").GetString() ?? "";
        var result = HandleTool(config, requestId, request, toolName);
        using var complete = await client.PostAsync($"/api/local-agents/tools/{requestId}/response", Json(result));
        complete.EnsureSuccessStatusCode();
        Log($"tool {toolName} {result.Status} requestId={requestId}");
        if (!quiet) Console.WriteLine($"{toolName}: {result.Status}");
        return true;
    }

    private async Task ReceiveWebSocketRequests(AgentConfig config, ClientWebSocket socket, TimeSpan receiveWindow)
    {
        var deadline = DateTimeOffset.UtcNow.Add(receiveWindow);
        while (socket.State == WebSocketState.Open && DateTimeOffset.UtcNow < deadline)
        {
            var remaining = deadline - DateTimeOffset.UtcNow;
            if (remaining <= TimeSpan.Zero) break;
            using var timeout = new CancellationTokenSource(remaining < TimeSpan.FromSeconds(5) ? remaining : TimeSpan.FromSeconds(5));
            string? message;
            try
            {
                message = await ReceiveWebSocketText(socket, timeout.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            if (string.IsNullOrWhiteSpace(message))
            {
                break;
            }
            using var document = JsonDocument.Parse(message);
            var envelope = document.RootElement;
            var type = envelope.TryGetProperty("type", out var typeElement) ? typeElement.GetString() : "";
            switch (type)
            {
                case "tool.request":
                    await HandleWebSocketToolRequest(config, socket, envelope);
                    break;
                case "ping":
                    using (var sendTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(5)))
                    {
                        await SendWebSocketEnvelope(socket, config.AgentId, "pong", TryEnvelopeRequestId(envelope), new { }, sendTimeout.Token);
                    }
                    break;
                case "tool.ack":
                    Log("websocket tool ack received");
                    break;
                case "error":
                    Log("websocket error received: " + envelope.GetProperty("payload").ToString());
                    break;
                default:
                    Log("websocket ignored message type=" + type);
                    break;
            }
        }
    }

    private async Task HandleWebSocketToolRequest(AgentConfig config, ClientWebSocket socket, JsonElement envelope)
    {
        var requestId = TryEnvelopeRequestId(envelope);
        if (requestId is null || !envelope.TryGetProperty("payload", out var payload) || !payload.TryGetProperty("request", out var request))
        {
            Log("websocket tool.request missing request payload");
            return;
        }
        var toolName = request.GetProperty("toolName").GetString() ?? "";
        var result = HandleTool(config, requestId.Value, request, toolName);
        using var sendTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await SendWebSocketEnvelope(socket, config.AgentId, "tool.response", requestId, result, sendTimeout.Token);
        Log($"websocket tool {toolName} {result.Status} requestId={requestId}");
        Console.WriteLine($"{toolName}: {result.Status}");
    }

    private static object HeartbeatPayload(
        AgentConfig config,
        string? configuredTransport = null,
        string? activeTransport = null,
        int? webSocketFailureCount = null,
        DateTimeOffset? nextWebSocketRetryAt = null)
    {
        var state = LoadRunState();
        return new
        {
            agentId = config.AgentId,
            version = config.Version,
            capabilities = new[] { "agent.status", "agent.doctor", "workspace.list", "workspace.tree", "workspace.search", "file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore" },
            workspaces = config.Workspaces.Select(workspace => new
            {
                workspace.WorkspaceId,
                workspace.Name,
                rootPath = workspace.Path,
                workspace.Approved
            }),
            configuredTransport = NormalizeTransport(configuredTransport ?? state?.ConfiguredTransport ?? config.Transport),
            activeTransport = activeTransport ?? state?.ActiveTransport,
            webSocketFailureCount = webSocketFailureCount ?? state?.WebSocketFailureCount ?? 0,
            nextWebSocketRetryAt = nextWebSocketRetryAt ?? state?.NextWebSocketRetryAt
        };
    }

    private static async Task SendWebSocketEnvelope(
        ClientWebSocket socket,
        Guid agentId,
        string type,
        Guid? requestId,
        object payload,
        CancellationToken cancellationToken)
    {
        var envelope = new
        {
            type,
            messageId = Guid.NewGuid().ToString(),
            agentId,
            requestId,
            sentAt = DateTimeOffset.UtcNow,
            payload
        };
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(envelope, JsonOptions));
        await socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
    }

    private static async Task<string?> ReceiveWebSocketText(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        using var stream = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                return null;
            }
            stream.Write(buffer, 0, result.Count);
            if (stream.Length > 64 * 1024)
            {
                throw new InvalidOperationException("WebSocket response exceeded the local safety limit.");
            }
        } while (!result.EndOfMessage);
        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private static string? CryptProtect(string value, out string? error)
    {
        error = null;
        var bytes = Encoding.UTF8.GetBytes(value);
        var input = new DataBlob(bytes);
        var output = new DataBlob();
        try
        {
            if (!CryptProtectData(ref input, "LearnBot CLI web session", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output))
            {
                error = "Windows DPAPI CryptProtectData failed.";
                return null;
            }
            var protectedBytes = new byte[output.cbData];
            Marshal.Copy(output.pbData, protectedBytes, 0, protectedBytes.Length);
            return Convert.ToBase64String(protectedBytes);
        }
        finally
        {
            input.Free();
            output.Free();
        }
    }

    private static string? CryptUnprotect(string value)
    {
        var bytes = Convert.FromBase64String(value);
        var input = new DataBlob(bytes);
        var output = new DataBlob();
        try
        {
            if (!CryptUnprotectData(ref input, out _, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref output))
            {
                return null;
            }
            var plainBytes = new byte[output.cbData];
            Marshal.Copy(output.pbData, plainBytes, 0, plainBytes.Length);
            return Encoding.UTF8.GetString(plainBytes);
        }
        finally
        {
            input.Free();
            output.Free();
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int cbData;
        public IntPtr pbData;
        private bool allocatedByMarshal;

        public DataBlob(byte[] data)
        {
            cbData = data.Length;
            pbData = Marshal.AllocHGlobal(data.Length);
            allocatedByMarshal = true;
            Marshal.Copy(data, 0, pbData, data.Length);
        }

        public void Free()
        {
            if (pbData != IntPtr.Zero)
            {
                if (allocatedByMarshal)
                {
                    Marshal.FreeHGlobal(pbData);
                }
                else
                {
                    LocalFree(pbData);
                }
                pbData = IntPtr.Zero;
            }
            cbData = 0;
            allocatedByMarshal = false;
        }
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DataBlob pDataIn,
        string? szDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DataBlob pDataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DataBlob pDataIn,
        out string? ppszDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DataBlob pDataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);

    private ToolResponse HandleTool(AgentConfig config, Guid requestId, JsonElement request, string toolName)
    {
        var startedAt = DateTimeOffset.UtcNow;
        var output = new Dictionary<string, object?>();
        var status = "SUCCEEDED";
        string? failureCode = null;
        string? error = null;

        switch (toolName)
        {
            case "agent.status":
                output["version"] = Version;
                output["workspaceCount"] = config.Workspaces.Count;
                output["safeMode"] = true;
                break;
            case "agent.doctor":
                output["configPath"] = ConfigPath();
                output["paired"] = !string.IsNullOrWhiteSpace(config.Token);
                output["safeMode"] = true;
                break;
            case "workspace.list":
                output["workspaces"] = config.Workspaces;
                break;
            case "workspace.tree":
                var tree = ReadWorkspaceTree(
                    config,
                    TryGuid(request, "workspaceId"),
                    TryInputString(request, "path") ?? ".",
                    TryInputInt(request, "maxEntries") ?? DefaultMaxTreeEntries,
                    TryInputInt(request, "maxDepth") ?? DefaultMaxTreeDepth);
                if (tree.Success)
                {
                    foreach (var item in tree.Output) output[item.Key] = item.Value;
                }
                else
                {
                    status = tree.Status;
                    failureCode = tree.FailureCode;
                    error = tree.Error;
                }
                break;
            case "workspace.search":
                var search = SearchWorkspaceText(
                    config,
                    TryGuid(request, "workspaceId"),
                    TryInputString(request, "query") ?? "",
                    TryInputString(request, "path") ?? ".",
                    TryInputInt(request, "maxMatches") ?? DefaultMaxSearchMatches,
                    TryInputInt(request, "maxFiles") ?? DefaultMaxSearchFiles,
                    TryInputInt(request, "maxBytesPerFile") ?? DefaultMaxSearchFileBytes);
                if (search.Success)
                {
                    foreach (var item in search.Output) output[item.Key] = item.Value;
                }
                else
                {
                    status = search.Status;
                    failureCode = search.FailureCode;
                    error = search.Error;
                }
                break;
            case "file.read":
                var read = ReadWorkspaceFile(
                    config,
                    TryGuid(request, "workspaceId"),
                    TryInputString(request, "path") ?? "",
                    TryInputInt(request, "maxBytes") ?? DefaultMaxReadBytes);
                if (read.Success)
                {
                    foreach (var item in read.Output) output[item.Key] = item.Value;
                }
                else
                {
                    status = read.Status;
                    failureCode = read.FailureCode;
                    error = read.Error;
                }
                break;
            case "git.status":
                var gitStatus = ReadGitStatus(config, TryGuid(request, "workspaceId")).GetAwaiter().GetResult();
                if (gitStatus.Success)
                {
                    foreach (var item in gitStatus.Output) output[item.Key] = item.Value;
                }
                else
                {
                    status = gitStatus.Status;
                    failureCode = gitStatus.FailureCode;
                    error = gitStatus.Error;
                }
                break;
            case "git.diff":
                var gitDiff = ReadGitDiff(
                    config,
                    TryGuid(request, "workspaceId"),
                    TryInputString(request, "path"),
                    TryInputInt(request, "maxBytes") ?? DefaultMaxDiffBytes).GetAwaiter().GetResult();
                if (gitDiff.Success)
                {
                    foreach (var item in gitDiff.Output) output[item.Key] = item.Value;
                }
                else
                {
                    status = gitDiff.Status;
                    failureCode = gitDiff.FailureCode;
                    error = gitDiff.Error;
                }
                break;
            case "patch.apply":
                var patch = HandlePatchApply(config, TryGuid(request, "workspaceId"), request);
                foreach (var item in patch.Output) output[item.Key] = item.Value;
                status = patch.Status;
                failureCode = patch.FailureCode;
                error = patch.Error;
                break;
            case "rollback.restore":
                var rollback = RestoreRollbackSnapshot(config, TryGuid(request, "workspaceId"), request);
                foreach (var item in rollback.Output) output[item.Key] = item.Value;
                status = rollback.Status;
                failureCode = rollback.FailureCode;
                error = rollback.Error;
                break;
            case "command.runAllowed":
                var command = RunAllowedCommand(config, TryGuid(request, "workspaceId"), request);
                foreach (var item in command.Output) output[item.Key] = item.Value;
                status = command.Status;
                failureCode = command.FailureCode;
                error = command.Error;
                break;
            default:
                status = "REJECTED";
                failureCode = "UNSAFE_TOOL";
                error = "This Local Agent skeleton rejects unknown tools and arbitrary file, git, command, patch, test, and rollback operations by default.";
                break;
        }

        return new ToolResponse(
            request.GetProperty("sessionId").GetGuid(),
            requestId,
            request.GetProperty("userId").GetGuid(),
            config.AgentId,
            TryGuid(request, "workspaceId"),
            request.GetProperty("executionTarget").GetString() ?? "USER_LOCAL_AGENT",
            toolName,
            status,
            output,
            failureCode,
            error,
            startedAt,
            DateTimeOffset.UtcNow,
            Array.Empty<string>());
    }

    private const int DefaultMaxReadBytes = 200_000;
    private const int AbsoluteMaxReadBytes = 512_000;
    private const int DefaultMaxDiffBytes = 200_000;
    private const int AbsoluteMaxDiffBytes = 512_000;
    private const int AbsoluteMaxPatchBytes = 512_000;
    private const int DefaultMaxTreeEntries = 200;
    private const int AbsoluteMaxTreeEntries = 1000;
    private const int DefaultMaxTreeDepth = 4;
    private const int AbsoluteMaxTreeDepth = 12;
    private const int DefaultMaxSearchMatches = 25;
    private const int AbsoluteMaxSearchMatches = 200;
    private const int DefaultMaxSearchFiles = 300;
    private const int AbsoluteMaxSearchFiles = 2000;
    private const int DefaultMaxSearchFileBytes = 200_000;
    private const int AbsoluteMaxSearchFileBytes = 512_000;

    private static readonly HashSet<string> DefaultTreeExcludedDirectories = new(StringComparer.OrdinalIgnoreCase)
    {
        ".git",
        ".idea",
        ".vs",
        ".vscode",
        "bin",
        "build",
        "dist",
        "node_modules",
        "obj",
        "out",
        "target"
    };

    private ToolResult ReadWorkspaceTree(AgentConfig config, Guid? workspaceId, string requestedPath, int maxEntries, int maxDepth)
    {
        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var root = workspace.Root!;
        var target = Path.GetFullPath(Path.Combine(root, string.IsNullOrWhiteSpace(requestedPath) ? "." : requestedPath));
        if (!IsWithin(root, target))
        {
            return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Requested path escapes the approved workspace.");
        }
        if (!Directory.Exists(target))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Tree path was not found or is not a directory.");
        }

        var cappedEntries = Math.Clamp(maxEntries <= 0 ? DefaultMaxTreeEntries : maxEntries, 1, AbsoluteMaxTreeEntries);
        var cappedDepth = Math.Clamp(maxDepth <= 0 ? DefaultMaxTreeDepth : maxDepth, 0, AbsoluteMaxTreeDepth);
        var entries = new List<Dictionary<string, object?>>();
        var skippedDirectories = new List<string>();
        var truncated = false;

        WalkWorkspaceTree(root, target, 0, cappedDepth, cappedEntries, entries, skippedDirectories, ref truncated);

        return ToolResult.Ok(new Dictionary<string, object?>
        {
            ["workspaceId"] = workspace.Workspace!.WorkspaceId,
            ["path"] = target,
            ["relativePath"] = RelativeWorkspacePath(root, target),
            ["entries"] = entries,
            ["entryCount"] = entries.Count,
            ["maxEntries"] = cappedEntries,
            ["maxDepth"] = cappedDepth,
            ["truncated"] = truncated,
            ["excludedDirectories"] = DefaultTreeExcludedDirectories.OrderBy(item => item, StringComparer.OrdinalIgnoreCase).ToArray(),
            ["skippedDirectories"] = skippedDirectories
        });
    }

    private static void WalkWorkspaceTree(
        string root,
        string directory,
        int depth,
        int maxDepth,
        int maxEntries,
        List<Dictionary<string, object?>> entries,
        List<string> skippedDirectories,
        ref bool truncated)
    {
        if (entries.Count >= maxEntries)
        {
            truncated = true;
            return;
        }

        IEnumerable<string> children;
        try
        {
            children = Directory.EnumerateFileSystemEntries(directory)
                .OrderBy(path => Directory.Exists(path) ? 0 : 1)
                .ThenBy(path => Path.GetFileName(path), StringComparer.OrdinalIgnoreCase)
                .ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            skippedDirectories.Add(RelativeWorkspacePath(root, directory));
            return;
        }

        foreach (var child in children)
        {
            if (entries.Count >= maxEntries)
            {
                truncated = true;
                return;
            }
            if (!IsWithin(root, Path.GetFullPath(child)))
            {
                continue;
            }

            var isDirectory = Directory.Exists(child);
            var relativePath = RelativeWorkspacePath(root, child);
            if (isDirectory && DefaultTreeExcludedDirectories.Contains(Path.GetFileName(child)))
            {
                skippedDirectories.Add(relativePath);
                continue;
            }

            entries.Add(new Dictionary<string, object?>
            {
                ["path"] = relativePath,
                ["type"] = isDirectory ? "directory" : "file",
                ["bytes"] = isDirectory ? null : SafeFileLength(child)
            });

            if (isDirectory)
            {
                if (depth >= maxDepth)
                {
                    truncated = true;
                    continue;
                }
                WalkWorkspaceTree(root, child, depth + 1, maxDepth, maxEntries, entries, skippedDirectories, ref truncated);
            }
        }
    }

    private static long? SafeFileLength(string path)
    {
        try
        {
            return new FileInfo(path).Length;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static string RelativeWorkspacePath(string root, string path)
    {
        var relativePath = Path.GetRelativePath(root, path).Replace('\\', '/');
        return relativePath == "." ? "." : relativePath;
    }

    private ToolResult SearchWorkspaceText(
        AgentConfig config,
        Guid? workspaceId,
        string query,
        string requestedPath,
        int maxMatches,
        int maxFiles,
        int maxBytesPerFile)
    {
        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }
        if (string.IsNullOrWhiteSpace(query))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "query is required.");
        }

        var root = workspace.Root!;
        var target = Path.GetFullPath(Path.Combine(root, string.IsNullOrWhiteSpace(requestedPath) ? "." : requestedPath));
        if (!IsWithin(root, target))
        {
            return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Requested path escapes the approved workspace.");
        }

        var cappedMatches = Math.Clamp(maxMatches <= 0 ? DefaultMaxSearchMatches : maxMatches, 1, AbsoluteMaxSearchMatches);
        var cappedFiles = Math.Clamp(maxFiles <= 0 ? DefaultMaxSearchFiles : maxFiles, 1, AbsoluteMaxSearchFiles);
        var cappedBytes = Math.Clamp(maxBytesPerFile <= 0 ? DefaultMaxSearchFileBytes : maxBytesPerFile, 1, AbsoluteMaxSearchFileBytes);
        var files = Directory.Exists(target)
            ? EnumerateSearchFiles(root, target, cappedFiles + 1).ToList()
            : File.Exists(target)
                ? [target]
                : [];
        if (files.Count == 0)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Search path was not found.");
        }

        var matches = new List<Dictionary<string, object?>>();
        var scannedFiles = 0;
        var skippedFiles = 0;
        var truncated = files.Count > cappedFiles;
        foreach (var file in files.Take(cappedFiles))
        {
            var length = SafeFileLength(file);
            if (length is null || length.Value > cappedBytes)
            {
                skippedFiles++;
                continue;
            }

            byte[] bytes;
            try
            {
                bytes = File.ReadAllBytes(file);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                skippedFiles++;
                continue;
            }
            if (bytes.Any(value => value == 0))
            {
                skippedFiles++;
                continue;
            }

            scannedFiles++;
            var text = Encoding.UTF8.GetString(bytes);
            var lines = text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
            for (var lineIndex = 0; lineIndex < lines.Length; lineIndex++)
            {
                var line = lines[lineIndex];
                var column = line.IndexOf(query, StringComparison.OrdinalIgnoreCase);
                if (column < 0)
                {
                    continue;
                }

                matches.Add(new Dictionary<string, object?>
                {
                    ["path"] = RelativeWorkspacePath(root, file),
                    ["line"] = lineIndex + 1,
                    ["column"] = column + 1,
                    ["snippet"] = TrimSearchSnippet(line)
                });
                if (matches.Count >= cappedMatches)
                {
                    truncated = true;
                    break;
                }
            }
            if (matches.Count >= cappedMatches)
            {
                break;
            }
        }

        return ToolResult.Ok(new Dictionary<string, object?>
        {
            ["workspaceId"] = workspace.Workspace!.WorkspaceId,
            ["path"] = target,
            ["relativePath"] = RelativeWorkspacePath(root, target),
            ["query"] = query,
            ["matches"] = matches,
            ["matchCount"] = matches.Count,
            ["scannedFiles"] = scannedFiles,
            ["skippedFiles"] = skippedFiles,
            ["maxMatches"] = cappedMatches,
            ["maxFiles"] = cappedFiles,
            ["maxBytesPerFile"] = cappedBytes,
            ["truncated"] = truncated
        });
    }

    private static IEnumerable<string> EnumerateSearchFiles(string root, string directory, int maxFiles)
    {
        var pending = new Queue<string>();
        pending.Enqueue(directory);
        var yielded = 0;
        while (pending.Count > 0 && yielded < maxFiles)
        {
            var current = pending.Dequeue();
            IEnumerable<string> children;
            try
            {
                children = Directory.EnumerateFileSystemEntries(current)
                    .OrderBy(path => Directory.Exists(path) ? 0 : 1)
                    .ThenBy(path => Path.GetFileName(path), StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                continue;
            }

            foreach (var child in children)
            {
                var fullPath = Path.GetFullPath(child);
                if (!IsWithin(root, fullPath))
                {
                    continue;
                }
                if (Directory.Exists(fullPath))
                {
                    if (!DefaultTreeExcludedDirectories.Contains(Path.GetFileName(fullPath)))
                    {
                        pending.Enqueue(fullPath);
                    }
                    continue;
                }
                yielded++;
                yield return fullPath;
                if (yielded >= maxFiles)
                {
                    yield break;
                }
            }
        }
    }

    private static string TrimSearchSnippet(string line)
    {
        var trimmed = line.Trim();
        return trimmed.Length <= 240 ? trimmed : trimmed[..240];
    }

    private ToolResult ReadWorkspaceFile(AgentConfig config, Guid? workspaceId, string requestedPath, int maxBytes)
    {
        if (workspaceId is null)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "workspaceId is required.");
        }
        var workspace = config.Workspaces.FirstOrDefault(item => item.WorkspaceId == workspaceId.Value && item.Approved);
        if (workspace is null)
        {
            return ToolResult.Fail("REJECTED", "WORKSPACE_NOT_APPROVED", "Workspace is not approved.");
        }
        if (string.IsNullOrWhiteSpace(requestedPath))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "path is required.");
        }

        var root = Path.GetFullPath(workspace.Path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var target = Path.GetFullPath(Path.Combine(root, requestedPath));
        if (!IsWithin(root, target))
        {
            return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Requested path escapes the approved workspace.");
        }
        if (!File.Exists(target))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "File was not found.");
        }

        var cappedMaxBytes = Math.Clamp(maxBytes <= 0 ? DefaultMaxReadBytes : maxBytes, 1, AbsoluteMaxReadBytes);
        var bytes = File.ReadAllBytes(target);
        var truncated = bytes.Length > cappedMaxBytes;
        var selected = truncated ? bytes[..cappedMaxBytes] : bytes;
        if (selected.Any(value => value == 0))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Binary files are not supported by file.read.");
        }

        var content = Encoding.UTF8.GetString(selected);
        return ToolResult.Ok(new Dictionary<string, object?>
        {
            ["workspaceId"] = workspace.WorkspaceId,
            ["path"] = target,
            ["relativePath"] = Path.GetRelativePath(root, target).Replace('\\', '/'),
            ["content"] = content,
            ["bytes"] = bytes.Length,
            ["returnedBytes"] = selected.Length,
            ["truncated"] = truncated
        });
    }

    private async Task<ToolResult> ReadGitStatus(AgentConfig config, Guid? workspaceId)
    {
        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var root = workspace.Root!;
        if (!Directory.Exists(Path.Combine(root, ".git")))
        {
            return ToolResult.Ok(new Dictionary<string, object?>
            {
                ["workspaceId"] = workspace.Workspace!.WorkspaceId,
                ["path"] = root,
                ["branch"] = null,
                ["clean"] = null,
                ["changes"] = Array.Empty<Dictionary<string, object?>>(),
                ["nonGitWorkspace"] = true,
                ["identityComplete"] = false,
                ["repositoryIdentity"] = new Dictionary<string, object?>
                {
                    ["gitRoot"] = null,
                    ["branch"] = null,
                    ["headCommit"] = null,
                    ["remoteName"] = null,
                    ["remoteUrl"] = null
                },
                ["identityWarnings"] = new[] { "Workspace is not a Git worktree root; continuing without Git metadata." }
            });
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = "git",
            WorkingDirectory = root,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false
        };
        startInfo.ArgumentList.Add("status");
        startInfo.ArgumentList.Add("--porcelain=v1");
        startInfo.ArgumentList.Add("-b");
        startInfo.ArgumentList.Add("--untracked-files=all");
        startInfo.Environment["GIT_OPTIONAL_LOCKS"] = "0";

        try
        {
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", "Failed to start git.");
            }

            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            var waitTask = process.WaitForExitAsync();
            var completed = await Task.WhenAny(waitTask, Task.Delay(TimeSpan.FromSeconds(10)));
            if (completed != waitTask)
            {
                try { process.Kill(entireProcessTree: true); } catch { }
                return ToolResult.Fail("TIMED_OUT", "TIMEOUT", "git.status timed out.");
            }

            var stdout = await stdoutTask;
            var stderr = await stderrTask;
            if (process.ExitCode != 0)
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", string.IsNullOrWhiteSpace(stderr) ? "git.status failed." : stderr.Trim());
            }

            var output = ParseGitStatus(stdout);
            output["workspaceId"] = workspace.Workspace!.WorkspaceId;
            output["path"] = root;
            AddGitIdentity(root, output);
            return ToolResult.Ok(output);
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "git executable is unavailable.");
        }
    }

    private async Task<ToolResult> ReadGitDiff(AgentConfig config, Guid? workspaceId, string? requestedPath, int maxBytes)
    {
        var workspace = ResolveApprovedWorkspace(config, workspaceId);
        if (!workspace.Success)
        {
            return ToolResult.Fail(workspace.Status, workspace.FailureCode!, workspace.Error!);
        }

        var root = workspace.Root!;
        if (!Directory.Exists(Path.Combine(root, ".git")))
        {
            return ToolResult.Ok(new Dictionary<string, object?>
            {
                ["workspaceId"] = workspace.Workspace!.WorkspaceId,
                ["path"] = root,
                ["relativePath"] = requestedPath,
                ["staged"] = "",
                ["unstaged"] = "",
                ["bytes"] = 0,
                ["truncated"] = false,
                ["nonGitWorkspace"] = true,
                ["warning"] = "Workspace is not a Git worktree root; git.diff is empty."
            });
        }

        string? relativePath = null;
        if (!string.IsNullOrWhiteSpace(requestedPath))
        {
            var target = Path.GetFullPath(Path.Combine(root, requestedPath));
            if (!IsWithin(root, target))
            {
                return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Requested path escapes the approved workspace.");
            }
            relativePath = Path.GetRelativePath(root, target).Replace('\\', '/');
        }

        var cappedMaxBytes = Math.Clamp(maxBytes <= 0 ? DefaultMaxDiffBytes : maxBytes, 1, AbsoluteMaxDiffBytes);
        var staged = await RunGitDiff(root, cached: true, relativePath);
        if (!staged.Success) return staged;
        var unstaged = await RunGitDiff(root, cached: false, relativePath);
        if (!unstaged.Success) return unstaged;

        var diff = BuildCombinedDiff(staged.Output.TryGetValue("diff", out var stagedDiff) ? stagedDiff as string ?? "" : "",
            unstaged.Output.TryGetValue("diff", out var unstagedDiff) ? unstagedDiff as string ?? "" : "");
        var bytes = Encoding.UTF8.GetBytes(diff);
        var truncated = bytes.Length > cappedMaxBytes;
        var selected = truncated ? bytes[..cappedMaxBytes] : bytes;

        return ToolResult.Ok(new Dictionary<string, object?>
        {
            ["workspaceId"] = workspace.Workspace!.WorkspaceId,
            ["path"] = root,
            ["relativePath"] = relativePath,
            ["diff"] = Encoding.UTF8.GetString(selected),
            ["bytes"] = bytes.Length,
            ["returnedBytes"] = selected.Length,
            ["truncated"] = truncated,
            ["hasChanges"] = bytes.Length > 0
        });
    }

    private ToolResult DryRunPatchApply(AgentConfig config, Guid? workspaceId, JsonElement request)
    {
        if (!request.TryGetProperty("input", out var input))
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "patch.apply input is required.");
        }
        if (TryInputBool(input, "dryRunOnly") != true)
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "patch.apply requires dryRunOnly=true until patch mutation release gates are implemented.");
        }
        if (TryInputBool(input, "mutationAllowed") == true)
        {
            return ToolResult.Fail("REJECTED", "UNSAFE_TOOL", "patch.apply dry-run refuses requests that allow mutation.");
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

        var targetFiles = TryInputStringList(input, "targetFiles");
        var expectedFiles = TryExpectedFiles(input);
        var parsed = ParseUnifiedDiff(diff);
        if (!parsed.Success)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", parsed.Error ?? "Invalid unified diff.");
        }
        if (parsed.Files.Count == 0)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Patch did not contain file changes.");
        }

        var root = workspace.Root!;
        var fileResults = new List<Dictionary<string, object?>>();
        foreach (var file in parsed.Files)
        {
            if (targetFiles.Count > 0 && !targetFiles.Contains(file.Path, StringComparer.Ordinal))
            {
                return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Patch modifies a file outside targetFiles: " + file.Path);
            }

            var target = Path.GetFullPath(Path.Combine(root, file.Path));
            if (!IsWithin(root, target))
            {
                return ToolResult.Fail("REJECTED", "PATH_ESCAPE", "Patch path escapes the approved workspace: " + file.Path);
            }
            if (!File.Exists(target))
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", "Patch target file was not found: " + file.Path);
            }

            var bytes = File.ReadAllBytes(target);
            if (bytes.Any(value => value == 0))
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", "Binary files are not supported by patch.apply dry-run: " + file.Path);
            }
            var content = Encoding.UTF8.GetString(bytes);
            var actualSha = Sha256Hex(bytes);
            expectedFiles.TryGetValue(file.Path, out var expected);
            var hashMatches = expected?.Sha256 is not null
                && string.Equals(expected.Sha256, actualSha, StringComparison.OrdinalIgnoreCase);
            var lines = SplitLines(content);
            var hunkResults = file.Hunks.Select(hunk => DryRunHunk(lines, hunk)).ToList();
            var contextMatches = hunkResults.All(item => item.ContextMatches);

            fileResults.Add(new Dictionary<string, object?>
            {
                ["path"] = file.Path,
                ["absolutePath"] = target,
                ["expectedSha256"] = expected?.Sha256,
                ["actualSha256"] = actualSha,
                ["bytes"] = bytes.LongLength,
                ["hashMatches"] = hashMatches,
                ["contextMatches"] = contextMatches,
                ["hunks"] = hunkResults.Select(item => new Dictionary<string, object?>
                {
                    ["oldStart"] = item.OldStart,
                    ["oldLineCount"] = item.OldLineCount,
                    ["contextMatches"] = item.ContextMatches,
                    ["message"] = item.Message
                }).ToList()
            });

            if (!contextMatches)
            {
                var mismatchOutput = PatchDryRunOutput(workspace.Workspace!.WorkspaceId, input, fileResults, preflightPassed: false);
                return ToolResult.Fail("REJECTED", "CONTEXT_MISMATCH", "Patch context did not match local file: " + file.Path, mismatchOutput);
            }
        }

        var snapshot = CreateSnapshot(workspace.Workspace!.WorkspaceId, root, input, fileResults);
        if (!snapshot.Created)
        {
            var failedOutput = PatchDryRunOutput(workspace.Workspace!.WorkspaceId, input, fileResults, preflightPassed: true, snapshot);
            return ToolResult.Fail(
                "FAILED",
                "TOOL_FAILED",
                snapshot.Error ?? "Snapshot creation failed.",
                failedOutput);
        }

        var output = PatchDryRunOutput(workspace.Workspace!.WorkspaceId, input, fileResults, preflightPassed: true, snapshot);
        return ToolResult.Fail(
            "REJECTED",
            "UNSAFE_TOOL",
            "Patch dry-run passed and a local snapshot was created, but file mutation is disabled until release gates and rollback safety are implemented.",
            output);
    }

    private async Task<ToolResult> RunGitDiff(string root, bool cached, string? relativePath)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "git",
            WorkingDirectory = root,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false
        };
        startInfo.ArgumentList.Add("diff");
        startInfo.ArgumentList.Add("--no-ext-diff");
        if (cached) startInfo.ArgumentList.Add("--cached");
        if (!string.IsNullOrWhiteSpace(relativePath))
        {
            startInfo.ArgumentList.Add("--");
            startInfo.ArgumentList.Add(relativePath);
        }
        startInfo.Environment["GIT_OPTIONAL_LOCKS"] = "0";

        try
        {
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", "Failed to start git.");
            }

            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            var waitTask = process.WaitForExitAsync();
            var completed = await Task.WhenAny(waitTask, Task.Delay(TimeSpan.FromSeconds(10)));
            if (completed != waitTask)
            {
                try { process.Kill(entireProcessTree: true); } catch { }
                return ToolResult.Fail("TIMED_OUT", "TIMEOUT", "git.diff timed out.");
            }

            var stdout = await stdoutTask;
            var stderr = await stderrTask;
            if (process.ExitCode != 0)
            {
                return ToolResult.Fail("FAILED", "TOOL_FAILED", string.IsNullOrWhiteSpace(stderr) ? "git.diff failed." : stderr.Trim());
            }

            return ToolResult.Ok(new Dictionary<string, object?> { ["diff"] = stdout });
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "git executable is unavailable.");
        }
    }

    private static string BuildCombinedDiff(string staged, string unstaged)
    {
        var builder = new StringBuilder();
        if (!string.IsNullOrWhiteSpace(staged))
        {
            builder.AppendLine("# staged");
            builder.Append(staged);
            if (!staged.EndsWith('\n')) builder.AppendLine();
        }
        if (!string.IsNullOrWhiteSpace(unstaged))
        {
            builder.AppendLine("# unstaged");
            builder.Append(unstaged);
        }
        return builder.ToString();
    }

    private Dictionary<string, object?> ParseGitStatus(string stdout)
    {
        var branch = "";
        var changes = new List<Dictionary<string, object?>>();
        foreach (var line in stdout.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries))
        {
            if (line.StartsWith("## ", StringComparison.Ordinal))
            {
                branch = line[3..];
                continue;
            }
            if (line.Length < 4) continue;
            changes.Add(new Dictionary<string, object?>
            {
                ["index"] = line[0].ToString(),
                ["worktree"] = line[1].ToString(),
                ["path"] = line[3..]
            });
        }

        return new Dictionary<string, object?>
        {
            ["branch"] = branch,
            ["clean"] = changes.Count == 0,
            ["changes"] = changes
        };
    }

    private void AddGitIdentity(string root, Dictionary<string, object?> output)
    {
        var warnings = new List<string>();
        var gitRoot = RunGitIdentity(root, "rev-parse", "--show-toplevel");
        var headCommit = RunGitIdentity(root, "rev-parse", "HEAD");
        var branch = RunGitIdentity(root, "branch", "--show-current");
        var remoteName = RunGitIdentity(root, "config", "--get", "branch." + (branch.Value ?? "") + ".remote");
        var remoteUrl = !string.IsNullOrWhiteSpace(remoteName.Value)
            ? RunGitIdentity(root, "config", "--get", "remote." + remoteName.Value + ".url")
            : RunGitIdentity(root, "config", "--get", "remote.origin.url");

        AddIdentityWarning(warnings, gitRoot.Warning);
        AddIdentityWarning(warnings, headCommit.Warning);
        AddIdentityWarning(warnings, branch.Warning);
        AddIdentityWarning(warnings, remoteName.Warning);
        AddIdentityWarning(warnings, remoteUrl.Warning);

        output["repositoryIdentity"] = new Dictionary<string, object?>
        {
            ["gitRoot"] = NormalizePath(gitRoot.Value),
            ["branch"] = branch.Value,
            ["headCommit"] = headCommit.Value,
            ["remoteName"] = remoteName.Value,
            ["remoteUrl"] = remoteUrl.Value
        };
        output["identityComplete"] = !string.IsNullOrWhiteSpace(headCommit.Value);
        if (warnings.Count > 0)
        {
            output["identityWarnings"] = warnings.Distinct(StringComparer.Ordinal).ToList();
        }
    }

    private GitIdentityValue RunGitIdentity(string root, params string[] args)
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = "git",
                WorkingDirectory = root,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };
            foreach (var arg in args)
            {
                startInfo.ArgumentList.Add(arg);
            }
            startInfo.Environment["GIT_OPTIONAL_LOCKS"] = "0";
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return new GitIdentityValue(null, "Failed to start git " + string.Join(' ', args) + ".");
            }
            if (!process.WaitForExit(TimeSpan.FromSeconds(5)))
            {
                try { process.Kill(entireProcessTree: true); } catch { }
                return new GitIdentityValue(null, "git " + string.Join(' ', args) + " timed out.");
            }
            var stdout = process.StandardOutput.ReadToEnd().Trim();
            var stderr = process.StandardError.ReadToEnd().Trim();
            if (process.ExitCode != 0)
            {
                return new GitIdentityValue(null, string.IsNullOrWhiteSpace(stderr) ? "git " + string.Join(' ', args) + " failed." : stderr);
            }
            return new GitIdentityValue(stdout, null);
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return new GitIdentityValue(null, "git executable is unavailable.");
        }
    }

    private static void AddIdentityWarning(List<string> warnings, string? warning)
    {
        if (!string.IsNullOrWhiteSpace(warning))
        {
            warnings.Add(warning);
        }
    }

    private static string? NormalizePath(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Replace('\\', '/');

    private WorkspaceResolution ResolveApprovedWorkspace(AgentConfig config, Guid? workspaceId)
    {
        if (workspaceId is null)
        {
            return WorkspaceResolution.Fail("FAILED", "TOOL_FAILED", "workspaceId is required.");
        }
        var workspace = config.Workspaces.FirstOrDefault(item => item.WorkspaceId == workspaceId.Value && item.Approved);
        if (workspace is null)
        {
            return WorkspaceResolution.Fail("REJECTED", "WORKSPACE_NOT_APPROVED", "Workspace is not approved.");
        }
        var root = Path.GetFullPath(workspace.Path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (!Directory.Exists(root))
        {
            return WorkspaceResolution.Fail("FAILED", "TOOL_FAILED", "Workspace path does not exist.");
        }
        return WorkspaceResolution.Ok(workspace, root);
    }

    private HttpClient Client(AgentConfig config)
    {
        if (string.IsNullOrWhiteSpace(config.ServerUrl) || string.IsNullOrWhiteSpace(config.Token) || config.AgentId == Guid.Empty)
        {
            throw new InvalidOperationException("Run learnbot pair first.");
        }
        var client = new HttpClient { BaseAddress = new Uri(config.ServerUrl.TrimEnd('/')) };
        client.DefaultRequestHeaders.Add("X-Local-Agent-Token", config.Token);
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
        return client;
    }

    private AgentConfig RequireConfig(bool allowUnpaired = false)
    {
        var config = LoadConfigOrDefault();
        if (!allowUnpaired && (string.IsNullOrWhiteSpace(config.Token) || config.AgentId == Guid.Empty))
        {
            throw new InvalidOperationException("Run learnbot pair first.");
        }
        return config;
    }

    private AgentConfig LoadConfigOrDefault()
    {
        var path = ConfigPath();
        if (!File.Exists(path)) return new AgentConfig();
        return JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(path), JsonOptions) ?? new AgentConfig();
    }

    private void SaveConfig(AgentConfig config)
    {
        var path = ConfigPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, JsonSerializer.Serialize(config, JsonOptions));
    }

    private static string ConfigPath()
    {
        var overridePath = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            return Path.GetFullPath(overridePath);
        }
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine(home, ".learnbot", "agent.json");
    }

    private static string AgentDataDirectory()
    {
        var configDirectory = Path.GetDirectoryName(ConfigPath());
        if (!string.IsNullOrWhiteSpace(configDirectory))
        {
            return configDirectory;
        }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".learnbot");
    }

    private static string LogPath() => Path.Combine(AgentDataDirectory(), "agent.log");

    private static string StatePath() => Path.Combine(AgentDataDirectory(), "agent-state.json");

    private static string WebSessionPath() => Path.Combine(AgentDataDirectory(), "web-session.json");

    private static string? TryReadStoredWebAccessToken(string? expectedServerUrl = null)
    {
        return TryReadStoredWebSession(expectedServerUrl)?.AccessToken;
    }

    private static StoredWebSession? TryReadStoredWebSession(string? expectedServerUrl = null)
    {
        try
        {
            var path = WebSessionPath();
            if (!File.Exists(path)) return null;
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            var schema = root.TryGetProperty("schema", out var schemaElement) ? schemaElement.GetString() : "";
            if (!string.Equals(schema, "learnbot.local-agent.web-session-artifact.v1", StringComparison.Ordinal))
            {
                return null;
            }
            var serverUrl = root.TryGetProperty("serverUrl", out var serverElement) ? serverElement.GetString() : null;
            if (!string.IsNullOrWhiteSpace(expectedServerUrl)
                && !string.IsNullOrWhiteSpace(serverUrl)
                && !string.Equals(serverUrl.TrimEnd('/'), expectedServerUrl.TrimEnd('/'), StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }
            var encryptedAccessToken = root.TryGetProperty("encryptedAccessToken", out var tokenElement) ? tokenElement.GetString() : null;
            var encryptedRefreshToken = root.TryGetProperty("encryptedRefreshToken", out var refreshElement) ? refreshElement.GetString() : null;
            var accessToken = string.IsNullOrWhiteSpace(encryptedAccessToken) ? null : TryUnprotectForCurrentUser(encryptedAccessToken);
            var refreshToken = string.IsNullOrWhiteSpace(encryptedRefreshToken) ? null : TryUnprotectForCurrentUser(encryptedRefreshToken);
            var expiresAt = root.TryGetProperty("expiresAt", out var expiresElement)
                && DateTimeOffset.TryParse(expiresElement.GetString(), out var parsedExpiresAt)
                    ? parsedExpiresAt
                    : (DateTimeOffset?)null;
            var refreshExpiresAt = root.TryGetProperty("refreshExpiresAt", out var refreshExpiresElement)
                && DateTimeOffset.TryParse(refreshExpiresElement.GetString(), out var parsedRefreshExpiresAt)
                    ? parsedRefreshExpiresAt
                    : (DateTimeOffset?)null;
            return string.IsNullOrWhiteSpace(accessToken)
                ? null
                : new StoredWebSession(serverUrl, accessToken, refreshToken, expiresAt, refreshExpiresAt);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or FormatException or CryptographicException)
        {
            return null;
        }
    }

    private static async Task<string?> ReadStoredWebAccessTokenWithRefresh(string? expectedServerUrl = null)
    {
        var session = TryReadStoredWebSession(expectedServerUrl);
        if (session is null)
        {
            return null;
        }
        var now = DateTimeOffset.UtcNow;
        if (session.ExpiresAt is null || session.ExpiresAt > now.AddMinutes(2))
        {
            return session.AccessToken;
        }
        if (string.IsNullOrWhiteSpace(session.RefreshToken)
            || (session.RefreshExpiresAt is not null && session.RefreshExpiresAt <= now.AddMinutes(2)))
        {
            return session.AccessToken;
        }
        var server = (expectedServerUrl ?? session.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        try
        {
            using var client = new HttpClient { BaseAddress = new Uri(server) };
            client.DefaultRequestHeaders.Add("X-Refresh-Token", session.RefreshToken);
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var response = await client.PostAsync("/api/auth/refresh", Json(new { }));
            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode)
            {
                return session.AccessToken;
            }
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            var accessToken = root.GetProperty("token").GetString();
            var refreshToken = root.GetProperty("refreshToken").GetString();
            var expiresAt = root.GetProperty("expiresAt").GetString();
            var refreshExpiresAt = root.GetProperty("refreshExpiresAt").GetString();
            return TryWriteStoredWebSession(server, accessToken, refreshToken, expiresAt, refreshExpiresAt, out _)
                ? accessToken
                : session.AccessToken;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or UriFormatException)
        {
            return session.AccessToken;
        }
    }

    private static string ReadSecret(string prompt)
    {
        Console.Error.Write(prompt);
        if (Console.IsInputRedirected)
        {
            return Console.ReadLine() ?? "";
        }
        var builder = new StringBuilder();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter)
            {
                Console.Error.WriteLine();
                return builder.ToString();
            }
            if (key.Key == ConsoleKey.Backspace)
            {
                if (builder.Length > 0)
                {
                    builder.Length--;
                }
                continue;
            }
            if (!char.IsControl(key.KeyChar))
            {
                builder.Append(key.KeyChar);
            }
        }
    }

    private static bool TryWriteStoredWebSession(
        string serverUrl,
        string? accessToken,
        string? refreshToken,
        string? expiresAt,
        string? refreshExpiresAt,
        out string? error)
    {
        error = null;
        if (string.IsNullOrWhiteSpace(accessToken) || string.IsNullOrWhiteSpace(refreshToken))
        {
            error = "server did not return both access and refresh tokens";
            return false;
        }
        var protectedAccess = TryProtectForCurrentUser(accessToken, out error);
        if (protectedAccess is null)
        {
            return false;
        }
        var protectedRefresh = TryProtectForCurrentUser(refreshToken, out error);
        if (protectedRefresh is null)
        {
            return false;
        }
        try
        {
            var path = WebSessionPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + ".tmp";
            var body = new
            {
                schema = "learnbot.local-agent.web-session-artifact.v1",
                serverUrl = serverUrl.TrimEnd('/'),
                encryptedAccessToken = protectedAccess,
                encryptedRefreshToken = protectedRefresh,
                expiresAt,
                refreshExpiresAt,
                createdAt = DateTimeOffset.UtcNow,
                encryption = new
                {
                    provider = "WINDOWS_DPAPI_CURRENT_USER",
                    plaintextTokenSerializationAllowed = false
                }
            };
            File.WriteAllText(tempPath, JsonSerializer.Serialize(body, JsonOptions), new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.Move(tempPath, path, overwrite: true);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            error = ex.Message;
            return false;
        }
    }

    private static string? TryProtectForCurrentUser(string value, out string? error)
    {
        error = null;
        if (!OperatingSystem.IsWindows())
        {
            error = "DPAPI session storage is only enabled on Windows";
            return null;
        }
        return CryptProtect(value, out error);
    }

    private static string? TryUnprotectForCurrentUser(string value)
    {
        if (!OperatingSystem.IsWindows())
        {
            return null;
        }
        return CryptUnprotect(value);
    }

    private static void TryOpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true
            });
        }
        catch
        {
        }
    }

    private static void Log(string message)
    {
        var path = LogPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.AppendAllText(path, $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
    }

    private static void WriteRunState(
        string status,
        string? lastEvent,
        string? configuredTransport = null,
        string? activeTransport = null,
        int webSocketFailureCount = 0,
        DateTimeOffset? nextWebSocketRetryAt = null)
    {
        var path = StatePath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var existing = LoadRunState();
        var now = DateTimeOffset.UtcNow;
        var startedAt = status == "running" && existing?.Status != "running" ? now : existing?.StartedAt ?? now;
        var state = new AgentRunState(
            status,
            Environment.ProcessId,
            startedAt,
            now,
            lastEvent,
            LogPath(),
            configuredTransport,
            activeTransport,
            webSocketFailureCount,
            nextWebSocketRetryAt);
        File.WriteAllText(path, JsonSerializer.Serialize(state, JsonOptions));
    }

    private static AgentRunState? LoadRunState()
    {
        var path = StatePath();
        if (!File.Exists(path)) return null;
        try
        {
            return JsonSerializer.Deserialize<AgentRunState>(File.ReadAllText(path), JsonOptions);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool IsProcessRunning(int processId)
    {
        if (processId <= 0) return false;
        try
        {
            using var process = Process.GetProcessById(processId);
            return !process.HasExited;
        }
        catch (ArgumentException)
        {
            return false;
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 5)
        {
            return true;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    private static StringContent Json(object value) =>
        new(JsonSerializer.Serialize(value, JsonOptions), Encoding.UTF8, "application/json");

    private static IReadOnlyDictionary<string, object?> BuildCliCodexServerSubmissionBody(
        CliCodexCommandPreviewReport preview,
        CliCodexPatchDryRunApprovalHandoffPreview? patchDryRunApprovalHandoffPreview = null)
    {
        var body = new Dictionary<string, object?>(preview.ServerSubmissionPlan.BodyPreview, StringComparer.Ordinal);
        if (patchDryRunApprovalHandoffPreview is not null)
        {
            body["patchDryRunApprovalHandoffPreview"] = patchDryRunApprovalHandoffPreview;
        }
        return body;
    }

    private async Task<CliCodexServerPlanFetchResult> FetchCliCodexServerPlan(
        CliCodexCommandPreviewReport preview,
        string? webToken,
        CliCodexPatchDryRunApprovalHandoffPreview? patchDryRunApprovalHandoffPreview = null,
        bool autoLoop = true,
        bool noApply = false,
        TimeSpan? pollTimeout = null,
        TimeSpan? approvalTimeout = null)
    {
        var readiness = BuildCliWebSessionServerPlanReadinessReport();
        var submissionBody = BuildCliCodexServerSubmissionBody(preview, patchDryRunApprovalHandoffPreview);
        if (string.IsNullOrWhiteSpace(webToken))
        {
            return BuildCliCodexServerPlanFetchResult(
                preview,
                submissionBody,
                readiness,
                status: "BLOCKED_AUTH_REQUIRED",
                attempted: false,
                networkCallEnabled: false,
                webTokenProvided: false,
                httpStatusCode: null,
                serverResponse: null,
                autoLoop: null,
                error: "web token is required; pass --web-token or set LEARNBOT_WEB_TOKEN");
        }
        if (!preview.ServerSubmissionPlan.ReadyForDisabledPlan)
        {
            return BuildCliCodexServerPlanFetchResult(
                preview,
                submissionBody,
                readiness,
                status: "BLOCKED_PREVIEW",
                attempted: false,
                networkCallEnabled: false,
                webTokenProvided: true,
                httpStatusCode: null,
                serverResponse: null,
                autoLoop: null,
                error: "server submission plan is not ready");
        }

        try
        {
            var server = (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/');
            using var client = new HttpClient
            {
                BaseAddress = new Uri(server),
                Timeout = CliServerRequestTimeout(pollTimeout, approvalTimeout)
            };
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var response = await client.PostAsync(preview.ServerSubmissionPlan.Endpoint, Json(submissionBody));
            var body = await response.Content.ReadAsStringAsync();
            var responseBody = string.IsNullOrWhiteSpace(body) ? null : JsonSerializer.Deserialize<object>(body, JsonOptions);
            CliCodexServerAutoLoopResult? autoLoopResult = null;
            if (response.IsSuccessStatusCode && autoLoop)
            {
                try
                {
                    autoLoopResult = await RunCliCodexServerAutoLoop(
                        preview,
                        client,
                        body,
                        noApply,
                        pollTimeout ?? TimeSpan.FromMinutes(5),
                        approvalTimeout ?? TimeSpan.FromMinutes(10));
                }
                catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
                {
                    autoLoopResult = BuildCliCodexServerAutoLoopResult(
                        "FAILED",
                        enabled: true,
                        started: true,
                        loopId: null,
                        approvalRequestId: null,
                        approvalUrl: null,
                        approvalObserved: false,
                        approvalState: null,
                        releaseAttempted: false,
                        releaseForExecutionAttempted: false,
                        mutationApplied: false,
                        timedOut: false,
                        iterations: 0,
                        localAgentPolls: 0,
                        events: [],
                        lastStatus: null,
                        releaseReadiness: null,
                        releaseBoundary: null,
                        releaseForExecution: null,
                        finalPublicationAttempted: false,
                        finalPublication: null,
                        blockers: ["auto loop failed after run creation; rerun with --no-auto-loop to inspect the raw server response"],
                        error: ex.Message,
                        reason: "The server run was created, but CLI auto-advance failed before completion.");
                }
            }
            return BuildCliCodexServerPlanFetchResult(
                preview,
                submissionBody,
                readiness,
                status: response.IsSuccessStatusCode ? "RUN_CREATED" : "FAILED",
                attempted: true,
                networkCallEnabled: true,
                webTokenProvided: true,
                httpStatusCode: (int)response.StatusCode,
                serverResponse: responseBody,
                autoLoop: autoLoopResult,
                error: response.IsSuccessStatusCode ? null : "server returned a non-success status");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return BuildCliCodexServerPlanFetchResult(
                preview,
                submissionBody,
                readiness,
                status: "FAILED",
                attempted: true,
                networkCallEnabled: true,
                webTokenProvided: true,
                httpStatusCode: null,
                serverResponse: null,
                autoLoop: null,
                error: ex.Message);
        }
    }

    private async Task<CliCodexServerAutoLoopResult> RunCliCodexServerAutoLoop(
        CliCodexCommandPreviewReport preview,
        HttpClient webClient,
        string startResponseBody,
        bool noApply,
        TimeSpan pollTimeout,
        TimeSpan approvalTimeout)
    {
        var events = new List<CliCodexServerAutoLoopEvent>();
        object? lastStatus = null;
        object? releaseReadiness = null;
        object? releaseBoundary = null;
        object? releaseForExecution = null;
        object? finalPublication = null;
        Guid? loopId = null;
        Guid? approvalRequestId = null;
        var approvalObserved = false;
        string? approvalState = null;
        var releaseAttempted = false;
        var releaseForExecutionAttempted = false;
        var finalPublicationAttempted = false;
        var mutationApplied = false;
        var sourceObservationQueued = false;
        var selectedReadOnlyQueued = false;
        var localAgentPolls = 0;
        var iterations = 0;
        var blockers = new List<string>();
        var started = false;
        var timedOut = false;
        string? error = null;

        try
        {
            using var startDocument = JsonDocument.Parse(startResponseBody);
            loopId = TryGetGuid(startDocument.RootElement, "loopId");
            started = loopId is not null;
        }
        catch (JsonException ex)
        {
            error = "failed to parse loop run response: " + ex.Message;
        }

        if (loopId is null || preview.ServerSubmissionPlan.RepositoryId is null)
        {
            blockers.Add(loopId is null ? "server did not return loopId" : "repository id is missing");
            return BuildCliCodexServerAutoLoopResult(
                "BLOCKED",
                enabled: true,
                started,
                loopId,
                approvalRequestId,
                approvalUrl: null,
                approvalObserved,
                approvalState,
                releaseAttempted,
                releaseForExecutionAttempted,
                mutationApplied,
                timedOut,
                iterations,
                localAgentPolls,
                events,
                lastStatus,
                releaseReadiness,
                releaseBoundary,
                releaseForExecution,
                finalPublicationAttempted,
                finalPublication,
                blockers,
                error,
                "Server run creation succeeded, but the CLI could not identify the loop to poll.");
        }

        var config = LoadConfigOrDefault();
        var serverUrl = (config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var approvalUrl = (string?)null;
        var approvalPromptPrinted = false;
        Guid? cliApprovalPromptedFor = null;
        var deadline = DateTimeOffset.UtcNow.Add(pollTimeout);
        DateTimeOffset? approvalDeadline = null;
        var finalStatus = "RUNNING";
        var reason = "The CLI advanced server loop status and processed Local Agent work inline.";

        while (DateTimeOffset.UtcNow < deadline)
        {
            iterations++;
            var statusElement = await GetJsonElement(
                webClient,
                BuildLoopStatusPath(loopId.Value, preview.ServerSubmissionPlan.RepositoryId.Value, preview.ServerSubmissionPlan.AgentId, preview.ServerSubmissionPlan.WorkspaceId));
            lastStatus = ToJsonObject(statusElement);
            var actionKey = TryGetString(statusElement, "actionKey");
            var runnerDecision = TryGetString(statusElement, "runnerDecision");
            var recommendedActionKey = TryGetNestedString(statusElement, "recommendedAction", "actionKey");
            events.Add(AutoLoopEvent("status", actionKey ?? runnerDecision ?? "UNKNOWN", TryGetString(statusElement, "reason")));

            if (!selectedReadOnlyQueued
                && (string.Equals(recommendedActionKey, "QUEUE_SELECTED_READ_ONLY", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(actionKey, "QUEUE_READ_ONLY_OBSERVATION", StringComparison.OrdinalIgnoreCase)))
            {
                var enqueue = await PostJsonElement(
                    webClient,
                    "/api/code-agent/loop/runner/enqueue-selected-read-only",
                    BuildRunnerLoopBody(preview, loopId.Value));
                selectedReadOnlyQueued = true;
                lastStatus = ToJsonObject(enqueue);
                events.Add(AutoLoopEvent(
                    "runner-enqueue",
                    TryGetString(enqueue, "status") ?? "ENQUEUE_ATTEMPTED",
                    TryGetString(enqueue, "reason")));
                if (!HasQueuedRequest(enqueue))
                {
                    finalStatus = "LOCAL_AGENT_NOT_READY";
                    reason = TryGetString(enqueue, "reason") ?? "The server refused to enqueue the selected read-only Local Agent request.";
                    blockers.Add(reason);
                    break;
                }
                if (await PollOnce(config, quiet: true))
                {
                    localAgentPolls++;
                    events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed selected read-only Local Agent work"));
                }
                continue;
            }

            if (string.Equals(actionKey, "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED", StringComparison.OrdinalIgnoreCase)
                || string.Equals(runnerDecision, "READY_FINAL_RESULT_DISABLED", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    finalPublication = ToJsonObject(await PostJsonElement(
                        webClient,
                        "/api/code-agent/loop/runner/final-result-publication",
                        new
                        {
                            repositoryId = preview.ServerSubmissionPlan.RepositoryId,
                            loopId,
                            agentId = preview.ServerSubmissionPlan.AgentId,
                            workspaceId = preview.ServerSubmissionPlan.WorkspaceId
                        }));
                    finalPublicationAttempted = true;
                    finalStatus = "COMPLETED";
                    mutationApplied = releaseForExecutionAttempted;
                    reason = "Approved execution flow completed and the server published the final result.";
                    events.Add(AutoLoopEvent("final-publication", "PUBLISHED", "final result publication endpoint completed"));
                    break;
                }
                catch (HttpRequestException ex)
                {
                    finalPublicationAttempted = true;
                    error = ex.Message;
                    blockers.Add("final result publication failed; publication flags or readiness gates may be disabled");
                    finalStatus = "FINAL_PUBLICATION_BLOCKED";
                    reason = "Approved execution flow completed, but final result publication failed.";
                    events.Add(AutoLoopEvent("final-publication", "FAILED", ex.Message));
                    break;
                }
            }

            var discoveredApprovalId = TryGetApprovalRequestId(statusElement);
            if (discoveredApprovalId is not null)
            {
                approvalRequestId ??= discoveredApprovalId;
                approvalUrl ??= serverUrl + "/code";
                if (!approvalPromptPrinted)
                {
                    Console.Error.WriteLine("approval required: " + approvalUrl);
                    approvalPromptPrinted = true;
                }
            }

            if (approvalRequestId is not null)
            {
                var approvalElement = await GetJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}");
                approvalState = TryGetString(approvalElement, "approvalState") ?? TryGetString(approvalElement, "status");
                var toolStatus = TryGetString(approvalElement, "status");
                approvalObserved = string.Equals(approvalState, "APPROVED", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(toolStatus, "APPROVED_HELD", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(toolStatus, "APPROVED", StringComparison.OrdinalIgnoreCase);
                events.Add(AutoLoopEvent("approval", approvalState ?? toolStatus ?? "UNKNOWN", approvalObserved ? "approval observed" : "waiting for browser approval"));

                if (!approvalObserved
                    && cliApprovalPromptedFor != approvalRequestId
                    && !noApply)
                {
                    cliApprovalPromptedFor = approvalRequestId;
                    var cliDecision = PromptCliApprovalDecision(approvalRequestId.Value, approvalUrl, approvalElement);
                    if (cliDecision is not null)
                    {
                        var decisionText = cliDecision.Value ? "APPROVE" : "DENY";
                        var decidedElement = await PostJsonElement(
                            webClient,
                            $"/api/local-agents/tools/{approvalRequestId}/approval",
                            new { decision = decisionText });
                        approvalState = TryGetString(decidedElement, "approvalState") ?? TryGetString(decidedElement, "status");
                        toolStatus = TryGetString(decidedElement, "status");
                        approvalObserved = string.Equals(approvalState, "APPROVED", StringComparison.OrdinalIgnoreCase)
                            || string.Equals(toolStatus, "APPROVED_HELD", StringComparison.OrdinalIgnoreCase)
                            || string.Equals(toolStatus, "APPROVED", StringComparison.OrdinalIgnoreCase);
                        events.Add(AutoLoopEvent("cli-approval", decisionText, "approval decision submitted from CLI"));
                    }
                }

                if (string.Equals(approvalState, "DENIED", StringComparison.OrdinalIgnoreCase)
                    || (!releaseForExecutionAttempted
                        && (string.Equals(toolStatus, "REJECTED", StringComparison.OrdinalIgnoreCase)
                            || string.Equals(toolStatus, "CANCELLED", StringComparison.OrdinalIgnoreCase))))
                {
                    finalStatus = "APPROVAL_DENIED";
                    reason = "The user denied or cancelled the approval request; no patch was released.";
                    break;
                }

                if (releaseForExecutionAttempted && IsTerminalLocalAgentToolStatus(toolStatus))
                {
                    mutationApplied = TryGetNestedBool(approvalElement, "output", "mutationApplied") == true;
                    if (!mutationApplied)
                    {
                        finalStatus = "PATCH_FAILED";
                        error = TryGetString(approvalElement, "error");
                        blockers.Add("patch.apply completed without mutationApplied=true");
                        reason = "The approved patch was released, but Local Agent did not apply it.";
                        events.Add(AutoLoopEvent("patch-apply", toolStatus ?? "UNKNOWN", error));
                        break;
                    }
                    events.Add(AutoLoopEvent("patch-apply", "SUCCEEDED", "Local Agent reported mutationApplied=true"));
                }

                if (noApply)
                {
                    finalStatus = "APPROVAL_REQUIRED";
                    blockers.Add("no-apply mode requested; approval and release are not automated");
                    reason = "The CLI stopped after creating the approval request because --no-apply was requested.";
                    break;
                }

                approvalDeadline ??= DateTimeOffset.UtcNow.Add(approvalTimeout);
                if (!approvalObserved)
                {
                    if (DateTimeOffset.UtcNow >= approvalDeadline.Value)
                    {
                        finalStatus = "APPROVAL_TIMED_OUT";
                        timedOut = true;
                        blockers.Add("approval timeout elapsed before browser approval");
                        reason = "The CLI created the approval request but did not observe approval before the timeout.";
                        break;
                    }
                    await Task.Delay(TimeSpan.FromSeconds(2));
                    continue;
                }

                if (!releaseAttempted)
                {
                    var readinessElement = await GetJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}/readiness");
                    releaseReadiness = ToJsonObject(readinessElement);
                    var preconditionsPassed = TryGetNestedBool(readinessElement, "patchExecutionGate", "preconditionsPassed") == true;
                    if (!preconditionsPassed)
                    {
                        if (!sourceObservationQueued)
                        {
                            if (BuildSourceRepositoryObservationBody(approvalElement, approvalRequestId.Value) is { } repositoryObservationBody)
                            {
                                await PostJsonElement(webClient, "/api/local-agents/tools/read-only", repositoryObservationBody);
                                events.Add(AutoLoopEvent("source-observation", "GIT_STATUS_QUEUED", "queued source repository verification before release"));
                            }
                            await PostJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}/dry-run", new { });
                            sourceObservationQueued = true;
                            events.Add(AutoLoopEvent("source-observation", "PATCH_DRY_RUN_QUEUED", "queued source patch dry-run before release"));
                        }

                        if (await PollOnce(config, quiet: true))
                        {
                            localAgentPolls++;
                            events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed queued source observation work"));
                            continue;
                        }

                        events.Add(AutoLoopEvent("release-readiness", "WAITING_FOR_SOURCE_OBSERVATIONS", "release preconditions are not complete yet"));
                        await Task.Delay(TimeSpan.FromSeconds(2));
                        continue;
                    }

                    var releaseBoundaryElement = await PostJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}/release", new { });
                    releaseBoundary = ToJsonObject(releaseBoundaryElement);
                    if (string.Equals(TryGetString(releaseBoundaryElement, "status"), "RELEASE_REFUSED_PRECONDITIONS_BLOCKED", StringComparison.OrdinalIgnoreCase))
                    {
                        finalStatus = "RELEASE_BLOCKED";
                        blockers.Add("release preconditions are blocked before fresh observations can be queued");
                        reason = "Approval was observed, but the server reported blocked release preconditions.";
                        events.Add(AutoLoopEvent("release-boundary", "PRECONDITIONS_BLOCKED", TryGetString(releaseBoundaryElement, "message")));
                        break;
                    }

                    await PostJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}/fresh-observations", new { });
                    releaseAttempted = true;
                    events.Add(AutoLoopEvent("release-readiness", "CHECKED", "release boundary and fresh observations were requested"));
                }

                if (await PollOnce(config, quiet: true))
                {
                    localAgentPolls++;
                    events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed queued Local Agent work"));
                    continue;
                }

                if (!releaseForExecutionAttempted)
                {
                    try
                    {
                        releaseForExecution = ToJsonObject(await PostJsonElement(webClient, $"/api/local-agents/tools/{approvalRequestId}/release-for-execution", new { }));
                        releaseForExecutionAttempted = true;
                        events.Add(AutoLoopEvent("release-for-execution", "ATTEMPTED", "approved patch was released for Local Agent claim"));
                    }
                    catch (HttpRequestException ex)
                    {
                        if (IsRetryableReleaseForExecutionError(ex.Message)
                            && DateTimeOffset.UtcNow < approvalDeadline.GetValueOrDefault(DateTimeOffset.UtcNow.Add(approvalTimeout)))
                        {
                            error = ex.Message;
                            events.Add(AutoLoopEvent("release-for-execution", "WAITING_FOR_FRESH_EVIDENCE", ex.Message));
                            await Task.Delay(TimeSpan.FromSeconds(2));
                            continue;
                        }
                        releaseForExecutionAttempted = true;
                        error = ex.Message;
                        blockers.Add("release-for-execution failed; release flags or readiness gates may be disabled");
                        finalStatus = "RELEASE_BLOCKED";
                        reason = "Approval was observed, but the server refused to release the patch for execution.";
                        break;
                    }
                    continue;
                }

                if (await PollOnce(config, quiet: true))
                {
                    localAgentPolls++;
                    events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed approved execution work"));
                    continue;
                }
            }

            if (string.Equals(actionKey, "PREVIEW_RUNNER_STEP", StringComparison.OrdinalIgnoreCase)
                || string.Equals(recommendedActionKey, "PREVIEW_RUNNER_STEP", StringComparison.OrdinalIgnoreCase))
            {
                var runnerPreview = await PostJsonElement(
                    webClient,
                    "/api/code-agent/loop/runner/preview",
                    BuildRunnerLoopBody(preview, loopId.Value));
                lastStatus = ToJsonObject(runnerPreview);
                var previewActionKey = TryGetString(runnerPreview, "actionKey");
                var previewRecommendedActionKey = TryGetNestedString(runnerPreview, "recommendedAction", "actionKey");
                var runnerDecisionAfterPreview = TryGetString(runnerPreview, "runnerDecision");
                events.Add(AutoLoopEvent(
                    "runner-preview",
                    runnerDecisionAfterPreview ?? previewActionKey ?? previewRecommendedActionKey ?? "PREVIEWED",
                    TryGetString(runnerPreview, "reason")));

                if (string.Equals(previewRecommendedActionKey, "QUEUE_SELECTED_READ_ONLY", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(previewActionKey, "QUEUE_SELECTED_READ_ONLY", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(runnerDecisionAfterPreview, "PREPARED_READ_ONLY_CANDIDATE", StringComparison.OrdinalIgnoreCase))
                {
                    var enqueue = await PostJsonElement(
                        webClient,
                        "/api/code-agent/loop/runner/enqueue-selected-read-only",
                        BuildRunnerLoopBody(preview, loopId.Value));
                    lastStatus = ToJsonObject(enqueue);
                    events.Add(AutoLoopEvent(
                        "runner-enqueue",
                        TryGetString(enqueue, "status") ?? "ENQUEUE_ATTEMPTED",
                        TryGetString(enqueue, "reason")));
                    if (!HasQueuedRequest(enqueue))
                    {
                        finalStatus = "LOCAL_AGENT_NOT_READY";
                        reason = TryGetString(enqueue, "reason") ?? "The server refused to enqueue the selected read-only Local Agent request.";
                        blockers.Add(reason);
                        break;
                    }
                    if (await PollOnce(config, quiet: true))
                    {
                        localAgentPolls++;
                        events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed selected read-only Local Agent work"));
                    }
                    continue;
                }

                if (string.Equals(previewActionKey, "STOP_WITH_REASON", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(runnerDecisionAfterPreview, "NO_REQUEST_PREPARED", StringComparison.OrdinalIgnoreCase))
                {
                    finalStatus = "STOPPED";
                    reason = "The server runner preview did not prepare another Local Agent request.";
                    break;
                }

                await Task.Delay(TimeSpan.FromSeconds(1));
                continue;
            }

            if (string.Equals(actionKey, "QUEUE_READ_ONLY_OBSERVATION", StringComparison.OrdinalIgnoreCase)
                || TryGetBool(statusElement, "advanceAvailable") == true)
            {
                var advance = await PostJsonElement(
                    webClient,
                    $"/api/code-agent/loop/runs/{loopId}/advance?repositoryId={preview.ServerSubmissionPlan.RepositoryId}",
                    new
                    {
                        agentId = preview.ServerSubmissionPlan.AgentId,
                        workspaceId = preview.ServerSubmissionPlan.WorkspaceId
                    });
                lastStatus = ToJsonObject(advance);
                events.Add(AutoLoopEvent("advance", TryGetString(advance, "runnerDecision") ?? "ADVANCED", TryGetString(advance, "reason")));
            }

            if (await PollOnce(config, quiet: true))
            {
                localAgentPolls++;
                events.Add(AutoLoopEvent("local-agent", "CLAIM_PROCESSED", "processed queued Local Agent work"));
                continue;
            }

            if (string.Equals(actionKey, "STOP_WITH_REASON", StringComparison.OrdinalIgnoreCase)
                || string.Equals(runnerDecision, "STOPPED", StringComparison.OrdinalIgnoreCase))
            {
                finalStatus = "STOPPED";
                reason = "The server loop reached a stopped state.";
                break;
            }

            if (approvalRequestId is null && !TryGetBool(statusElement, "waitingForLocalAgent").GetValueOrDefault())
            {
                await Task.Delay(TimeSpan.FromSeconds(1));
            }
        }

        if (DateTimeOffset.UtcNow >= deadline && finalStatus == "RUNNING")
        {
            finalStatus = "TIMED_OUT";
            timedOut = true;
            blockers.Add("poll timeout elapsed before the loop reached a terminal state");
            reason = "The CLI did not reach approval, release, or a terminal server state before the polling timeout.";
        }

        mutationApplied = mutationApplied && finalStatus != "PATCH_FAILED";
        return BuildCliCodexServerAutoLoopResult(
            finalStatus,
            enabled: true,
            started,
            loopId,
            approvalRequestId,
            approvalUrl,
            approvalObserved,
            approvalState,
            releaseAttempted,
            releaseForExecutionAttempted,
            mutationApplied,
            timedOut,
            iterations,
            localAgentPolls,
            events,
            lastStatus,
            releaseReadiness,
            releaseBoundary,
            releaseForExecution,
            finalPublicationAttempted,
            finalPublication,
            blockers,
            error,
            reason);
    }

    private static bool IsRetryableReleaseForExecutionError(string? message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return false;
        }
        return message.Contains("fresh release-attempt-linked evidence", StringComparison.OrdinalIgnoreCase)
            || message.Contains("Linked patch dry-run output is required", StringComparison.OrdinalIgnoreCase)
            || message.Contains("Patch execution gate is not ready", StringComparison.OrdinalIgnoreCase)
            || message.Contains("release preconditions are incomplete", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsTerminalLocalAgentToolStatus(string? status) =>
        string.Equals(status, "SUCCEEDED", StringComparison.OrdinalIgnoreCase)
        || string.Equals(status, "FAILED", StringComparison.OrdinalIgnoreCase)
        || string.Equals(status, "REJECTED", StringComparison.OrdinalIgnoreCase)
        || string.Equals(status, "TIMED_OUT", StringComparison.OrdinalIgnoreCase)
        || string.Equals(status, "DISCONNECTED", StringComparison.OrdinalIgnoreCase)
        || string.Equals(status, "CANCELLED", StringComparison.OrdinalIgnoreCase);

    private static CliCodexServerAutoLoopResult BuildCliCodexServerAutoLoopResult(
        string status,
        bool enabled,
        bool started,
        Guid? loopId,
        Guid? approvalRequestId,
        string? approvalUrl,
        bool approvalObserved,
        string? approvalState,
        bool releaseAttempted,
        bool releaseForExecutionAttempted,
        bool mutationApplied,
        bool timedOut,
        int iterations,
        int localAgentPolls,
        IReadOnlyList<CliCodexServerAutoLoopEvent> events,
        object? lastStatus,
        object? releaseReadiness,
        object? releaseBoundary,
        object? releaseForExecution,
        bool finalPublicationAttempted,
        object? finalPublication,
        IReadOnlyList<string> blockers,
        string? error,
        string reason) =>
        new(
            Schema: "learnbot.local-agent.codex-server-auto-loop-result.v1",
            Status: status,
            Enabled: enabled,
            Started: started,
            LoopId: loopId,
            ApprovalRequestId: approvalRequestId,
            ApprovalUrl: approvalUrl,
            ApprovalObserved: approvalObserved,
            ApprovalState: approvalState,
            ReleaseAttempted: releaseAttempted,
            ReleaseForExecutionAttempted: releaseForExecutionAttempted,
            MutationApplied: mutationApplied,
            TimedOut: timedOut,
            Iterations: iterations,
            LocalAgentPolls: localAgentPolls,
            Events: events,
            LastStatus: lastStatus,
            ReleaseReadiness: releaseReadiness,
            ReleaseBoundary: releaseBoundary,
            ReleaseForExecution: releaseForExecution,
            FinalPublicationAttempted: finalPublicationAttempted,
            FinalPublication: finalPublication,
            Blockers: blockers.Distinct(StringComparer.Ordinal).ToList(),
            Error: error,
            Reason: reason);

    private static CliCodexServerAutoLoopEvent AutoLoopEvent(string stage, string status, string? detail) =>
        new(stage, status, detail, DateTimeOffset.UtcNow);

    private static string BuildLoopStatusPath(Guid loopId, Guid repositoryId, Guid? agentId, Guid? workspaceId)
    {
        var query = new StringBuilder($"/api/code-agent/loop/runs/{loopId}?repositoryId={Uri.EscapeDataString(repositoryId.ToString())}");
        if (agentId is not null) query.Append("&agentId=").Append(Uri.EscapeDataString(agentId.Value.ToString()));
        if (workspaceId is not null) query.Append("&workspaceId=").Append(Uri.EscapeDataString(workspaceId.Value.ToString()));
        return query.ToString();
    }

    private static TimeSpan CliServerRequestTimeout(TimeSpan? pollTimeout, TimeSpan? approvalTimeout)
    {
        var pollSeconds = pollTimeout?.TotalSeconds ?? TimeSpan.FromMinutes(5).TotalSeconds;
        var approvalSeconds = approvalTimeout?.TotalSeconds ?? TimeSpan.FromMinutes(10).TotalSeconds;
        var seconds = Math.Clamp(Math.Max(pollSeconds, approvalSeconds) + 60, 300, 7_500);
        return TimeSpan.FromSeconds(seconds);
    }

    private static object BuildRunnerLoopBody(CliCodexCommandPreviewReport preview, Guid loopId) => new
    {
        repositoryId = preview.ServerSubmissionPlan.RepositoryId,
        loopId,
        agentId = preview.ServerSubmissionPlan.AgentId,
        workspaceId = preview.ServerSubmissionPlan.WorkspaceId
    };

    private static async Task<JsonElement> GetJsonElement(HttpClient client, string path)
    {
        using var response = await client.GetAsync(path);
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"GET {path} failed with HTTP {(int)response.StatusCode}: {text}");
        }
        using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        return document.RootElement.Clone();
    }

    private static async Task<JsonElement> PostJsonElement(HttpClient client, string path, object body)
    {
        using var response = await client.PostAsync(path, Json(body));
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"POST {path} failed with HTTP {(int)response.StatusCode}: {text}");
        }
        using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
        return document.RootElement.Clone();
    }

    private static bool? PromptCliApprovalDecision(Guid requestId, string? approvalUrl, JsonElement approvalElement)
    {
        var toolName = TryGetString(approvalElement, "toolName") ?? "local tool";
        var targetFiles = ApprovalTargetFilesText(approvalElement);
        Console.Error.WriteLine();
        Console.Error.WriteLine($"Approval required for {toolName}: {requestId}");
        if (!string.IsNullOrWhiteSpace(targetFiles))
        {
            Console.Error.WriteLine("Target files: " + targetFiles);
        }
        if (!string.IsNullOrWhiteSpace(approvalUrl))
        {
            Console.Error.WriteLine("Browser approval page: " + approvalUrl);
        }

        while (true)
        {
            Console.Error.Write("Approve this request? [y] approve / [n] deny: ");
            var answer = Console.ReadLine();
            if (answer is null)
            {
                return null;
            }
            answer = answer.Trim();
            if (answer.Equals("y", StringComparison.OrdinalIgnoreCase)
                || answer.Equals("yes", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
            if (answer.Equals("n", StringComparison.OrdinalIgnoreCase)
                || answer.Equals("no", StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }
            Console.Error.WriteLine("Please enter y to approve or n to deny.");
        }
    }

    private static string ApprovalTargetFilesText(JsonElement approvalElement)
    {
        if (approvalElement.ValueKind != JsonValueKind.Object
            || !approvalElement.TryGetProperty("input", out var input)
            || input.ValueKind != JsonValueKind.Object
            || !input.TryGetProperty("targetFiles", out var targetFiles)
            || targetFiles.ValueKind != JsonValueKind.Array)
        {
            return "";
        }

        return string.Join(", ", targetFiles.EnumerateArray()
            .Where(item => item.ValueKind == JsonValueKind.String)
            .Select(item => item.GetString())
            .Where(item => !string.IsNullOrWhiteSpace(item))
            .Take(10));
    }

    private static object? ToJsonObject(JsonElement element) =>
        JsonSerializer.Deserialize<object>(element.GetRawText(), JsonOptions);

    private static string? TryGetString(JsonElement element, string propertyName) =>
        element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty(propertyName, out var value)
            && value.ValueKind == JsonValueKind.String
                ? value.GetString()
                : null;

    private static bool? TryGetBool(JsonElement element, string propertyName) =>
        element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty(propertyName, out var value)
            && value.ValueKind is JsonValueKind.True or JsonValueKind.False
                ? value.GetBoolean()
                : null;

    private static bool HasQueuedRequest(JsonElement element) =>
        element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty("queuedRequest", out var value)
        && value.ValueKind != JsonValueKind.Null
        && value.ValueKind != JsonValueKind.Undefined;

    private static bool? TryGetNestedBool(JsonElement element, params string[] path)
    {
        var current = element;
        foreach (var part in path)
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(part, out current))
            {
                return null;
            }
        }
        return current.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? current.GetBoolean()
            : null;
    }

    private static string? TryGetNestedString(JsonElement element, params string[] path)
    {
        var current = element;
        foreach (var part in path)
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(part, out current))
            {
                return null;
            }
        }
        return current.ValueKind == JsonValueKind.String ? current.GetString() : null;
    }

    private static Guid? TryGetGuid(JsonElement element, string propertyName)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(propertyName, out var value))
        {
            return null;
        }
        if (value.ValueKind == JsonValueKind.String && Guid.TryParse(value.GetString(), out var parsed))
        {
            return parsed;
        }
        return null;
    }

    private static object? BuildSourceRepositoryObservationBody(JsonElement approvalElement, Guid sourceRequestId)
    {
        var agentId = TryGetGuid(approvalElement, "agentId");
        var workspaceId = TryGetGuid(approvalElement, "workspaceId");
        if (agentId is null || workspaceId is null)
        {
            return null;
        }

        var input = new Dictionary<string, object?>
        {
            ["sourceRequestId"] = sourceRequestId.ToString()
        };
        if (approvalElement.ValueKind == JsonValueKind.Object
            && approvalElement.TryGetProperty("input", out var requestInput)
            && requestInput.ValueKind == JsonValueKind.Object)
        {
            if (requestInput.TryGetProperty("sourceRepository", out var sourceRepository)
                && sourceRepository.ValueKind is JsonValueKind.Object or JsonValueKind.Array)
            {
                input["sourceRepository"] = ToJsonObject(sourceRepository);
            }
            if (requestInput.TryGetProperty("localWorkspace", out var localWorkspace)
                && localWorkspace.ValueKind is JsonValueKind.Object or JsonValueKind.Array)
            {
                input["localWorkspace"] = ToJsonObject(localWorkspace);
            }
            if (requestInput.TryGetProperty("loopId", out var loopId)
                && loopId.ValueKind == JsonValueKind.String)
            {
                input["loopId"] = loopId.GetString();
            }
        }

        return new
        {
            agentId,
            workspaceId,
            toolName = "git.status",
            input
        };
    }

    private static Guid? TryGetApprovalRequestId(JsonElement statusElement)
    {
        if (TryGetNestedGuid(statusElement, "finalReport", "approvalRequestId") is Guid fromFinal)
        {
            return fromFinal;
        }
        if (TryGetNestedGuid(statusElement, "advance", "handoff", "approvalRequestId") is Guid fromAdvance)
        {
            return fromAdvance;
        }
        return null;
    }

    private static Guid? TryGetNestedGuid(JsonElement element, params string[] path)
    {
        var current = element;
        foreach (var part in path)
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(part, out current))
            {
                return null;
            }
        }
        if (current.ValueKind == JsonValueKind.String && Guid.TryParse(current.GetString(), out var parsed))
        {
            return parsed;
        }
        return null;
    }

    private static CliCodexServerPlanFetchResult BuildCliCodexServerPlanFetchResult(
        CliCodexCommandPreviewReport preview,
        IReadOnlyDictionary<string, object?> submissionBody,
        CliWebSessionServerPlanReadinessReport webSessionReadiness,
        string status,
        bool attempted,
        bool networkCallEnabled,
        bool webTokenProvided,
        int? httpStatusCode,
        object? serverResponse,
        CliCodexServerAutoLoopResult? autoLoop = null,
        string? error = null) =>
        new(
            Schema: "learnbot.local-agent.codex-server-plan-fetch-result.v1",
            CommandName: preview.CommandName,
            Command: preview.Command,
            Version: preview.Version,
            Status: status,
            WebSessionReadiness: webSessionReadiness,
            OneCyclePreview: preview.OneCyclePreview,
            ReadOnlyServerBridge: BuildCliCodexReadOnlyServerBridge(preview, webSessionReadiness, status, attempted, networkCallEnabled, webTokenProvided, httpStatusCode, serverResponse),
            Attempted: attempted,
            NetworkCallEnabled: networkCallEnabled,
            UsedLocalAgentToken: false,
            WebTokenProvided: webTokenProvided,
            TokenSecretPrinted: false,
            RequestCreated: status == "RUN_CREATED",
            MutationAllowed: false,
            Endpoint: preview.ServerSubmissionPlan.Endpoint,
            Method: preview.ServerSubmissionPlan.Method,
            ServerSubmissionPlan: preview.ServerSubmissionPlan with { BodyPreview = submissionBody },
            Blockers: status.StartsWith("BLOCKED", StringComparison.Ordinal) && error is not null
                ? preview.ServerSubmissionPlan.Blockers.Concat([error]).Distinct(StringComparer.Ordinal).ToList()
                : preview.ServerSubmissionPlan.Blockers,
            HttpStatusCode: httpStatusCode,
            ServerResponse: serverResponse,
            AutoLoop: autoLoop,
            Error: error);

    private static CliCodexReadOnlyServerBridge BuildCliCodexReadOnlyServerBridge(
        CliCodexCommandPreviewReport preview,
        CliWebSessionServerPlanReadinessReport readiness,
        string fetchStatus,
        bool attempted,
        bool networkCallEnabled,
        bool webTokenProvided,
        int? httpStatusCode,
        object? serverResponse)
    {
        var oneCycle = preview.OneCyclePreview;
        var canAttemptServerPlan = oneCycle.ReadyForReadOnlyToolLoop
            && preview.ServerSubmissionPlan.ReadyForDisabledPlan
            && webTokenProvided
            && (readiness.EnvironmentWebTokenUsableForServerPlanFetch || readiness.StoredSessionUsableForServerPlanFetch);
        var planFetched = attempted && httpStatusCode is >= 200 and <= 299 && serverResponse is not null;
        var bridgeReady = canAttemptServerPlan && (planFetched || !networkCallEnabled);
        var blockers = new List<string>();
        if (!oneCycle.ReadyForReadOnlyToolLoop)
        {
            blockers.AddRange(oneCycle.Blockers);
        }
        if (!webTokenProvided)
        {
            blockers.Add("web token is required before authenticated server loop/runner preview fetch");
        }
        if (!readiness.EnvironmentWebTokenUsableForServerPlanFetch && !readiness.StoredSessionUsableForServerPlanFetch)
        {
            blockers.Add("no usable web-session auth is available for server loop/runner preview");
        }
        if (!preview.ServerSubmissionPlan.ReadyForDisabledPlan)
        {
            blockers.AddRange(preview.ServerSubmissionPlan.Blockers);
        }
        if (attempted && !planFetched)
        {
            blockers.Add("server submission-plan fetch did not return a successful disabled plan");
        }

        return new CliCodexReadOnlyServerBridge(
            Schema: "learnbot.local-agent.codex-read-only-server-bridge.v1",
            Status: bridgeReady ? "READY_FOR_RUNNER_PREVIEW_HANDOFF" : "BLOCKED_OR_WAITING",
            FetchStatus: fetchStatus,
            OneCycleReadyForReadOnlyToolLoop: oneCycle.ReadyForReadOnlyToolLoop,
            AuthenticatedServerPlanReady: canAttemptServerPlan,
            ServerPlanFetchAttempted: attempted,
            ServerPlanFetched: planFetched,
            ServerPlanNetworkCallEnabled: networkCallEnabled,
            EnvironmentTokenFallbackUsed: webTokenProvided && readiness.EnvironmentWebTokenUsableForServerPlanFetch,
            StoredSessionAuthUsed: false,
            StoredSessionAuthEnabled: readiness.ServerPlanFetchFromStoredSessionEnabled,
            RequestCreationEnabled: false,
            RunnerPreviewFetchEnabled: planFetched,
            RunnerPreviewEndpoint: "/api/code-agent/loop/runner/preview",
            SelectToolPreviewEndpoint: "/api/code-agent/loop/runner/select-tool-preview",
            EnqueueSelectedReadOnlyEndpoint: "/api/code-agent/loop/runner/enqueue-selected-read-only",
            RunStatusEndpoint: "/api/code-agent/loop/runs/{loopId}",
            AdvanceEndpoint: "/api/code-agent/loop/runs/{loopId}/advance",
            AutoAdvanceAvailable: planFetched,
            FileDiscoveryReadPlan: oneCycle.FileDiscoveryReadPlan,
            FileDiscoveryPlanEnabled: oneCycle.FileDiscoveryReadPlan.FileDiscoveryPlanEnabled,
            FileReadPlanEnabled: oneCycle.FileDiscoveryReadPlan.FileReadPlanEnabled,
            PatchDryRunEnabled: false,
            MutationAllowed: false,
            TokenSecretPrinted: false,
            OrderedReadOnlyStages: oneCycle.Stages
                .Where(stage => stage.Name is "goal-input" or "workspace-discovery" or "file-discovery" or "file-read" or "plan")
                .Select(stage => stage.Name)
                .ToList(),
            Blockers: blockers.Distinct(StringComparer.Ordinal).ToList(),
            Reason: "This bridge ties the CLI one-cycle preview to the server loop/runner preview endpoints for read-only work only. It never creates Local Agent requests, fetches files, enqueues tools, applies patches, runs tests, or mutates code.");
    }

    private async Task<CliWebSessionPlanFetchResult> FetchCliWebSessionPlan(string planKind, string[] args)
    {
        var config = LoadConfigOrDefault();
        var server = (GetOption(args, "--server") ?? config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var claim = string.Equals(planKind, "claim", StringComparison.OrdinalIgnoreCase);
        var claimResult = string.Equals(planKind, "claim-result", StringComparison.OrdinalIgnoreCase);
        var create = string.Equals(planKind, "device-session-create", StringComparison.OrdinalIgnoreCase);
        var endpoint = claim
            ? "/api/auth/cli-device-session/claim/plan"
            : claimResult
                ? "/api/auth/cli-device-session/claim-result/plan"
                : create
                    ? "/api/auth/cli-device-session/create/plan"
                    : "/api/auth/cli-device-session/plan";
        var normalizedPlanKind = claim ? "claim" : claimResult ? "claim-result" : create ? "device-session-create" : "device-session";
        var localPlan = BuildCliWebSessionLocalFallbackPlan(normalizedPlanKind, server, endpoint);
        var offline = args.Contains("--offline", StringComparer.OrdinalIgnoreCase);
        if (offline)
        {
            return BuildCliWebSessionPlanFetchResult(
                planKind: normalizedPlanKind,
                serverUrl: server,
                endpoint: endpoint,
                status: "LOCAL_STATIC_FALLBACK",
                attempted: false,
                networkCallEnabled: false,
                fallbackUsed: true,
                localPlan: localPlan,
                httpStatusCode: null,
                serverResponse: null,
                error: "offline mode requested");
        }

        try
        {
            using var client = new HttpClient { BaseAddress = new Uri(server) };
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            var body = claim
                ? new Dictionary<string, object?>
                {
                    ["deviceCode"] = GetOption(args, "--device-code") ?? "<device-code>",
                    ["clientName"] = "learnbot",
                    ["cliVersion"] = Version
                }
                : claimResult
                    ? new Dictionary<string, object?>
                    {
                        ["claimStatus"] = GetOption(args, "--claim-status") ?? "<claim-status>",
                        ["clientName"] = "learnbot",
                        ["cliVersion"] = Version
                    }
                : new Dictionary<string, object?>
                {
                    ["clientName"] = "learnbot",
                    ["cliVersion"] = Version
                };
            using var response = await client.PostAsync(endpoint, Json(body));
            var text = await response.Content.ReadAsStringAsync();
            var responseBody = string.IsNullOrWhiteSpace(text) ? null : JsonSerializer.Deserialize<object>(text, JsonOptions);
            return BuildCliWebSessionPlanFetchResult(
                planKind: normalizedPlanKind,
                serverUrl: server,
                endpoint: endpoint,
                status: response.IsSuccessStatusCode ? "FETCHED_DISABLED_PLAN" : "LOCAL_STATIC_FALLBACK",
                attempted: true,
                networkCallEnabled: true,
                fallbackUsed: !response.IsSuccessStatusCode,
                localPlan: localPlan,
                httpStatusCode: (int)response.StatusCode,
                serverResponse: responseBody,
                error: response.IsSuccessStatusCode ? null : "server returned a non-success status");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return BuildCliWebSessionPlanFetchResult(
                planKind: normalizedPlanKind,
                serverUrl: server,
                endpoint: endpoint,
                status: "LOCAL_STATIC_FALLBACK",
                attempted: true,
                networkCallEnabled: true,
                fallbackUsed: true,
                localPlan: localPlan,
                httpStatusCode: null,
                serverResponse: null,
                error: ex.Message);
        }
    }

    private static IReadOnlyDictionary<string, object?> BuildCliWebSessionLocalFallbackPlan(string planKind, string serverUrl, string endpoint)
    {
        var claim = string.Equals(planKind, "claim", StringComparison.Ordinal);
        var claimResult = string.Equals(planKind, "claim-result", StringComparison.Ordinal);
        var create = string.Equals(planKind, "device-session-create", StringComparison.Ordinal);
        return new Dictionary<string, object?>
        {
            ["schema"] = claim
                ? "learnbot.local-agent.web-session-claim-static-plan.v1"
                : claimResult
                    ? "learnbot.local-agent.web-session-claim-result-static-plan.v1"
                : create
                    ? "learnbot.local-agent.web-device-session-create-static-plan.v1"
                    : "learnbot.local-agent.web-device-session-static-plan.v1",
            ["serverUrl"] = serverUrl,
            ["method"] = "POST",
            ["endpoint"] = endpoint,
            ["absoluteEndpointPreview"] = serverUrl + endpoint,
            ["verificationUriPath"] = create ? "/settings/local-agent/device" : null,
            ["userCodeFormat"] = create ? "XXXX-XXXX" : null,
            ["userCodeLength"] = create ? 8 : null,
            ["expiresInSeconds"] = create ? 600 : null,
            ["pollingIntervalSeconds"] = create ? 5 : null,
            ["claimResultRequired"] = claimResult,
            ["claimResultAccepted"] = false,
            ["accessTokenRequired"] = claimResult,
            ["refreshTokenRequired"] = claimResult,
            ["plaintextTokenSerializationAllowed"] = false,
            ["enabled"] = false,
            ["networkCallEnabled"] = false,
            ["deviceCodeIssuanceEnabled"] = false,
            ["deviceCodeIssued"] = false,
            ["userCodeCreated"] = false,
            ["claimPollingEnabled"] = false,
            ["sessionClaimEnabled"] = false,
            ["accessTokenIssued"] = false,
            ["refreshTokenIssued"] = false,
            ["cookiePersistenceEnabled"] = false,
            ["localSessionArtifactWriteEnabled"] = false,
            ["localSessionArtifactEncryptedRequired"] = claim || claimResult,
            ["artifactWriterPreflightEnabled"] = false,
            ["artifactWriterExecutionEnabled"] = false,
            ["tokenRefreshEnabled"] = false,
            ["localAgentTokenAccepted"] = false,
            ["deviceCodeSecretPrinted"] = false,
            ["tokenSecretPrinted"] = false,
            ["webSessionArtifactBodyPreview"] = claim ? BuildCliWebSessionArtifactBodyPreview(serverUrl) : null,
            ["artifactWriterPlanPreview"] = claimResult ? BuildCliWebSessionArtifactWriterPlanPreview(serverUrl) : null,
            ["reason"] = claim
                ? "Local fallback only describes future claim polling and encrypted web-session storage; it does not poll, claim, issue tokens, or write files."
                : claimResult
                    ? "Local fallback only describes future browser-approved claim-result validation and encrypted web-session artifact writing; it does not accept tokens, serialize plaintext secrets, or write files."
                : create
                    ? "Local fallback only describes the future device-code creation response shape; it does not issue device codes, user codes, tokens, cookies, or stored sessions."
                : "Local fallback only describes future browser/device-code login; it does not issue device codes, tokens, cookies, or stored sessions."
        };
    }

    private static IReadOnlyDictionary<string, object?> BuildCliWebSessionArtifactBodyPreview(string serverUrl) =>
        new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.web-session-artifact.v1",
            ["serverUrl"] = serverUrl,
            ["encryptedAccessToken"] = "<encrypted-access-token>",
            ["encryptedRefreshToken"] = "<encrypted-refresh-token>",
            ["expiresAt"] = "<expires-at>",
            ["refreshExpiresAt"] = "<refresh-expires-at>",
            ["createdAt"] = "<created-at>",
            ["encryption"] = new Dictionary<string, object?>
            {
                ["required"] = true,
                ["provider"] = "LOCAL_OS_SECRET_STORE_OR_DPAPI",
                ["plaintextTokenSerializationAllowed"] = false
            }
        };

    private static IReadOnlyDictionary<string, object?> BuildCliWebSessionArtifactWriterPlanPreview(string serverUrl) =>
        new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.web-session-artifact-writer-plan.v1",
            ["preconditions"] = new[]
            {
                "browser-approved claim result",
                "access token present",
                "refresh token present",
                "expiresAt present",
                "refreshExpiresAt present",
                "local OS secret store or DPAPI available"
            },
            ["artifactBodyPreview"] = BuildCliWebSessionArtifactBodyPreview(serverUrl),
            ["write"] = new Dictionary<string, object?>
            {
                ["enabled"] = false,
                ["atomicReplaceRequired"] = true,
                ["plaintextTokenSerializationAllowed"] = false,
                ["path"] = WebSessionPath()
            }
        };

    private CliWebSessionArtifactWriterPreflightResult BuildCliWebSessionArtifactWriterPreflightResult(string[] args)
    {
        var config = LoadConfigOrDefault();
        var serverUrl = (GetOption(args, "--server") ?? config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        var claimResultAccepted = args.Contains("--approved", StringComparer.OrdinalIgnoreCase);
        var accessTokenPresent = args.Contains("--access-token-present", StringComparer.OrdinalIgnoreCase);
        var refreshTokenPresent = args.Contains("--refresh-token-present", StringComparer.OrdinalIgnoreCase);
        var plaintextAllowed = args.Contains("--allow-plaintext-token-serialization", StringComparer.OrdinalIgnoreCase);
        var writeRequested = args.Contains("--write", StringComparer.OrdinalIgnoreCase);
        var expiresAt = GetOption(args, "--expires-at");
        var refreshExpiresAt = GetOption(args, "--refresh-expires-at");
        var expiresAtValid = DateTimeOffset.TryParse(expiresAt, out _);
        var refreshExpiresAtValid = DateTimeOffset.TryParse(refreshExpiresAt, out _);
        var missing = new List<string>();
        var blockers = new List<string>();

        if (!claimResultAccepted)
        {
            missing.Add("claimResult=APPROVED");
            blockers.Add("browser-approved claim result is required before a local web-session artifact can be prepared.");
        }
        if (!accessTokenPresent)
        {
            missing.Add("accessToken");
            blockers.Add("access token presence must be proven by claim-result metadata; token values are not accepted by this preflight.");
        }
        if (!refreshTokenPresent)
        {
            missing.Add("refreshToken");
            blockers.Add("refresh token presence must be proven by claim-result metadata; token values are not accepted by this preflight.");
        }
        if (string.IsNullOrWhiteSpace(expiresAt) || !expiresAtValid)
        {
            missing.Add("expiresAt");
            blockers.Add("expiresAt must be present and parseable before artifact writing can be considered.");
        }
        if (string.IsNullOrWhiteSpace(refreshExpiresAt) || !refreshExpiresAtValid)
        {
            missing.Add("refreshExpiresAt");
            blockers.Add("refreshExpiresAt must be present and parseable before artifact writing can be considered.");
        }
        if (plaintextAllowed)
        {
            blockers.Add("plaintext token serialization is not allowed for web-session artifacts.");
        }
        if (writeRequested)
        {
            blockers.Add("local web-session artifact writing is still disabled; this command performs preflight only.");
        }

        var passed = blockers.Count == 0;
        return new CliWebSessionArtifactWriterPreflightResult(
            Schema: "learnbot.local-agent.web-session-artifact-writer-preflight-result.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: passed ? "READY_FOR_DISABLED_WRITER" : "BLOCKED_PRECONDITION_FAILED",
            ServerUrl: serverUrl,
            SessionPath: WebSessionPath(),
            ClaimResultAccepted: claimResultAccepted,
            AccessTokenPresent: accessTokenPresent,
            RefreshTokenPresent: refreshTokenPresent,
            ExpiresAtPresent: !string.IsNullOrWhiteSpace(expiresAt),
            RefreshExpiresAtPresent: !string.IsNullOrWhiteSpace(refreshExpiresAt),
            ExpiryFieldsValid: expiresAtValid && refreshExpiresAtValid,
            PlaintextTokenSerializationAllowed: false,
            PlaintextTokenSerializationRequested: plaintextAllowed,
            EncryptionRequired: true,
            EncryptionProvider: "LOCAL_OS_SECRET_STORE_OR_DPAPI",
            EncryptionProviderProbeEnabled: false,
            AtomicReplaceRequired: true,
            ArtifactBodyPreview: BuildCliWebSessionArtifactBodyPreview(serverUrl),
            RequiredClaimResultFields: [
                "claimResult=APPROVED",
                "serverUrl",
                "accessToken",
                "refreshToken",
                "expiresAt",
                "refreshExpiresAt"
            ],
            MissingOrInvalidFields: missing,
            ArtifactWriterPreflightPassed: passed,
            ArtifactWriteRequested: writeRequested,
            ArtifactWriterExecutionEnabled: false,
            LocalSessionArtifactWritten: false,
            LocalAgentTokenUsed: false,
            TokenSecretPrinted: false,
            Blockers: blockers,
            Reason: "This preflight validates a simulated browser-approved claim-result boundary without accepting token values, serializing plaintext secrets, writing files, or using the Local Agent pairing token.");
    }

    private CliWebSessionArtifactWriterTestWriteResult BuildCliWebSessionArtifactWriterTestWriteResult(string[] args)
    {
        var testOnly = args.Contains("--test-only", StringComparer.OrdinalIgnoreCase);
        var preflight = BuildCliWebSessionArtifactWriterPreflightResult(args.Where(arg => !string.Equals(arg, "--test-only", StringComparison.OrdinalIgnoreCase)).ToArray());
        var blockers = preflight.Blockers.ToList();
        if (!testOnly)
        {
            blockers.Add("test-only artifact writing requires explicit --test-only.");
        }

        var path = WebSessionPath();
        var written = false;
        var atomicReplaceUsed = false;
        long? bytesWritten = null;
        string? artifactSha256 = null;
        string? error = null;

        if (blockers.Count == 0)
        {
            try
            {
                var artifact = BuildTestOnlyEncryptedWebSessionArtifact(preflight.ServerUrl);
                var payload = JsonSerializer.Serialize(artifact, JsonOptions);
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var tempPath = path + ".tmp-" + Guid.NewGuid().ToString("N");
                File.WriteAllText(tempPath, payload, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
                File.Move(tempPath, path, overwrite: true);
                atomicReplaceUsed = true;
                written = true;
                var bytes = File.ReadAllBytes(path);
                bytesWritten = bytes.LongLength;
                artifactSha256 = Sha256Hex(bytes);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException)
            {
                error = ex.Message;
                blockers.Add("test-only artifact write failed: " + ex.Message);
            }
        }

        return new CliWebSessionArtifactWriterTestWriteResult(
            Schema: "learnbot.local-agent.web-session-artifact-writer-test-write-result.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: written ? "TEST_ONLY_ARTIFACT_WRITTEN" : "BLOCKED_PRECONDITION_FAILED",
            SessionPath: path,
            TestOnlyMode: testOnly,
            Preflight: preflight,
            ArtifactWriterExecutionEnabled: testOnly && blockers.Count == 0,
            LocalSessionArtifactWritten: written,
            AtomicReplaceUsed: atomicReplaceUsed,
            EncryptionProvider: "TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION",
            PlaintextTokenSerializationAllowed: false,
            PlaintextTokenSerializationDetected: written && ContainsTestOnlyPlaintextTokenMaterial(path),
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            BytesWritten: bytesWritten,
            ArtifactSha256: artifactSha256,
            Blockers: blockers,
            Error: error,
            Reason: "This test-only writer proves atomic encrypted artifact creation using placeholder token material. It is not a real browser claim-result writer and the key material is not persisted in the artifact.");
    }

    private CliWebSessionArtifactReaderTestValidateResult BuildCliWebSessionArtifactReaderTestValidateResult(string[] args)
    {
        var testOnly = args.Contains("--test-only", StringComparer.OrdinalIgnoreCase);
        var path = WebSessionPath();
        var blockers = new List<string>();
        var fileExists = File.Exists(path);
        var jsonParsed = false;
        var schemaValidated = false;
        var encryptionProviderAccepted = false;
        var decrypted = false;
        var plaintextDetected = false;
        string? accessTokenFingerprint = null;
        string? refreshTokenFingerprint = null;
        string? error = null;

        if (!testOnly)
        {
            blockers.Add("test-only artifact read/decrypt validation requires explicit --test-only.");
        }
        if (!fileExists)
        {
            blockers.Add("web-session artifact is missing.");
        }

        if (testOnly && fileExists)
        {
            try
            {
                var text = File.ReadAllText(path);
                plaintextDetected = text.Contains("test-only-access-token-material", StringComparison.Ordinal)
                    || text.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
                using var document = JsonDocument.Parse(text);
                jsonParsed = true;
                var root = document.RootElement;
                schemaValidated = root.TryGetProperty("schema", out var schema)
                    && schema.GetString() == "learnbot.local-agent.web-session-artifact.v1";
                var encryption = root.GetProperty("encryption");
                var provider = encryption.GetProperty("provider").GetString();
                encryptionProviderAccepted = provider == "TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION";
                if (!schemaValidated)
                {
                    blockers.Add("web-session artifact schema is invalid.");
                }
                if (!encryptionProviderAccepted)
                {
                    blockers.Add("only TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION can be decrypted by this test validator.");
                }
                if (plaintextDetected)
                {
                    blockers.Add("plaintext test token material was detected in the artifact.");
                }
                if (schemaValidated && encryptionProviderAccepted && !plaintextDetected)
                {
                    var access = DecryptTestOnlyTokenMaterial(root.GetProperty("encryptedAccessToken").GetString() ?? "");
                    var refresh = DecryptTestOnlyTokenMaterial(root.GetProperty("encryptedRefreshToken").GetString() ?? "");
                    decrypted = access == "test-only-access-token-material" && refresh == "test-only-refresh-token-material";
                    accessTokenFingerprint = TokenFingerprint(access);
                    refreshTokenFingerprint = TokenFingerprint(refresh);
                    if (!decrypted)
                    {
                        blockers.Add("test-only encrypted token material could not be verified.");
                    }
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or CryptographicException or FormatException or KeyNotFoundException)
            {
                error = ex.Message;
                blockers.Add("test-only artifact read/decrypt validation failed: " + ex.Message);
            }
        }

        return new CliWebSessionArtifactReaderTestValidateResult(
            Schema: "learnbot.local-agent.web-session-artifact-reader-test-validate-result.v1",
            CommandName: "learnbot",
            Version: Version,
            Status: blockers.Count == 0 ? "TEST_ONLY_ARTIFACT_DECRYPTED" : "BLOCKED_OR_INVALID",
            SessionPath: path,
            TestOnlyMode: testOnly,
            FileExists: fileExists,
            ReadAttempted: testOnly && fileExists,
            JsonParsed: jsonParsed,
            SchemaValidated: schemaValidated,
            EncryptionProviderAccepted: encryptionProviderAccepted,
            DecryptionAttempted: schemaValidated && encryptionProviderAccepted && !plaintextDetected,
            DecryptionSucceeded: decrypted,
            AccessTokenFingerprint: accessTokenFingerprint,
            RefreshTokenFingerprint: refreshTokenFingerprint,
            PlaintextTokenSerializationDetected: plaintextDetected,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            ProductionStoredSessionLoaded: false,
            Blockers: blockers,
            Error: error,
            Reason: "This validator reads and decrypts only the test-only web-session artifact provider. Production stored-session loading stays disabled.");
    }

    private static IReadOnlyDictionary<string, object?> BuildTestOnlyEncryptedWebSessionArtifact(string serverUrl) =>
        new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.web-session-artifact.v1",
            ["serverUrl"] = serverUrl,
            ["encryptedAccessToken"] = EncryptTestOnlyTokenMaterial("test-only-access-token-material"),
            ["encryptedRefreshToken"] = EncryptTestOnlyTokenMaterial("test-only-refresh-token-material"),
            ["expiresAt"] = "2026-07-03T12:00:00Z",
            ["refreshExpiresAt"] = "2026-07-04T12:00:00Z",
            ["createdAt"] = DateTimeOffset.UtcNow.ToString("O"),
            ["encryption"] = new Dictionary<string, object?>
            {
                ["required"] = true,
                ["provider"] = "TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION",
                ["plaintextTokenSerializationAllowed"] = false,
                ["keyPersisted"] = false
            }
        };

    private static string EncryptTestOnlyTokenMaterial(string value)
    {
        var key = TestOnlyArtifactKey();
        var nonce = RandomNumberGenerator.GetBytes(12);
        var plaintext = Encoding.UTF8.GetBytes(value);
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(key, tag.Length);
        aes.Encrypt(nonce, plaintext, ciphertext, tag);
        return Convert.ToBase64String(nonce) + "." + Convert.ToBase64String(ciphertext) + "." + Convert.ToBase64String(tag);
    }

    private static string DecryptTestOnlyTokenMaterial(string value)
    {
        var parts = value.Split('.');
        if (parts.Length != 3)
        {
            throw new FormatException("test-only encrypted token material must contain nonce, ciphertext, and tag");
        }
        var nonce = Convert.FromBase64String(parts[0]);
        var ciphertext = Convert.FromBase64String(parts[1]);
        var tag = Convert.FromBase64String(parts[2]);
        var plaintext = new byte[ciphertext.Length];
        using var aes = new AesGcm(TestOnlyArtifactKey(), tag.Length);
        aes.Decrypt(nonce, ciphertext, tag, plaintext);
        return Encoding.UTF8.GetString(plaintext);
    }

    private static byte[] TestOnlyArtifactKey() =>
        SHA256.HashData(Encoding.UTF8.GetBytes("learnbot-local-agent-test-only-web-session-artifact-key-v1"));

    private static bool ContainsTestOnlyPlaintextTokenMaterial(string path)
    {
        var text = File.ReadAllText(path);
        return text.Contains("test-only-access-token-material", StringComparison.Ordinal)
            || text.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
    }

    private static CliWebSessionPlanFetchResult BuildCliWebSessionPlanFetchResult(
        string planKind,
        string serverUrl,
        string endpoint,
        string status,
        bool attempted,
        bool networkCallEnabled,
        bool fallbackUsed,
        IReadOnlyDictionary<string, object?> localPlan,
        int? httpStatusCode,
        object? serverResponse,
        string? error) =>
        new(
            Schema: "learnbot.local-agent.web-session-plan-fetch-result.v1",
            CommandName: "learnbot",
            Version: Version,
            PlanKind: planKind,
            Status: status,
            ServerUrl: serverUrl,
            Attempted: attempted,
            NetworkCallEnabled: networkCallEnabled,
            FallbackUsed: fallbackUsed,
            UsedLocalAgentToken: false,
            TokenSecretPrinted: false,
            RequestCreated: false,
            DeviceCodeIssued: false,
            SessionClaimed: false,
            AccessTokenIssued: false,
            RefreshTokenIssued: false,
            CookiePersistenceEnabled: false,
            LocalSessionArtifactWritten: false,
            Endpoint: endpoint,
            Method: "POST",
            LocalPlan: localPlan,
            HttpStatusCode: httpStatusCode,
            ServerResponse: serverResponse,
            Error: error);

    private static string? GetOption(string[] args, string name)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
        }
        return null;
    }

    private static string PositionalText(string[] args)
    {
        var values = new List<string>();
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i].StartsWith("--", StringComparison.Ordinal))
            {
                if (!IsBooleanOption(args[i]) && i + 1 < args.Length && !args[i + 1].StartsWith("--", StringComparison.Ordinal))
                {
                    i++;
                }
                continue;
            }
            values.Add(args[i]);
        }
        return string.Join(' ', values).Trim();
    }

    private static bool IsBooleanOption(string option) =>
        string.Equals(option, "--server-plan", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--json", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--preview", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--browser", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--device", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--no-open", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--no-remember", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--no-auto-loop", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--no-apply", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--observe-read-only", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--read-selected", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--remember", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--offline", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--accept-generated-diff-preview", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--run-nonwriting-preflight-preview", StringComparison.OrdinalIgnoreCase)
        || string.Equals(option, "--include-approval-handoff-preview", StringComparison.OrdinalIgnoreCase);

    private static int ParseInt(string? value, int fallback) => int.TryParse(value, out var parsed) ? parsed : fallback;

    private static string NormalizeTransport(string? value)
    {
        var normalized = string.IsNullOrWhiteSpace(value) ? "polling" : value.Trim().ToLowerInvariant();
        return normalized is "polling" or "websocket" or "auto" ? normalized : "polling";
    }

    private static string WebSocketUrl(AgentConfig config)
    {
        var server = (config.ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        if (server.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return "wss://" + server["https://".Length..] + "/api/local-agents/ws";
        }
        if (server.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            return "ws://" + server["http://".Length..] + "/api/local-agents/ws";
        }
        return server + "/api/local-agents/ws";
    }

    private static TimeSpan WebSocketRetryDelay(int failureCount)
    {
        var capped = Math.Clamp(failureCount, 1, 5);
        return TimeSpan.FromSeconds(Math.Min(60, 5 * (1 << (capped - 1))));
    }

    private static string TokenFingerprint(string? token)
    {
        if (string.IsNullOrWhiteSpace(token)) return "";
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(token));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }

    private static Dictionary<string, object?> PatchDryRunOutput(
        Guid workspaceId,
        JsonElement input,
        List<Dictionary<string, object?>> fileResults,
        bool preflightPassed,
        SnapshotCreationResult? snapshot = null) => new()
    {
        ["workspaceId"] = workspaceId,
        ["dryRun"] = true,
        ["preflightPassed"] = preflightPassed,
        ["mutationApplied"] = false,
        ["snapshotCreated"] = snapshot?.Created ?? false,
        ["snapshotObservation"] = SnapshotObservation(input, fileResults, snapshot),
        ["rollbackObservation"] = RollbackObservation(input, fileResults),
        ["files"] = fileResults
    };

    private static Dictionary<string, object?> SnapshotObservation(JsonElement input, List<Dictionary<string, object?>> fileResults, SnapshotCreationResult? snapshot)
    {
        JsonElement? policy = input.TryGetProperty("snapshotPolicy", out var value) && value.ValueKind == JsonValueKind.Object
            ? value
            : null;
        var created = snapshot?.Created ?? false;
        return new Dictionary<string, object?>
        {
            ["required"] = TryObjectBool(policy, "required") ?? true,
            ["scope"] = TryObjectString(policy, "scope") ?? "TARGET_FILES",
            ["location"] = TryObjectString(policy, "location") ?? "LOCAL_AGENT_MANAGED",
            ["createBeforeMutation"] = TryObjectBool(policy, "createBeforeMutation") ?? true,
            ["includeExpectedHashes"] = TryObjectBool(policy, "includeExpectedHashes") ?? true,
            ["manifestPreview"] = snapshot?.Manifest ?? SnapshotManifestPreview(input, fileResults),
            ["wouldCreate"] = true,
            ["created"] = created,
            ["error"] = snapshot?.Error,
            ["files"] = fileResults.Select(SnapshotFileObservation).ToList()
        };
    }

    private static Dictionary<string, object?> RollbackObservation(JsonElement input, List<Dictionary<string, object?>> fileResults)
    {
        JsonElement? policy = input.TryGetProperty("rollbackPolicy", out var value) && value.ValueKind == JsonValueKind.Object
            ? value
            : null;
        return new Dictionary<string, object?>
        {
            ["required"] = TryObjectBool(policy, "required") ?? true,
            ["tool"] = TryObjectString(policy, "tool") ?? "rollback.restore",
            ["restoreScope"] = TryObjectString(policy, "restoreScope") ?? "SNAPSHOT_TARGET_FILES",
            ["requiresUserApproval"] = TryObjectBool(policy, "requiresUserApproval") ?? true,
            ["restorePreconditions"] = RollbackRestorePreconditions(fileResults),
            ["wouldRestore"] = true,
            ["restored"] = false,
            ["files"] = fileResults.Select(SnapshotFileObservation).ToList()
        };
    }

    private static Dictionary<string, object?> SnapshotManifestPreview(JsonElement input, List<Dictionary<string, object?>> fileResults)
    {
        var manifestId = SnapshotManifestId(input, fileResults);
        var targetPaths = fileResults
            .Select(file => file.TryGetValue("path", out var path) ? path?.ToString() : null)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(path => path!)
            .ToList();
        var validLayout = TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, targetPaths, out var layout, out var layoutError);
        return new Dictionary<string, object?>
        {
            ["id"] = manifestId,
            ["version"] = 1,
            ["schema"] = "learnbot.local-agent.snapshot-manifest.v1",
            ["workspaceId"] = TryInputGuidString(input, "workspaceId"),
            ["sourceRequestId"] = TryInputStringFromObject(input, "sourceRequestId"),
            ["managedRoot"] = "%USERPROFILE%\\.learnbot\\snapshots",
            ["relativeManifestPath"] = layout?.RelativeManifestPath ?? manifestId + "\\manifest.json",
            ["contentStrategy"] = "COPY_TARGET_FILES_BEFORE_MUTATION",
            ["created"] = false,
            ["writesPlanned"] = false,
            ["pathGuardPassed"] = validLayout,
            ["pathGuardError"] = layoutError,
            ["files"] = fileResults.Select(file => SnapshotManifestFilePreview(file, layout)).ToList(),
            ["cleanupPolicy"] = new Dictionary<string, object?>
            {
                ["retentionDays"] = 30,
                ["deleteOnlyAfterSuccessfulRollbackOrUserCleanup"] = true
            }
        };
    }

    private static Dictionary<string, object?> SnapshotManifestFilePreview(Dictionary<string, object?> file, SnapshotLayout? layout)
    {
        var path = file.TryGetValue("path", out var pathValue) ? pathValue : null;
        var normalizedPath = path?.ToString()?.Replace('\\', '/');
        var snapshotFile = layout?.Files.FirstOrDefault(item => string.Equals(item.Path, normalizedPath, StringComparison.Ordinal));
        return new Dictionary<string, object?>
        {
            ["path"] = path,
            ["snapshotRelativePath"] = snapshotFile?.SnapshotRelativePath ?? (path is null ? null : "files/" + path.ToString()!.Replace('\\', '/')),
            ["expectedSha256"] = file.TryGetValue("expectedSha256", out var expected) ? expected : null,
            ["actualSha256"] = file.TryGetValue("actualSha256", out var actual) ? actual : null,
            ["hashMatches"] = file.TryGetValue("hashMatches", out var hashMatches) ? hashMatches : null,
            ["contextMatches"] = file.TryGetValue("contextMatches", out var contextMatches) ? contextMatches : null
        };
    }

    private static List<Dictionary<string, object?>> RollbackRestorePreconditions(List<Dictionary<string, object?>> fileResults) =>
    [
        new()
        {
            ["key"] = "snapshotManifestExists",
            ["required"] = true,
            ["previewOnly"] = true
        },
        new()
        {
            ["key"] = "targetFilesStillWithinWorkspace",
            ["required"] = true,
            ["previewOnly"] = true
        },
        new()
        {
            ["key"] = "targetFileCount",
            ["required"] = fileResults.Count,
            ["previewOnly"] = true
        },
        new()
        {
            ["key"] = "userApprovalRequired",
            ["required"] = true,
            ["previewOnly"] = true
        }
    ];

    private static string SnapshotManifestId(JsonElement input, List<Dictionary<string, object?>> fileResults)
    {
        var sourceRequestId = TryInputStringFromObject(input, "sourceRequestId") ?? "no-source-request";
        var fileKey = string.Join("|", fileResults.Select(file => file.TryGetValue("path", out var path) ? path?.ToString() ?? "" : ""));
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(sourceRequestId + "|" + fileKey));
        return "snap-" + Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }

    private static SnapshotCreationResult CreateSnapshot(
        Guid workspaceId,
        string workspaceRoot,
        JsonElement input,
        List<Dictionary<string, object?>> fileResults)
    {
        var manifestId = SnapshotManifestId(input, fileResults);
        var targetPaths = fileResults
            .Select(file => file.TryGetValue("path", out var path) ? path?.ToString() : null)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(path => path!)
            .ToList();
        if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, targetPaths, out var layout, out var layoutError) || layout is null)
        {
            return SnapshotCreationResult.Failed(layoutError ?? "Invalid snapshot layout.");
        }
        if (Directory.Exists(layout.SnapshotRoot))
        {
            manifestId = manifestId + "-" + DateTimeOffset.UtcNow.ToString("yyyyMMddHHmmssfff");
            if (!TryBuildSnapshotLayout(AgentDataDirectory(), manifestId, targetPaths, out layout, out layoutError) || layout is null)
            {
                return SnapshotCreationResult.Failed(layoutError ?? "Invalid snapshot layout.");
            }
        }

        var stagingRoot = Path.GetFullPath(Path.Combine(layout.SnapshotsRoot, layout.ManifestId + ".staging-" + Guid.NewGuid().ToString("N")));
        if (!IsWithin(layout.SnapshotsRoot, stagingRoot))
        {
            return SnapshotCreationResult.Failed("Snapshot staging path escapes managed snapshot directory.");
        }
        if (Directory.Exists(layout.SnapshotRoot))
        {
            return SnapshotCreationResult.Failed("Snapshot target already exists: " + layout.RelativeManifestPath);
        }

        try
        {
            var manifestFiles = new List<Dictionary<string, object?>>();
            foreach (var file in fileResults)
            {
                var path = file.TryGetValue("path", out var pathValue) ? pathValue?.ToString()?.Replace('\\', '/') : null;
                if (string.IsNullOrWhiteSpace(path))
                {
                    return SnapshotCreationResult.Failed("Snapshot target file path is missing.");
                }
                var layoutFile = layout.Files.FirstOrDefault(item => string.Equals(item.Path, path, StringComparison.Ordinal));
                if (layoutFile is null)
                {
                    return SnapshotCreationResult.Failed("Snapshot target file was not present in the validated layout: " + path);
                }

                var sourcePath = file.TryGetValue("absolutePath", out var absolutePath) ? absolutePath?.ToString() : null;
                if (string.IsNullOrWhiteSpace(sourcePath))
                {
                    return SnapshotCreationResult.Failed("Snapshot source file path is missing: " + path);
                }
                sourcePath = Path.GetFullPath(sourcePath);
                if (!IsWithin(workspaceRoot, sourcePath))
                {
                    return SnapshotCreationResult.Failed("Snapshot source file escapes the approved workspace: " + path);
                }
                if (!File.Exists(sourcePath))
                {
                    return SnapshotCreationResult.Failed("Snapshot source file was not found: " + path);
                }

                var bytes = File.ReadAllBytes(sourcePath);
                if (bytes.Any(value => value == 0))
                {
                    return SnapshotCreationResult.Failed("Binary files are not supported by patch.apply snapshot: " + path);
                }
                var actualSha = Sha256Hex(bytes);
                var previousActual = file.TryGetValue("actualSha256", out var previousActualValue) ? previousActualValue?.ToString() : null;
                if (!string.IsNullOrWhiteSpace(previousActual) && !string.Equals(previousActual, actualSha, StringComparison.OrdinalIgnoreCase))
                {
                    return SnapshotCreationResult.Failed("Snapshot source changed after dry-run preflight: " + path);
                }

                var destinationPath = Path.GetFullPath(Path.Combine(stagingRoot, layoutFile.SnapshotRelativePath.Replace('/', Path.DirectorySeparatorChar)));
                if (!IsWithin(stagingRoot, destinationPath))
                {
                    return SnapshotCreationResult.Failed("Snapshot staging file path escapes staging root: " + path);
                }
                Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);
                File.Copy(sourcePath, destinationPath, overwrite: false);

                manifestFiles.Add(new Dictionary<string, object?>
                {
                    ["path"] = path,
                    ["snapshotRelativePath"] = layoutFile.SnapshotRelativePath,
                    ["expectedSha256"] = file.TryGetValue("expectedSha256", out var expected) ? expected : null,
                    ["actualSha256"] = actualSha,
                    ["bytes"] = bytes.LongLength,
                    ["hashMatches"] = file.TryGetValue("hashMatches", out var hashMatches) ? hashMatches : null,
                    ["contextMatches"] = file.TryGetValue("contextMatches", out var contextMatches) ? contextMatches : null
                });
            }

            var manifest = new Dictionary<string, object?>
            {
                ["id"] = layout.ManifestId,
                ["version"] = 1,
                ["schema"] = "learnbot.local-agent.snapshot-manifest.v1",
                ["workspaceId"] = workspaceId,
                ["sourceRequestId"] = TryInputStringFromObject(input, "sourceRequestId"),
                ["createdAt"] = DateTimeOffset.UtcNow,
                ["workspaceRoot"] = workspaceRoot,
                ["managedRoot"] = "%USERPROFILE%\\.learnbot\\snapshots",
                ["relativeManifestPath"] = layout.RelativeManifestPath,
                ["contentStrategy"] = "COPY_TARGET_FILES_BEFORE_MUTATION",
                ["created"] = true,
                ["writesPlanned"] = true,
                ["writesCompleted"] = true,
                ["pathGuardPassed"] = true,
                ["files"] = manifestFiles,
                ["cleanupPolicy"] = new Dictionary<string, object?>
                {
                    ["retentionDays"] = 30,
                    ["deleteOnlyAfterSuccessfulRollbackOrUserCleanup"] = true
                }
            };

            Directory.CreateDirectory(stagingRoot);
            var stagingManifestPath = Path.GetFullPath(Path.Combine(stagingRoot, "manifest.json"));
            if (!IsWithin(stagingRoot, stagingManifestPath))
            {
                return SnapshotCreationResult.Failed("Snapshot manifest staging path escapes staging root.");
            }
            File.WriteAllText(stagingManifestPath, JsonSerializer.Serialize(manifest, JsonOptions));
            Directory.Move(stagingRoot, layout.SnapshotRoot);
            return SnapshotCreationResult.Succeeded(manifest);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return SnapshotCreationResult.Failed("Snapshot creation failed: " + ex.Message);
        }
        finally
        {
            if (Directory.Exists(stagingRoot))
            {
                try
                {
                    Directory.Delete(stagingRoot, recursive: true);
                }
                catch (IOException)
                {
                    // Best-effort cleanup only. The failed tool result still reports snapshot creation failure.
                }
                catch (UnauthorizedAccessException)
                {
                    // Best-effort cleanup only. The failed tool result still reports snapshot creation failure.
                }
            }
        }
    }

    private static bool TryBuildSnapshotLayout(
        string agentDataDirectory,
        string manifestId,
        IReadOnlyCollection<string> targetPaths,
        out SnapshotLayout? layout,
        out string? error)
    {
        layout = null;
        error = null;
        if (string.IsNullOrWhiteSpace(manifestId) || !manifestId.StartsWith("snap-", StringComparison.Ordinal) || manifestId.Any(ch => !(char.IsLetterOrDigit(ch) || ch == '-')))
        {
            error = "Invalid snapshot manifest id.";
            return false;
        }
        var snapshotsRoot = Path.GetFullPath(Path.Combine(agentDataDirectory, "snapshots")).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var snapshotRoot = Path.GetFullPath(Path.Combine(snapshotsRoot, manifestId)).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (!IsWithin(snapshotsRoot, snapshotRoot))
        {
            error = "Snapshot root escapes managed snapshot directory.";
            return false;
        }
        var manifestPath = Path.GetFullPath(Path.Combine(snapshotRoot, "manifest.json"));
        if (!IsWithin(snapshotRoot, manifestPath))
        {
            error = "Snapshot manifest path escapes snapshot root.";
            return false;
        }

        var files = new List<SnapshotFilePath>();
        foreach (var rawPath in targetPaths)
        {
            var normalized = rawPath.Replace('\\', '/');
            if (!IsSafeRelativeSnapshotPath(normalized))
            {
                error = "Snapshot target path is not workspace-relative: " + rawPath;
                return false;
            }
            var snapshotRelativePath = "files/" + normalized;
            var destinationPath = Path.GetFullPath(Path.Combine(snapshotRoot, snapshotRelativePath.Replace('/', Path.DirectorySeparatorChar)));
            if (!IsWithin(snapshotRoot, destinationPath))
            {
                error = "Snapshot file path escapes snapshot root: " + rawPath;
                return false;
            }
            files.Add(new SnapshotFilePath(normalized, snapshotRelativePath, destinationPath));
        }

        layout = new SnapshotLayout(manifestId, snapshotsRoot, snapshotRoot, manifestId + "/manifest.json", manifestPath, files);
        return true;
    }

    private static bool IsSafeRelativeSnapshotPath(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Contains(':') || value.StartsWith("/", StringComparison.Ordinal) || Path.IsPathRooted(value))
        {
            return false;
        }
        var parts = value.Split('/', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length > 0 && parts.All(part => part != "." && part != "..");
    }

    private static Dictionary<string, object?> SnapshotFileObservation(Dictionary<string, object?> file)
    {
        return new Dictionary<string, object?>
        {
            ["path"] = file.TryGetValue("path", out var path) ? path : null,
            ["expectedSha256"] = file.TryGetValue("expectedSha256", out var expected) ? expected : null,
            ["actualSha256"] = file.TryGetValue("actualSha256", out var actual) ? actual : null,
            ["hashMatches"] = file.TryGetValue("hashMatches", out var hashMatches) ? hashMatches : null,
            ["contextMatches"] = file.TryGetValue("contextMatches", out var contextMatches) ? contextMatches : null
        };
    }

    private static bool? TryObjectBool(JsonElement? input, string property)
    {
        return input is { ValueKind: JsonValueKind.Object } value
            ? TryInputBool(value, property)
            : null;
    }

    private static string? TryObjectString(JsonElement? input, string property)
    {
        return input is { ValueKind: JsonValueKind.Object } value
            ? TryInputStringFromObject(value, property)
            : null;
    }

    private static string? TryInputStringFromObject(JsonElement input, string property)
    {
        if (!input.TryGetProperty(property, out var value))
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static string? TryInputGuidString(JsonElement input, string property)
    {
        if (!input.TryGetProperty(property, out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }
        if (value.ValueKind == JsonValueKind.String && Guid.TryParse(value.GetString(), out var parsed))
        {
            return parsed.ToString();
        }
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static PatchParseResult ParseUnifiedDiff(string diff)
    {
        var lines = diff.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
        var files = new List<PatchFile>();
        PatchFile? currentFile = null;
        PatchHunk? currentHunk = null;
        string? oldPath = null;

        foreach (var rawLine in lines)
        {
            if (rawLine.StartsWith("--- ", StringComparison.Ordinal))
            {
                oldPath = NormalizeDiffPath(rawLine[4..].Trim());
                currentHunk = null;
                continue;
            }
            if (rawLine.StartsWith("+++ ", StringComparison.Ordinal))
            {
                var newPath = NormalizeDiffPath(rawLine[4..].Trim());
                var path = newPath == "/dev/null" ? oldPath : newPath;
                if (string.IsNullOrWhiteSpace(path) || path == "/dev/null")
                {
                    return PatchParseResult.Fail("Patch target path is missing.");
                }
                currentFile = new PatchFile(path);
                files.Add(currentFile);
                currentHunk = null;
                continue;
            }
            if (rawLine.StartsWith("@@ ", StringComparison.Ordinal))
            {
                if (currentFile is null)
                {
                    return PatchParseResult.Fail("Patch hunk appeared before a file header.");
                }
                if (!TryParseHunkHeader(rawLine, out var oldStart, out var oldCount))
                {
                    return PatchParseResult.Fail("Invalid hunk header: " + rawLine);
                }
                currentHunk = new PatchHunk(oldStart, oldCount);
                currentFile.Hunks.Add(currentHunk);
                continue;
            }
            if (currentHunk is null)
            {
                continue;
            }
            if (rawLine.StartsWith("\\", StringComparison.Ordinal))
            {
                continue;
            }
            if (rawLine.Length == 0)
            {
                currentHunk.Lines.Add(new PatchLine(' ', ""));
                continue;
            }
            var marker = rawLine[0];
            if (marker is ' ' or '-' or '+')
            {
                currentHunk.Lines.Add(new PatchLine(marker, rawLine[1..]));
                continue;
            }
            return PatchParseResult.Fail("Invalid patch line marker in hunk.");
        }

        return PatchParseResult.Ok(files);
    }

    private static HunkDryRunResult DryRunHunk(string[] fileLines, PatchHunk hunk)
    {
        var expected = hunk.Lines
            .Where(line => line.Marker is ' ' or '-')
            .Select(line => line.Text)
            .ToArray();
        if (expected.Length == 0)
        {
            return new HunkDryRunResult(hunk.OldStart, hunk.OldCount, true, "Hunk has no existing-line context to verify.");
        }

        var startIndex = Math.Max(0, hunk.OldStart - 1);
        if (MatchesAt(fileLines, expected, startIndex))
        {
            return new HunkDryRunResult(hunk.OldStart, hunk.OldCount, true, "Hunk context matched at the expected location.");
        }

        for (var index = 0; index <= fileLines.Length - expected.Length; index++)
        {
            if (index == startIndex) continue;
            if (MatchesAt(fileLines, expected, index))
            {
                return new HunkDryRunResult(hunk.OldStart, hunk.OldCount, true, "Hunk context matched at a shifted location.");
            }
        }

        return new HunkDryRunResult(hunk.OldStart, hunk.OldCount, false, "Hunk context did not match the local file.");
    }

    private static bool TryApplyPatchToLines(
        IReadOnlyList<string> originalLines,
        PatchFile patchFile,
        out List<string> updatedLines,
        out string? error)
    {
        updatedLines = originalLines.ToList();
        error = null;
        var lineOffset = 0;

        foreach (var hunk in patchFile.Hunks)
        {
            var startIndex = Math.Max(0, hunk.OldStart - 1 + lineOffset);
            if (!TryApplyHunkToLines(updatedLines, hunk, startIndex, out var delta, out error))
            {
                error = "Patch hunk could not be applied to " + patchFile.Path + ": " + error;
                return false;
            }
            lineOffset += delta;
        }

        return true;
    }

    private static bool TryApplyHunkToLines(
        List<string> lines,
        PatchHunk hunk,
        int startIndex,
        out int delta,
        out string? error)
    {
        delta = 0;
        error = null;
        if (startIndex < 0 || startIndex > lines.Count)
        {
            error = "hunk start is outside the file.";
            return false;
        }

        var cursor = startIndex;
        var removeCount = 0;
        var replacement = new List<string>();
        foreach (var line in hunk.Lines)
        {
            if (line.Marker is ' ' or '-')
            {
                if (cursor >= lines.Count)
                {
                    error = "hunk expected more existing lines than the file contains.";
                    return false;
                }
                if (!string.Equals(lines[cursor], line.Text, StringComparison.Ordinal))
                {
                    error = "hunk context does not match at line " + (cursor + 1) + ".";
                    return false;
                }
                cursor++;
                removeCount++;
            }

            if (line.Marker is ' ' or '+')
            {
                replacement.Add(line.Text);
            }
        }

        lines.RemoveRange(startIndex, removeCount);
        lines.InsertRange(startIndex, replacement);
        delta = replacement.Count - removeCount;
        return true;
    }

    private static PatchWriteSequenceResult TryWritePatchedFileWithRecheck(
        string workspaceRoot,
        string targetPath,
        PatchFile patchFile,
        string? expectedSha256)
    {
        var root = Path.GetFullPath(workspaceRoot);
        var target = Path.GetFullPath(targetPath);
        if (!IsWithin(root, target))
        {
            return PatchWriteSequenceResult.Failed("Patch target escapes the approved workspace.");
        }
        if (!File.Exists(target))
        {
            return PatchWriteSequenceResult.Failed("Patch target file was not found.");
        }

        var beforeBytes = File.ReadAllBytes(target);
        if (beforeBytes.Any(value => value == 0))
        {
            return PatchWriteSequenceResult.Failed("Binary files are not supported by patch write.");
        }
        var beforeSha = Sha256Hex(beforeBytes);
        if (!string.IsNullOrWhiteSpace(expectedSha256)
            && !string.Equals(expectedSha256, beforeSha, StringComparison.OrdinalIgnoreCase))
        {
            return PatchWriteSequenceResult.Failed("Patch target changed after snapshot creation.");
        }

        var text = DecodeUtf8PreservingBom(beforeBytes, out var hasUtf8Bom);
        var newline = DetectLineEnding(text);
        var hadFinalNewline = text.EndsWith("\n", StringComparison.Ordinal);
        var lines = SplitLines(text);
        if (hadFinalNewline && lines.Length > 0 && lines[^1].Length == 0)
        {
            lines = lines[..^1];
        }

        if (!TryApplyPatchToLines(lines, patchFile, out var updatedLines, out var error))
        {
            return PatchWriteSequenceResult.Failed(error ?? "Patch hunk could not be applied.");
        }

        var updatedText = string.Join(newline, updatedLines);
        if (hadFinalNewline)
        {
            updatedText += newline;
        }
        var updatedBytes = EncodeUtf8PreservingBom(updatedText, hasUtf8Bom);
        var tempPath = target + ".learnbot-patch-" + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(tempPath, updatedBytes);
            File.Move(tempPath, target, overwrite: true);
            var afterBytes = File.ReadAllBytes(target);
            var afterSha = Sha256Hex(afterBytes);
            return PatchWriteSequenceResult.Succeeded(beforeSha, afterSha, beforeBytes.LongLength, afterBytes.LongLength, newline);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return PatchWriteSequenceResult.Failed("Patch write failed: " + ex.Message);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                try
                {
                    File.Delete(tempPath);
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

    private static string DetectLineEnding(string text) =>
        text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";

    private static string DecodeUtf8PreservingBom(byte[] bytes, out bool hasUtf8Bom)
    {
        hasUtf8Bom = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
        return Encoding.UTF8.GetString(hasUtf8Bom ? bytes[3..] : bytes);
    }

    private static byte[] EncodeUtf8PreservingBom(string text, bool hasUtf8Bom)
    {
        var body = Encoding.UTF8.GetBytes(text);
        if (!hasUtf8Bom)
        {
            return body;
        }
        var output = new byte[body.Length + 3];
        output[0] = 0xEF;
        output[1] = 0xBB;
        output[2] = 0xBF;
        Buffer.BlockCopy(body, 0, output, 3, body.Length);
        return output;
    }

    private static bool MatchesAt(string[] fileLines, string[] expected, int startIndex)
    {
        if (startIndex < 0 || startIndex + expected.Length > fileLines.Length) return false;
        for (var offset = 0; offset < expected.Length; offset++)
        {
            if (!string.Equals(fileLines[startIndex + offset], expected[offset], StringComparison.Ordinal))
            {
                return false;
            }
        }
        return true;
    }

    private static bool TryParseHunkHeader(string header, out int oldStart, out int oldCount)
    {
        oldStart = 0;
        oldCount = 0;
        var marker = header.Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault(part => part.StartsWith("-", StringComparison.Ordinal));
        if (marker is null) return false;
        var range = marker[1..].Split(',', 2);
        if (!int.TryParse(range[0], out oldStart) || oldStart < 0) return false;
        oldCount = range.Length == 2 && int.TryParse(range[1], out var parsedCount) ? parsedCount : 1;
        return oldCount >= 0;
    }

    private static string NormalizeDiffPath(string path)
    {
        if (path == "/dev/null") return path;
        var withoutTimestamp = path.Split('\t')[0].Trim();
        if (withoutTimestamp.StartsWith("a/", StringComparison.Ordinal) || withoutTimestamp.StartsWith("b/", StringComparison.Ordinal))
        {
            withoutTimestamp = withoutTimestamp[2..];
        }
        return withoutTimestamp.Replace('\\', '/');
    }

    private static string[] SplitLines(string content) =>
        content.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');

    private static string Sha256Hex(byte[] bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private static List<string> TryInputStringList(JsonElement input, string property)
    {
        if (!input.TryGetProperty(property, out var value) || value.ValueKind != JsonValueKind.Array)
        {
            return [];
        }
        return value.EnumerateArray()
            .Where(item => item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
            .Select(item => item.GetString()!.Replace('\\', '/'))
            .ToList();
    }

    private static Dictionary<string, ExpectedFile> TryExpectedFiles(JsonElement input)
    {
        if (!input.TryGetProperty("expectedFiles", out var value) || value.ValueKind != JsonValueKind.Array)
        {
            return new Dictionary<string, ExpectedFile>(StringComparer.Ordinal);
        }
        return value.EnumerateArray()
            .Where(item => item.ValueKind == JsonValueKind.Object)
            .Select(item => new ExpectedFile(
                item.TryGetProperty("path", out var path) && path.ValueKind == JsonValueKind.String ? path.GetString()?.Replace('\\', '/') ?? "" : "",
                item.TryGetProperty("sha256", out var sha) && sha.ValueKind == JsonValueKind.String ? sha.GetString() ?? "" : ""))
            .Where(item => !string.IsNullOrWhiteSpace(item.Path))
            .GroupBy(item => item.Path, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
    }

    private static bool? TryInputBool(JsonElement input, string property)
    {
        if (!input.TryGetProperty(property, out var value))
        {
            return null;
        }
        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.String when bool.TryParse(value.GetString(), out var parsed) => parsed,
            _ => null
        };
    }

    private static Guid? TryGuid(JsonElement element, string property)
    {
        return element.TryGetProperty(property, out var value) && value.ValueKind != JsonValueKind.Null
            ? value.GetGuid()
            : null;
    }

    private static Guid? TryEnvelopeRequestId(JsonElement envelope)
    {
        if (!envelope.TryGetProperty("requestId", out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.String && Guid.TryParse(value.GetString(), out var parsed) ? parsed : null;
    }

    private static string? TryInputString(JsonElement request, string property)
    {
        if (!request.TryGetProperty("input", out var input) || !input.TryGetProperty(property, out var value))
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static int? TryInputInt(JsonElement request, string property)
    {
        if (!request.TryGetProperty("input", out var input) || !input.TryGetProperty(property, out var value))
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var parsed) ? parsed : null;
    }

    private static bool IsWithin(string root, string target)
    {
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        return string.Equals(root, target, comparison)
            || target.StartsWith(root + Path.DirectorySeparatorChar, comparison)
            || target.StartsWith(root + Path.AltDirectorySeparatorChar, comparison);
    }

    private static bool PathEquals(string left, string right) =>
        string.Equals(Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar), Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase);

    private static async Task<int> SelfTest(string[] args)
    {
        if (args.Length == 0)
        {
            return Unknown("self-test " + string.Join(' ', args));
        }
        if (string.Equals(args[0], "snapshot-create", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestSnapshotCreate();
        }
        if (string.Equals(args[0], "patch-apply-memory", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchApplyMemory();
        }
        if (string.Equals(args[0], "patch-dry-run-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchDryRunContract();
        }
        if (string.Equals(args[0], "patch-apply-mutation-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchApplyMutationContract();
        }
        if (string.Equals(args[0], "tool-response-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestToolResponseContract();
        }
        if (string.Equals(args[0], "patch-write-sequence", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchWriteSequence();
        }
        if (string.Equals(args[0], "rollback-restore-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestRollbackRestoreContract();
        }
        if (string.Equals(args[0], "allowed-test-runner-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestAllowedTestRunnerContract();
        }
        if (string.Equals(args[0], "approved-execution-flow-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestApprovedExecutionFlowContract(GetOption(args, "--report"));
        }
        if (string.Equals(args[0], "approved-server-queue-flow-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestApprovedServerQueueFlowContract(GetOption(args, "--report"));
        }
        if (string.Equals(args[0], "approved-server-queue-second-attempt-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestApprovedServerQueueSecondAttemptContract();
        }
        if (string.Equals(args[0], "cli-status-doctor-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestCliStatusDoctorContract();
        }
        if (string.Equals(args[0], "m8-productization-status-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestM8ProductizationStatusContract();
        }
        if (string.Equals(args[0], "m8-doctor-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestM8DoctorContract();
        }
        if (string.Equals(args[0], "codex-command-preview-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestCodexCommandPreviewContract();
        }
        if (string.Equals(args[0], "codex-read-only-observation-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestCodexReadOnlyObservationContract();
        }
        if (string.Equals(args[0], "codex-server-plan-fetch-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestCodexServerPlanFetchContract();
        }
        if (string.Equals(args[0], "web-login-session-preview-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebLoginSessionPreviewContract();
        }
        if (string.Equals(args[0], "web-session-plan-fetch-contract", StringComparison.OrdinalIgnoreCase))
        {
            return await SelfTestWebSessionPlanFetchContract();
        }
        if (string.Equals(args[0], "web-session-server-plan-readiness-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionServerPlanReadinessContract();
        }
        if (string.Equals(args[0], "web-session-secret-provider-plan-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionSecretProviderPlanContract();
        }
        if (string.Equals(args[0], "web-session-secret-provider-probe-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionSecretProviderProbeContract();
        }
        if (string.Equals(args[0], "web-session-production-artifact-crypto-preview-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionProductionArtifactCryptoPreviewContract();
        }
        if (string.Equals(args[0], "web-session-production-artifact-writer-preview-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionProductionArtifactWriterPreviewContract();
        }
        if (string.Equals(args[0], "web-session-production-artifact-reader-preview-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionProductionArtifactReaderPreviewContract();
        }
        if (string.Equals(args[0], "web-session-stored-session-auth-readiness-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionStoredSessionAuthReadinessContract();
        }
        if (string.Equals(args[0], "web-session-artifact-validation-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionArtifactValidationContract();
        }
        if (string.Equals(args[0], "web-session-artifact-writer-preflight-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionArtifactWriterPreflightContract();
        }
        if (string.Equals(args[0], "web-session-artifact-writer-test-write-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionArtifactWriterTestWriteContract();
        }
        if (string.Equals(args[0], "web-session-artifact-reader-test-validate-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWebSessionArtifactReaderTestValidateContract();
        }
        if (string.Equals(args[0], "pair-atomic-config-contract", StringComparison.OrdinalIgnoreCase))
        {
            return await SelfTestPairAtomicConfigContract();
        }
        if (string.Equals(args[0], "workspace-tree-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWorkspaceTreeContract();
        }
        if (string.Equals(args[0], "workspace-search-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestWorkspaceSearchContract();
        }
        if (string.Equals(args[0], "non-git-workspace-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestNonGitWorkspaceContract();
        }
        if (string.Equals(args[0], "read-only-candidate-selection-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestReadOnlyCandidateSelectionContract();
        }
        if (string.Equals(args[0], "multi-file-read-report-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestMultiFileReadReportContract();
        }
        if (string.Equals(args[0], "patch-test-retry-decision-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchTestRetryDecisionContract();
        }
        if (string.Equals(args[0], "revised-patch-proposal-plan-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestRevisedPatchProposalPlanContract();
        }
        if (string.Equals(args[0], "local-model-revised-patch-request-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestLocalModelRevisedPatchRequestContract();
        }
        if (string.Equals(args[0], "local-model-revised-patch-output-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestLocalModelRevisedPatchOutputContract();
        }
        if (string.Equals(args[0], "validated-revised-patch-dry-run-handoff-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestValidatedRevisedPatchDryRunHandoffContract();
        }
        if (string.Equals(args[0], "patch-test-second-attempt-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestPatchTestSecondAttemptContract();
        }
        if (string.Equals(args[0], "revised-patch-approval-request-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestRevisedPatchApprovalRequestContract();
        }
        if (string.Equals(args[0], "revised-patch-approval-gate-contract", StringComparison.OrdinalIgnoreCase))
        {
            return SelfTestRevisedPatchApprovalGateContract();
        }
        if (!string.Equals(args[0], "snapshot-guards", StringComparison.OrdinalIgnoreCase))
        {
            return Unknown("self-test " + string.Join(' ', args));
        }
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-self-test");
        var ok = TryBuildSnapshotLayout(root, "snap-0123456789abcdef", ["src/App.cs", "README.md"], out var layout, out var error)
            && layout is not null
            && layout.Files.Count == 2
            && layout.RelativeManifestPath == "snap-0123456789abcdef/manifest.json"
            && layout.Files[0].SnapshotRelativePath == "files/src/App.cs"
            && IsWithin(Path.Combine(root, "snapshots"), layout.ManifestPath);
        ok = ok
            && !TryBuildSnapshotLayout(root, "../escape", ["README.md"], out _, out _)
            && !TryBuildSnapshotLayout(root, "snap-0123456789abcdef", ["../secret.txt"], out _, out _)
            && !TryBuildSnapshotLayout(root, "snap-0123456789abcdef", ["/tmp/secret.txt"], out _, out _)
            && !TryBuildSnapshotLayout(root, "snap-0123456789abcdef", ["C:/secret.txt"], out _, out _);
        if (!ok)
        {
            Console.Error.WriteLine(error ?? "snapshot guard self-test failed");
            return 1;
        }
        Console.WriteLine("snapshot-guards-ok");
        return 0;
    }

    private static int SelfTestCliStatusDoctorContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-cli-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var config = new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var app = new LearnBotLocalAgent();
            app.SaveConfig(config);

            var status = app.BuildCliStatusReport();
            var doctor = app.BuildCliDoctorReport();
            var statusJson = JsonSerializer.Serialize(status, JsonOptions);
            var doctorJson = JsonSerializer.Serialize(doctor, JsonOptions);
            var ok = status.CommandName == "learnbot"
                && status.Configured
                && status.Transport == "auto"
                && status.WorkspaceCount == 1
                && status.ApprovedWorkspaceCount == 1
                && status.ConfigExists
                && !statusJson.Contains("secret-token", StringComparison.Ordinal)
                && doctor.CommandName == "learnbot"
                && doctor.Ready
                && doctor.Checks.Any(check => check.Name == "tokenSecretHidden" && check.Ok)
                && doctor.Checks.Any(check => check.Name == "safeToolBoundary" && check.Ok)
                && !doctorJson.Contains("secret-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("cli status/doctor contract self-test failed");
                return 1;
            }
            Console.WriteLine("cli-status-doctor-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestM8ProductizationStatusContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-m8-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var config = new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(Guid.Parse("11111111-1111-1111-1111-111111111111"), "workspace", workspaceRoot, true)]
            };
            var app = new LearnBotLocalAgent();
            app.SaveConfig(config);

            var report = app.BuildCliM8ProductizationReport();
            var unpaired = LearnBotLocalAgent.BuildM8NextCommands(new CliStatusReport(
                "learnbot",
                Version,
                false,
                "http://localhost:8083",
                Guid.Empty,
                "polling",
                0,
                0,
                Path.Combine(agentRoot, "missing.json"),
                false,
                Path.Combine(agentRoot, "agent.log"),
                false,
                Path.Combine(agentRoot, "agent-state.json"),
                false,
                Environment.ProcessPath,
                null));
            var pairedStopped = LearnBotLocalAgent.BuildM8NextCommands(new CliStatusReport(
                "learnbot",
                Version,
                true,
                "http://localhost:8083",
                config.AgentId,
                "auto",
                1,
                1,
                Path.Combine(agentRoot, "agent.json"),
                true,
                Path.Combine(agentRoot, "agent.log"),
                false,
                Path.Combine(agentRoot, "agent-state.json"),
                false,
                Environment.ProcessPath,
                null));
            var pairedRunning = LearnBotLocalAgent.BuildM8NextCommands(new CliStatusReport(
                "learnbot",
                Version,
                true,
                "http://localhost:8083",
                config.AgentId,
                "auto",
                1,
                1,
                Path.Combine(agentRoot, "agent.json"),
                true,
                Path.Combine(agentRoot, "agent.log"),
                true,
                Path.Combine(agentRoot, "agent-state.json"),
                true,
                Environment.ProcessPath,
                null));
            var json = JsonSerializer.Serialize(report, JsonOptions);
            var itemNames = report.Items.Select(item => item.Name).ToHashSet(StringComparer.Ordinal);
            var ok = report.Schema == "learnbot.local-agent.m8-productization-status.v1"
                && report.CommandName == "learnbot"
                && report.ReadyForInternalPilot
                && !report.ReadyForMatureDistribution
                && !report.M8WorkEnabled
                && report.ServiceCommandExecutionEnabled
                && !report.InstallerSigningEnabled
                && !report.AutoUpdateEnabled
                && report.DoctorReady
                && itemNames.Contains("guidedSetup")
                && itemNames.Contains("backgroundLifecycle")
                && itemNames.Contains("windowsServicePreview")
                && itemNames.Contains("codexLikeCommands")
                && itemNames.Contains("signedInstaller")
                && itemNames.Contains("autoUpdate")
                && report.NextCommands.Any(item => item.Phase == "start" && item.Command.Contains("m8-lifecycle-run", StringComparison.Ordinal))
                && unpaired.Any(item => item.Phase == "pair" && item.Command.Contains("<pairing-token>", StringComparison.Ordinal) && !item.Enabled)
                && pairedStopped.Any(item => item.Phase == "start" && item.Command.Contains("m8-lifecycle-run", StringComparison.Ordinal) && item.Enabled)
                && pairedRunning.Any(item => item.Phase == "servicePreview" && item.Command.Contains("service-plan", StringComparison.Ordinal) && item.Enabled)
                && !json.Contains("secret-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("m8 productization status contract self-test failed");
                return 1;
            }
            Console.WriteLine("m8-productization-status-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestM8DoctorContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-m8-doctor-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var config = new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(Guid.Parse("11111111-1111-1111-1111-111111111111"), "workspace", workspaceRoot, true)]
            };
            var app = new LearnBotLocalAgent();
            app.SaveConfig(config);

            var report = app.BuildCliM8DoctorReport();
            var json = JsonSerializer.Serialize(report, JsonOptions);
            var sectionNames = report.Sections.Select(section => section.Name).ToHashSet(StringComparer.Ordinal);
            var ok = report.Schema == "learnbot.local-agent.m8-doctor.v1"
                && report.CommandName == "learnbot"
                && report.ReadyForInternalPilot
                && !report.ReadyForMatureDistribution
                && !report.M8WorkEnabled
                && report.ServiceCommandExecutionEnabled
                && !report.InstallerSigningEnabled
                && !report.AutoUpdateEnabled
                && !report.TokenSecretPrinted
                && report.ProductizationStatus.Schema == "learnbot.local-agent.m8-productization-status.v1"
                && sectionNames.Contains("setup")
                && sectionNames.Contains("lifecycle")
                && sectionNames.Contains("runtime")
                && sectionNames.Contains("logs")
                && sectionNames.Contains("servicePreview")
                && sectionNames.Contains("distribution")
                && report.NextCommands.Any(item => item.Phase == "start" && item.Command.Contains("m8-lifecycle-run", StringComparison.Ordinal))
                && !json.Contains("secret-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("m8 doctor contract self-test failed");
                return 1;
            }
            Console.WriteLine("m8-doctor-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestCodexCommandPreviewContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-codex-preview-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var config = new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var app = new LearnBotLocalAgent();
            app.SaveConfig(config);

            var repositoryId = Guid.Parse("33333333-3333-3333-3333-333333333333");
            var spaceId = Guid.Parse("44444444-4444-4444-4444-444444444444");
            var fix = app.BuildCliCodexCommandPreviewReport("fix", "repair failing tests", workspaceRoot, repositoryId.ToString(), spaceId.ToString(), 8);
            var review = app.BuildCliCodexCommandPreviewReport("review", "review auth changes", workspaceRoot);
            var missingGoal = app.BuildCliCodexCommandPreviewReport("fix", "", workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "unpaired.json"));
            var unpaired = new LearnBotLocalAgent().BuildCliCodexCommandPreviewReport("review", "review changes", workspaceRoot);

            var json = JsonSerializer.Serialize(new { fix, review, missingGoal, unpaired }, JsonOptions);
            var ok = fix.Schema == "learnbot.local-agent.codex-command-preview.v1"
                && fix.Command == "fix"
                && fix.ReadyForPreview
                && fix.Status == "READY_PREVIEW"
                && fix.WorkspaceMatched
                && fix.WorkspaceId == workspaceId
                && !fix.SubmitEnabled
                && fix.ReadOnlyPreview
                && !fix.RequestCreationEnabled
                && !fix.MutationAllowed
                && !fix.ApprovalBypassAllowed
                && !fix.TestExecutionEnabled
                && !fix.RollbackExecutionEnabled
                && !fix.FinalPublicationEnabled
                && !fix.PartialReindexEnabled
                && !fix.TokenSecretPrinted
                && fix.OneCyclePreview.Schema == "learnbot.local-agent.codex-one-cycle-preview.v1"
                && fix.OneCyclePreview.Status == "READY_READ_ONLY_LOOP_PREVIEW"
                && fix.OneCyclePreview.ReadyForReadOnlyToolLoop
                && !fix.OneCyclePreview.ReadyForPatchTestLoop
                && !fix.OneCyclePreview.ReadyForFinalReport
                && !fix.OneCyclePreview.ReadyForPartialReindex
                && fix.OneCyclePreview.LocalAgentExecutionTarget == "USER_LOCAL_AGENT"
                && fix.OneCyclePreview.ServerPlanningRequired
                && !fix.OneCyclePreview.RequestCreationEnabled
                && !fix.OneCyclePreview.MutationAllowed
                && fix.OneCyclePreview.ApprovalRequiredBeforeMutation
                && !fix.OneCyclePreview.ApprovalBypassAllowed
                && !fix.OneCyclePreview.TestExecutionEnabled
                && !fix.OneCyclePreview.RollbackExecutionEnabled
                && !fix.OneCyclePreview.FinalReportPublicationEnabled
                && !fix.OneCyclePreview.PartialReindexEnabled
                && !fix.OneCyclePreview.TokenSecretPrinted
                && fix.OneCyclePreview.FileDiscoveryReadPlan.Schema == "learnbot.local-agent.codex-file-discovery-read-plan.v1"
                && fix.OneCyclePreview.FileDiscoveryReadPlan.Status == "READY_DRY_RUN_PLAN"
                && fix.OneCyclePreview.FileDiscoveryReadPlan.DryRunOnly
                && fix.OneCyclePreview.FileDiscoveryReadPlan.PlanPrepared
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.ToolExecutionEnabled
                && fix.OneCyclePreview.FileDiscoveryReadPlan.FileDiscoveryPlanEnabled
                && fix.OneCyclePreview.FileDiscoveryReadPlan.FileReadPlanEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.FileTreeExecutionEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.FileSearchExecutionEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.FileReadExecutionEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.GitStatusExecutionEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.FileContentRead
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.FileBytesLoaded
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.RequestCreationEnabled
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.MutationAllowed
                && !fix.OneCyclePreview.FileDiscoveryReadPlan.TokenSecretPrinted
                && fix.OneCyclePreview.FileDiscoveryReadPlan.CandidateTools.Contains("file.tree")
                && fix.OneCyclePreview.FileDiscoveryReadPlan.CandidateTools.Contains("file.search")
                && fix.OneCyclePreview.FileDiscoveryReadPlan.CandidateTools.Contains("file.read")
                && fix.OneCyclePreview.FileDiscoveryReadPlan.CandidateTools.Contains("git.status")
                && fix.OneCyclePreview.FileDiscoveryReadPlan.QueryHints.Contains("repair")
                && fix.OneCyclePreview.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "workspace.tree"
                    && envelope.Schema == "learnbot.local-agent.codex-read-only-request-envelope-preview.v1"
                    && envelope.ExecutionTarget == "USER_LOCAL_AGENT"
                    && envelope.ApprovalState == "NOT_REQUIRED"
                    && !envelope.RequestCreationEnabled
                    && !envelope.EnqueueEnabled
                    && !envelope.Claimable
                    && !envelope.ExecutionEnabled
                    && !envelope.SideEffectful
                    && !envelope.RequiresApproval
                    && !envelope.MutationAllowed
                    && !envelope.FileContentRead
                    && !envelope.TokenSecretPrinted
                    && envelope.InputPreview.TryGetValue("workspaceId", out var treeWorkspaceId)
                    && treeWorkspaceId is Guid treeWorkspaceGuid
                    && treeWorkspaceGuid == workspaceId)
                && fix.OneCyclePreview.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "workspace.search"
                    && !envelope.RequestCreationEnabled
                    && !envelope.ExecutionEnabled
                    && !envelope.MutationAllowed
                    && envelope.InputPreview.TryGetValue("query", out var queryPreview)
                    && queryPreview is string queryPreviewText
                    && queryPreviewText.Contains("repair", StringComparison.Ordinal))
                && fix.OneCyclePreview.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "git.status"
                    && !envelope.RequestCreationEnabled
                    && !envelope.ExecutionEnabled
                    && !envelope.MutationAllowed)
                && fix.OneCyclePreview.FileDiscoveryReadPlan.PlannedSteps.Any(step => step.Name == "search" && step.ToolName == "file.search" && !step.ExecutionEnabled && !step.MutationAllowed)
                && fix.OneCyclePreview.FileDiscoveryReadPlan.PlannedSteps.Any(step => step.Name == "read" && step.ToolName == "file.read" && !step.ExecutionEnabled && !step.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "file-discovery" && stage.ReadOnly && stage.Ready && !stage.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "file-read" && stage.ReadOnly && stage.Ready && !stage.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "patch-dry-run" && !stage.Ready && stage.RequiresApproval && !stage.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "apply-and-test" && !stage.Ready && stage.RequiresApproval && !stage.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "final-report" && !stage.Ready && !stage.MutationAllowed)
                && fix.OneCyclePreview.Stages.Any(stage => stage.Name == "rag-freshness-update" && !stage.Ready && !stage.MutationAllowed)
                && fix.OneCyclePreview.Blockers.Contains("authenticated server handoff is required before request creation")
                && fix.ServerSubmissionPlan.Schema == "learnbot.local-agent.codex-server-submission-plan.v1"
                && fix.ServerSubmissionPlan.Endpoint == "/api/code-agent/loop/runs"
                && fix.ServerSubmissionPlan.RepositoryId == repositoryId
                && fix.ServerSubmissionPlan.SpaceId == spaceId
                && fix.ServerSubmissionPlan.AgentId == config.AgentId
                && fix.ServerSubmissionPlan.WorkspaceId == workspaceId
                && fix.ServerSubmissionPlan.ReadyForDisabledPlan
                && fix.ServerSubmissionPlan.Enabled
                && fix.ServerSubmissionPlan.NetworkCallEnabled
                && fix.ServerSubmissionPlan.RequestCreationEnabled
                && !fix.ServerSubmissionPlan.ServerConversationCreationEnabled
                && fix.ServerSubmissionPlan.LoopPreviewExecutionEnabled
                && fix.ServerSubmissionPlan.BodyPreview.TryGetValue("repositoryId", out var bodyRepositoryId)
                && bodyRepositoryId is Guid bodyRepositoryGuid
                && bodyRepositoryGuid == repositoryId
                && fix.ServerSubmissionPlan.FollowUpEndpoints.Contains("POST /api/code-agent/loop/runner/preview")
                && review.Command == "review"
                && review.ReadyForPreview
                && review.OneCyclePreview.Schema == "learnbot.local-agent.codex-one-cycle-preview.v1"
                && !review.OneCyclePreview.ReadyForReadOnlyToolLoop
                && review.OneCyclePreview.FileDiscoveryReadPlan.Schema == "learnbot.local-agent.codex-file-discovery-read-plan.v1"
                && !review.OneCyclePreview.FileDiscoveryReadPlan.PlanPrepared
                && !review.OneCyclePreview.FileDiscoveryReadPlan.FileContentRead
                && review.OneCyclePreview.Blockers.Contains("repository id is required for server handoff preview")
                && !review.ServerSubmissionPlan.ReadyForDisabledPlan
                && review.ServerSubmissionPlan.Blockers.Contains("repository id is required for server handoff preview")
                && missingGoal.Status == "BLOCKED_PREVIEW"
                && missingGoal.Blockers.Contains("goal is required")
                && missingGoal.OneCyclePreview.Blockers.Contains("goal is required")
                && missingGoal.ServerSubmissionPlan.Blockers.Contains("goal is required")
                && unpaired.Status == "BLOCKED_PREVIEW"
                && unpaired.Blockers.Contains("agent is not paired")
                && unpaired.OneCyclePreview.Blockers.Contains("agent is not paired")
                && unpaired.ServerSubmissionPlan.Blockers.Contains("agent is not paired")
                && !json.Contains("secret-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("codex command preview contract self-test failed");
                return 1;
            }
            Console.WriteLine("codex-command-preview-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestCodexReadOnlyObservationContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-codex-observe-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src"));
            File.WriteAllText(Path.Combine(workspaceRoot, "src", "Calculator.cs"), "class Calculator { string Goal = \"repair failing tests hidden-content\"; }\n");
            File.WriteAllText(Path.Combine(workspaceRoot, "README.md"), "fixture\n");
            if (!RunGitForSelfTest(workspaceRoot, "init"))
            {
                Console.Error.WriteLine("codex read-only observation contract self-test could not initialize git");
                return 1;
            }

            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            });

            var repositoryId = Guid.Parse("33333333-3333-3333-3333-333333333333");
            var preview = app.BuildCliCodexCommandPreviewReport("fix", "repair failing tests", workspaceRoot, repositoryId.ToString(), null, 6);
            var observed = app.BuildCliCodexReadOnlyObservationReport(preview);
            var readObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true);
            var inlineSourceObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "inline", diffTextProvided: true);
            var localModelSourceObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "local-model");
            var unsupportedSourceObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "network-share");
            var generatedDiff = """
--- a/src/Calculator.cs
+++ b/src/Calculator.cs
@@ -1 +1 @@
-class Calculator { string Goal = "repair failing tests hidden-content"; }
+class Calculator { string Goal = "repair passing tests hidden-content"; }
""";
            var acceptedGeneratedDiffObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "local-model", acceptGeneratedDiffPreview: true, generatedDiffPreview: generatedDiff);
            var acceptedGeneratedDiffPreflightObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "local-model", acceptGeneratedDiffPreview: true, generatedDiffPreview: generatedDiff, runNonWritingPreflightPreview: true);
            var generatedDiffWithoutSwitchObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "local-model", generatedDiffPreview: generatedDiff);
            var generatedDiffWrongTargetObserved = app.BuildCliCodexReadOnlyObservationReport(preview, readSelectedFiles: true, diffSource: "server-planner", acceptGeneratedDiffPreview: true, generatedDiffPreview: """
--- a/src/Other.cs
+++ b/src/Other.cs
@@ -1 +1 @@
-old
+new
""");
            var validDiffPreview = BuildCliCodexDiffSourceValidationPreview(readObserved.PatchProposalPreview, generatedDiff);
            var validPreflightPreview = app.BuildCliCodexPatchDryRunPreflightPreview(app.LoadConfigOrDefault(), workspaceId, validDiffPreview, """
--- a/src/Calculator.cs
+++ b/src/Calculator.cs
@@ -1 +1 @@
-class Calculator { string Goal = "repair failing tests hidden-content"; }
+class Calculator { string Goal = "repair passing tests hidden-content"; }
""");
            var mismatchDiffPreview = BuildCliCodexDiffSourceValidationPreview(readObserved.PatchProposalPreview, """
--- a/src/Calculator.cs
+++ b/src/Calculator.cs
@@ -1 +1 @@
-class Calculator { string Goal = "does not match"; }
+class Calculator { string Goal = "repair passing tests hidden-content"; }
""");
            var mismatchPreflightPreview = app.BuildCliCodexPatchDryRunPreflightPreview(app.LoadConfigOrDefault(), workspaceId, mismatchDiffPreview, """
--- a/src/Calculator.cs
+++ b/src/Calculator.cs
@@ -1 +1 @@
-class Calculator { string Goal = "does not match"; }
+class Calculator { string Goal = "repair passing tests hidden-content"; }
""");
            var rejectedDiffPreview = BuildCliCodexDiffSourceValidationPreview(readObserved.PatchProposalPreview, """
--- a/src/Other.cs
+++ b/src/Other.cs
@@ -1 +1 @@
-old
+new
""");

            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "unpaired.json"));
            var blockedApp = new LearnBotLocalAgent();
            var blockedPreview = blockedApp.BuildCliCodexCommandPreviewReport("fix", "repair failing tests", workspaceRoot, repositoryId.ToString(), null, 6);
            var blocked = blockedApp.BuildCliCodexReadOnlyObservationReport(blockedPreview);

            var json = JsonSerializer.Serialize(new { observed, blocked }, JsonOptions);
            var readJson = JsonSerializer.Serialize(readObserved, JsonOptions);
            var search = observed.Observations.SingleOrDefault(item => item.ToolName == "workspace.search");
            var ok = observed.Schema == "learnbot.local-agent.codex-read-only-observation.v1"
                && observed.Status == "OBSERVED_READ_ONLY_CONTEXT"
                && observed.ReadyForExecution
                && observed.ExecutionAttempted
                && observed.ToolExecutionEnabled
                && !observed.RequestCreationEnabled
                && !observed.FileContentRead
                && observed.SearchSnippetsRedacted
                && !observed.MutationAllowed
                && !observed.TokenSecretPrinted
                && observed.FileDiscoveryReadPlan.Schema == "learnbot.local-agent.codex-file-discovery-read-plan.v1"
                && observed.Observations.Count == 3
                && observed.Observations.All(item => item.Status == "SUCCEEDED" && item.ReadOnly && !item.FileContentRead && !item.MutationAllowed)
                && observed.Observations.Any(item => item.ToolName == "workspace.tree")
                && observed.Observations.Any(item => item.ToolName == "git.status")
                && observed.CandidateSelection.Schema == "learnbot.local-agent.codex-read-only-candidate-selection.v1"
                && observed.CandidateSelection.Status == "READY_FILE_READ_PLAN"
                && observed.CandidateSelection.SelectedFileCount == 1
                && observed.CandidateSelection.SelectedFiles.Any(file =>
                    file.Path == "src/Calculator.cs"
                    && file.Rank == 1
                    && file.Source == "workspace.search"
                    && file.NextTool == "file.read")
                && observed.CandidateSelection.FileReadPlanPrepared
                && !observed.CandidateSelection.FileReadExecutionEnabled
                && !observed.CandidateSelection.FileContentRead
                && !observed.CandidateSelection.RequestCreationEnabled
                && !observed.CandidateSelection.MutationAllowed
                && !observed.CandidateSelection.ModelRankingEnabled
                && observed.SelectedFileRead.Schema == "learnbot.local-agent.codex-selected-file-read.v1"
                && observed.SelectedFileRead.Status == "NOT_REQUESTED"
                && !observed.SelectedFileRead.ExecutionAttempted
                && !observed.SelectedFileRead.FileContentRead
                && observed.PatchIntentPreview.Schema == "learnbot.local-agent.codex-patch-intent-preview.v1"
                && observed.PatchIntentPreview.Status == "READ_REQUIRED"
                && !observed.PatchIntentPreview.PlanningInputPrepared
                && observed.PatchIntentPreview.DryRunOnly
                && !observed.PatchIntentPreview.DiffGenerated
                && !observed.PatchIntentPreview.PatchDryRunExecutionEnabled
                && !observed.PatchIntentPreview.RequestCreationEnabled
                && observed.PatchIntentPreview.ApprovalRequiredBeforeMutation
                && !observed.PatchIntentPreview.MutationAllowed
                && observed.PatchProposalPreview.Schema == "learnbot.local-agent.codex-patch-proposal-preview.v1"
                && observed.PatchProposalPreview.Status == "READ_REQUIRED"
                && !observed.PatchProposalPreview.ProposalPrepared
                && observed.PatchProposalPreview.DiffSource == "NONE_PLACEHOLDER_REQUIRED"
                && !observed.PatchProposalPreview.DiffGenerated
                && observed.PatchProposalPreview.DiffPreview is null
                && observed.PatchProposalPreview.UnifiedDiffRequired
                && observed.PatchProposalPreview.DryRunOnly
                && !observed.PatchProposalPreview.PatchApplyInputPrepared
                && !observed.PatchProposalPreview.PatchDryRunExecutionEnabled
                && !observed.PatchProposalPreview.RequestCreationEnabled
                && observed.PatchProposalPreview.ApprovalRequiredBeforeMutation
                && !observed.PatchProposalPreview.MutationAllowed
                && observed.DiffSourceInputPreview.Schema == "learnbot.local-agent.codex-diff-source-input-preview.v1"
                && observed.DiffSourceInputPreview.Status == "READ_REQUIRED"
                && !observed.DiffSourceInputPreview.SourceRequested
                && !observed.DiffSourceInputPreview.SourceEnabled
                && !observed.DiffSourceInputPreview.DiffBodyLoaded
                && !observed.DiffSourceInputPreview.DiffForwardedToValidation
                && !observed.DiffSourceInputPreview.RequestCreationEnabled
                && !observed.DiffSourceInputPreview.MutationAllowed
                && observed.PlannerDiffOutputPreview.Schema == "learnbot.local-agent.codex-planner-diff-output-preview.v1"
                && observed.PlannerDiffOutputPreview.Status == "READ_REQUIRED"
                && !observed.PlannerDiffOutputPreview.PlannerSourceRequested
                && !observed.PlannerDiffOutputPreview.ReadContextReady
                && !observed.PlannerDiffOutputPreview.OutputEnvelopePrepared
                && !observed.PlannerDiffOutputPreview.DiffGenerated
                && !observed.PlannerDiffOutputPreview.DiffBodyIncluded
                && !observed.PlannerDiffOutputPreview.DiffForwardedToValidation
                && !observed.PlannerDiffOutputPreview.RequestCreationEnabled
                && !observed.PlannerDiffOutputPreview.MutationAllowed
                && observed.GeneratedDiffAcceptancePreview.Schema == "learnbot.local-agent.codex-generated-diff-acceptance-preview.v1"
                && observed.GeneratedDiffAcceptancePreview.Status == "READ_REQUIRED"
                && !observed.GeneratedDiffAcceptancePreview.PlannerOutputEnvelopePrepared
                && !observed.GeneratedDiffAcceptancePreview.ExplicitPreviewSwitchEnabled
                && !observed.GeneratedDiffAcceptancePreview.GeneratedDiffProvided
                && !observed.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !observed.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && !observed.GeneratedDiffAcceptancePreview.DiffFileReadEnabled
                && !observed.GeneratedDiffAcceptancePreview.RequestCreationEnabled
                && !observed.GeneratedDiffAcceptancePreview.MutationAllowed
                && observed.PlannerDiffValidationHandoffPreview.Schema == "learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1"
                && observed.PlannerDiffValidationHandoffPreview.Status == "READ_REQUIRED"
                && !observed.PlannerDiffValidationHandoffPreview.PlannerOutputEnvelopePrepared
                && !observed.PlannerDiffValidationHandoffPreview.DiffBodyAvailable
                && !observed.PlannerDiffValidationHandoffPreview.ValidationInputPrepared
                && !observed.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && !observed.PlannerDiffValidationHandoffPreview.ValidationAttempted
                && !observed.PlannerDiffValidationHandoffPreview.PatchApplyInputPrepared
                && !observed.PlannerDiffValidationHandoffPreview.RequestCreationEnabled
                && !observed.PlannerDiffValidationHandoffPreview.MutationAllowed
                && observed.DiffSourceValidationPreview.Schema == "learnbot.local-agent.codex-diff-source-validation-preview.v1"
                && observed.DiffSourceValidationPreview.Status == "READ_REQUIRED"
                && !observed.DiffSourceValidationPreview.DiffProvided
                && !observed.DiffSourceValidationPreview.DiffParsed
                && !observed.DiffSourceValidationPreview.PatchApplyInputPrepared
                && !observed.DiffSourceValidationPreview.PatchDryRunExecutionEnabled
                && !observed.DiffSourceValidationPreview.RequestCreationEnabled
                && observed.DiffSourceValidationPreview.ApprovalRequiredBeforeMutation
                && !observed.DiffSourceValidationPreview.MutationAllowed
                && observed.PatchDryRunRequestEnvelopePreview.Schema == "learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1"
                && observed.PatchDryRunRequestEnvelopePreview.Status == "READ_REQUIRED"
                && observed.PatchDryRunRequestEnvelopePreview.ToolName == "patch.apply"
                && observed.PatchDryRunRequestEnvelopePreview.ExecutionTarget == "USER_LOCAL_AGENT"
                && observed.PatchDryRunRequestEnvelopePreview.ApprovalState == "NOT_PREPARED"
                && !observed.PatchDryRunRequestEnvelopePreview.DiffValidationPassed
                && !observed.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && !observed.PatchDryRunRequestEnvelopePreview.SnapshotCreationEnabled
                && !observed.PatchDryRunRequestEnvelopePreview.PatchDryRunExecutionEnabled
                && !observed.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !observed.PatchDryRunRequestEnvelopePreview.EnqueueEnabled
                && !observed.PatchDryRunRequestEnvelopePreview.Claimable
                && observed.PatchDryRunRequestEnvelopePreview.ApprovalRequiredBeforeDryRun
                && observed.PatchDryRunRequestEnvelopePreview.ApprovalRequiredBeforeMutation
                && !observed.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && observed.PatchDryRunPreflightPreview.Schema == "learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1"
                && observed.PatchDryRunPreflightPreview.Status == "DIFF_VALIDATION_REQUIRED"
                && !observed.PatchDryRunPreflightPreview.Requested
                && !observed.PatchDryRunPreflightPreview.ExecutionAttempted
                && observed.PatchDryRunPreflightPreview.NonWritingPreflightOnly
                && !observed.PatchDryRunPreflightPreview.PreflightPassed
                && !observed.PatchDryRunPreflightPreview.SnapshotCreated
                && !observed.PatchDryRunPreflightPreview.MutationApplied
                && !observed.PatchDryRunPreflightPreview.PatchDryRunExecutionEnabled
                && !observed.PatchDryRunPreflightPreview.RequestCreationEnabled
                && !observed.PatchDryRunPreflightPreview.MutationAllowed
                && observed.PatchDryRunApprovalHandoffPreview.Schema == "learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1"
                && observed.PatchDryRunApprovalHandoffPreview.Status == "READ_REQUIRED"
                && observed.PatchDryRunApprovalHandoffPreview.ToolName == "patch.apply"
                && observed.PatchDryRunApprovalHandoffPreview.ExecutionTarget == "USER_LOCAL_AGENT"
                && observed.PatchDryRunApprovalHandoffPreview.ApprovalKind == "SNAPSHOT_WRITING_DRY_RUN"
                && observed.PatchDryRunApprovalHandoffPreview.ApprovalState == "NOT_PREPARED"
                && !observed.PatchDryRunApprovalHandoffPreview.RequestEnvelopePrepared
                && !observed.PatchDryRunApprovalHandoffPreview.NonWritingPreflightPassed
                && !observed.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && observed.PatchDryRunApprovalHandoffPreview.DryRunApprovalRequired
                && observed.PatchDryRunApprovalHandoffPreview.MutationApprovalRequired
                && !observed.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !observed.PatchDryRunApprovalHandoffPreview.ApprovalRequestCreationEnabled
                && !observed.PatchDryRunApprovalHandoffPreview.EnqueueEnabled
                && !observed.PatchDryRunApprovalHandoffPreview.Claimable
                && !observed.PatchDryRunApprovalHandoffPreview.SnapshotCreationEnabled
                && !observed.PatchDryRunApprovalHandoffPreview.PatchDryRunExecutionEnabled
                && !observed.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && readObserved.Status == "OBSERVED_READ_ONLY_CONTEXT"
                && readObserved.FileContentRead
                && readObserved.SelectedFileRead.Schema == "learnbot.local-agent.codex-selected-file-read.v1"
                && readObserved.SelectedFileRead.Status == "SUCCEEDED"
                && readObserved.SelectedFileRead.Requested
                && readObserved.SelectedFileRead.ExecutionAttempted
                && readObserved.SelectedFileRead.FileReadExecutionEnabled
                && readObserved.SelectedFileRead.FileContentRead
                && !readObserved.SelectedFileRead.RequestCreationEnabled
                && !readObserved.SelectedFileRead.MutationAllowed
                && readObserved.SelectedFileRead.ReadFileCount == 1
                && readObserved.SelectedFileRead.Files.Any(file =>
                    file.Path == "src/Calculator.cs"
                    && file.Status == "SUCCEEDED"
                    && file.Content is not null
                    && file.Content.Contains("hidden-content", StringComparison.Ordinal))
                && readObserved.PatchIntentPreview.Schema == "learnbot.local-agent.codex-patch-intent-preview.v1"
                && readObserved.PatchIntentPreview.Status == "READY_PATCH_INTENT_PREVIEW"
                && readObserved.PatchIntentPreview.PlanningInputPrepared
                && readObserved.PatchIntentPreview.TargetFiles.Contains("src/Calculator.cs")
                && readObserved.PatchIntentPreview.ReadFileCount == 1
                && readObserved.PatchIntentPreview.NextTool == "patch.apply"
                && readObserved.PatchIntentPreview.DryRunOnly
                && !readObserved.PatchIntentPreview.DiffGenerated
                && !readObserved.PatchIntentPreview.PatchDryRunExecutionEnabled
                && !readObserved.PatchIntentPreview.RequestCreationEnabled
                && readObserved.PatchIntentPreview.ApprovalRequiredBeforeMutation
                && !readObserved.PatchIntentPreview.MutationAllowed
                && !readObserved.PatchIntentPreview.TestExecutionEnabled
                && !readObserved.PatchIntentPreview.FinalReportPublicationEnabled
                && !readObserved.PatchIntentPreview.PartialReindexEnabled
                && !readObserved.PatchIntentPreview.LocalModelPlanningEnabled
                && readObserved.PatchProposalPreview.Schema == "learnbot.local-agent.codex-patch-proposal-preview.v1"
                && readObserved.PatchProposalPreview.Status == "AWAITING_DIFF_SOURCE"
                && readObserved.PatchProposalPreview.ProposalPrepared
                && readObserved.PatchProposalPreview.TargetFiles.Contains("src/Calculator.cs")
                && readObserved.PatchProposalPreview.DiffSource == "NONE_PLACEHOLDER_REQUIRED"
                && !readObserved.PatchProposalPreview.DiffGenerated
                && readObserved.PatchProposalPreview.DiffPreview is null
                && readObserved.PatchProposalPreview.UnifiedDiffRequired
                && readObserved.PatchProposalPreview.DryRunOnly
                && !readObserved.PatchProposalPreview.PatchApplyInputPrepared
                && !readObserved.PatchProposalPreview.PatchDryRunExecutionEnabled
                && !readObserved.PatchProposalPreview.RequestCreationEnabled
                && readObserved.PatchProposalPreview.ApprovalRequiredBeforeMutation
                && !readObserved.PatchProposalPreview.MutationAllowed
                && !readObserved.PatchProposalPreview.TestExecutionEnabled
                && !readObserved.PatchProposalPreview.LocalModelPlanningEnabled
                && !readObserved.PatchProposalPreview.ServerPlannerEnabled
                && readObserved.DiffSourceInputPreview.Schema == "learnbot.local-agent.codex-diff-source-input-preview.v1"
                && readObserved.DiffSourceInputPreview.Status == "DIFF_SOURCE_NOT_PROVIDED"
                && readObserved.DiffSourceInputPreview.RequestedSource == "none"
                && !readObserved.DiffSourceInputPreview.SourceRequested
                && readObserved.DiffSourceInputPreview.SourceRecognized
                && !readObserved.DiffSourceInputPreview.SourceEnabled
                && !readObserved.DiffSourceInputPreview.DiffBodyLoaded
                && !readObserved.DiffSourceInputPreview.DiffForwardedToValidation
                && !readObserved.DiffSourceInputPreview.LocalModelPlanningEnabled
                && !readObserved.DiffSourceInputPreview.ServerPlannerEnabled
                && !readObserved.DiffSourceInputPreview.RequestCreationEnabled
                && !readObserved.DiffSourceInputPreview.MutationAllowed
                && readObserved.PlannerDiffOutputPreview.Schema == "learnbot.local-agent.codex-planner-diff-output-preview.v1"
                && readObserved.PlannerDiffOutputPreview.Status == "PLANNER_SOURCE_NOT_REQUESTED"
                && !readObserved.PlannerDiffOutputPreview.PlannerSourceRequested
                && readObserved.PlannerDiffOutputPreview.ReadContextReady
                && !readObserved.PlannerDiffOutputPreview.PlannerExecutionEnabled
                && !readObserved.PlannerDiffOutputPreview.OutputEnvelopePrepared
                && readObserved.PlannerDiffOutputPreview.UnifiedDiffRequired
                && !readObserved.PlannerDiffOutputPreview.DiffGenerated
                && !readObserved.PlannerDiffOutputPreview.DiffBodyIncluded
                && !readObserved.PlannerDiffOutputPreview.DiffForwardedToValidation
                && !readObserved.PlannerDiffOutputPreview.RequestCreationEnabled
                && !readObserved.PlannerDiffOutputPreview.MutationAllowed
                && readObserved.GeneratedDiffAcceptancePreview.Schema == "learnbot.local-agent.codex-generated-diff-acceptance-preview.v1"
                && readObserved.GeneratedDiffAcceptancePreview.Status == "PLANNER_SOURCE_REQUIRED"
                && !readObserved.GeneratedDiffAcceptancePreview.PlannerOutputEnvelopePrepared
                && !readObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !readObserved.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && !readObserved.GeneratedDiffAcceptancePreview.RequestCreationEnabled
                && !readObserved.GeneratedDiffAcceptancePreview.MutationAllowed
                && readObserved.PlannerDiffValidationHandoffPreview.Schema == "learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1"
                && readObserved.PlannerDiffValidationHandoffPreview.Status == "PLANNER_OUTPUT_REQUIRED"
                && !readObserved.PlannerDiffValidationHandoffPreview.PlannerOutputEnvelopePrepared
                && !readObserved.PlannerDiffValidationHandoffPreview.DiffBodyAvailable
                && !readObserved.PlannerDiffValidationHandoffPreview.ValidationInputPrepared
                && !readObserved.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && !readObserved.PlannerDiffValidationHandoffPreview.ValidationAttempted
                && !readObserved.PlannerDiffValidationHandoffPreview.MutationAllowed
                && inlineSourceObserved.DiffSourceInputPreview.Status == "DIFF_SOURCE_DISABLED_PREVIEW"
                && inlineSourceObserved.DiffSourceInputPreview.RequestedSource == "inline"
                && inlineSourceObserved.DiffSourceInputPreview.SourceRequested
                && inlineSourceObserved.DiffSourceInputPreview.SourceRecognized
                && !inlineSourceObserved.DiffSourceInputPreview.SourceEnabled
                && inlineSourceObserved.DiffSourceInputPreview.DiffTextProvided
                && !inlineSourceObserved.DiffSourceInputPreview.DiffTextAccepted
                && !inlineSourceObserved.DiffSourceInputPreview.DiffBodyLoaded
                && !inlineSourceObserved.DiffSourceInputPreview.DiffForwardedToValidation
                && !inlineSourceObserved.DiffSourceInputPreview.MutationAllowed
                && inlineSourceObserved.PlannerDiffOutputPreview.Status == "PLANNER_SOURCE_NOT_REQUESTED"
                && !inlineSourceObserved.PlannerDiffOutputPreview.PlannerSourceRequested
                && !inlineSourceObserved.PlannerDiffOutputPreview.OutputEnvelopePrepared
                && inlineSourceObserved.GeneratedDiffAcceptancePreview.Status == "PLANNER_SOURCE_REQUIRED"
                && !inlineSourceObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !inlineSourceObserved.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && inlineSourceObserved.PlannerDiffValidationHandoffPreview.Status == "PLANNER_OUTPUT_REQUIRED"
                && !inlineSourceObserved.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && !inlineSourceObserved.PlannerDiffValidationHandoffPreview.MutationAllowed
                && localModelSourceObserved.DiffSourceInputPreview.Status == "DIFF_SOURCE_DISABLED_PREVIEW"
                && localModelSourceObserved.DiffSourceInputPreview.RequestedSource == "local-model"
                && localModelSourceObserved.PlannerDiffOutputPreview.Status == "PLANNER_OUTPUT_DISABLED_PREVIEW"
                && localModelSourceObserved.PlannerDiffOutputPreview.PlannerSourceRequested
                && localModelSourceObserved.PlannerDiffOutputPreview.PlannerSourceRecognized
                && localModelSourceObserved.PlannerDiffOutputPreview.ReadContextReady
                && !localModelSourceObserved.PlannerDiffOutputPreview.PlannerExecutionEnabled
                && !localModelSourceObserved.PlannerDiffOutputPreview.LocalModelPlanningEnabled
                && !localModelSourceObserved.PlannerDiffOutputPreview.ServerPlannerEnabled
                && localModelSourceObserved.PlannerDiffOutputPreview.OutputEnvelopePrepared
                && localModelSourceObserved.PlannerDiffOutputPreview.UnifiedDiffRequired
                && !localModelSourceObserved.PlannerDiffOutputPreview.DiffGenerated
                && !localModelSourceObserved.PlannerDiffOutputPreview.DiffBodyIncluded
                && !localModelSourceObserved.PlannerDiffOutputPreview.DiffForwardedToValidation
                && !localModelSourceObserved.PlannerDiffOutputPreview.RequestCreationEnabled
                && !localModelSourceObserved.PlannerDiffOutputPreview.MutationAllowed
                && localModelSourceObserved.GeneratedDiffAcceptancePreview.Status == "GENERATED_DIFF_NOT_PROVIDED"
                && localModelSourceObserved.GeneratedDiffAcceptancePreview.PlannerOutputEnvelopePrepared
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.ExplicitPreviewSwitchEnabled
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.GeneratedDiffProvided
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.RequestCreationEnabled
                && !localModelSourceObserved.GeneratedDiffAcceptancePreview.MutationAllowed
                && localModelSourceObserved.PlannerDiffValidationHandoffPreview.Status == "HANDOFF_DISABLED_NO_DIFF"
                && localModelSourceObserved.PlannerDiffValidationHandoffPreview.PlannerOutputEnvelopePrepared
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.DiffBodyAvailable
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.ValidationInputPrepared
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.ValidationAttempted
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.DiffValidationPassed
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.PatchApplyInputPrepared
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.RequestCreationEnabled
                && !localModelSourceObserved.PlannerDiffValidationHandoffPreview.MutationAllowed
                && unsupportedSourceObserved.DiffSourceInputPreview.Status == "BLOCKED_UNSUPPORTED_DIFF_SOURCE"
                && unsupportedSourceObserved.DiffSourceInputPreview.SourceRequested
                && !unsupportedSourceObserved.DiffSourceInputPreview.SourceRecognized
                && !unsupportedSourceObserved.DiffSourceInputPreview.SourceEnabled
                && !unsupportedSourceObserved.DiffSourceInputPreview.DiffForwardedToValidation
                && !unsupportedSourceObserved.DiffSourceInputPreview.MutationAllowed
                && unsupportedSourceObserved.GeneratedDiffAcceptancePreview.Status == "PLANNER_SOURCE_REQUIRED"
                && !unsupportedSourceObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && readObserved.DiffSourceValidationPreview.Schema == "learnbot.local-agent.codex-diff-source-validation-preview.v1"
                && readObserved.DiffSourceValidationPreview.Status == "DIFF_SOURCE_REQUIRED"
                && readObserved.DiffSourceValidationPreview.TargetFiles.Contains("src/Calculator.cs")
                && !readObserved.DiffSourceValidationPreview.DiffProvided
                && !readObserved.DiffSourceValidationPreview.PatchApplyInputPrepared
                && !readObserved.DiffSourceValidationPreview.PatchDryRunExecutionEnabled
                && !readObserved.DiffSourceValidationPreview.RequestCreationEnabled
                && readObserved.DiffSourceValidationPreview.ApprovalRequiredBeforeMutation
                && !readObserved.DiffSourceValidationPreview.MutationAllowed
                && readObserved.PatchDryRunRequestEnvelopePreview.Schema == "learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1"
                && readObserved.PatchDryRunRequestEnvelopePreview.Status == "DIFF_VALIDATION_REQUIRED"
                && !readObserved.PatchDryRunRequestEnvelopePreview.DiffValidationPassed
                && !readObserved.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && !readObserved.PatchDryRunRequestEnvelopePreview.SnapshotCreationEnabled
                && !readObserved.PatchDryRunRequestEnvelopePreview.PatchDryRunExecutionEnabled
                && !readObserved.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !readObserved.PatchDryRunRequestEnvelopePreview.EnqueueEnabled
                && !readObserved.PatchDryRunRequestEnvelopePreview.Claimable
                && !readObserved.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && readObserved.PatchDryRunPreflightPreview.Status == "DIFF_VALIDATION_REQUIRED"
                && !readObserved.PatchDryRunPreflightPreview.ExecutionAttempted
                && readObserved.PatchDryRunPreflightPreview.NonWritingPreflightOnly
                && !readObserved.PatchDryRunPreflightPreview.SnapshotCreated
                && !readObserved.PatchDryRunPreflightPreview.MutationApplied
                && readObserved.PatchDryRunApprovalHandoffPreview.Status == "DRY_RUN_ENVELOPE_REQUIRED"
                && !readObserved.PatchDryRunApprovalHandoffPreview.RequestEnvelopePrepared
                && !readObserved.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && !readObserved.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !readObserved.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && generatedDiffWithoutSwitchObserved.GeneratedDiffAcceptancePreview.Status == "EXPLICIT_SWITCH_REQUIRED"
                && generatedDiffWithoutSwitchObserved.GeneratedDiffAcceptancePreview.GeneratedDiffProvided
                && !generatedDiffWithoutSwitchObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !generatedDiffWithoutSwitchObserved.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && generatedDiffWithoutSwitchObserved.DiffSourceValidationPreview.Status == "DIFF_SOURCE_REQUIRED"
                && !generatedDiffWithoutSwitchObserved.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.Status == "ACCEPTED_FOR_VALIDATION_PREVIEW"
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.PlannerOutputEnvelopePrepared
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.ExplicitPreviewSwitchEnabled
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.GeneratedDiffProvided
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.GeneratedDiffBytes > 0
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.MaxGeneratedDiffBytes == AbsoluteMaxPatchBytes
                && !acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.DiffFileReadEnabled
                && !acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.InlineDiffAccepted
                && acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && !acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.RequestCreationEnabled
                && !acceptedGeneratedDiffObserved.GeneratedDiffAcceptancePreview.MutationAllowed
                && acceptedGeneratedDiffObserved.DiffSourceValidationPreview.Status == "VALID_DIFF_SOURCE_PREVIEW"
                && acceptedGeneratedDiffObserved.DiffSourceValidationPreview.DiffProvided
                && acceptedGeneratedDiffObserved.DiffSourceValidationPreview.DiffParsed
                && acceptedGeneratedDiffObserved.DiffSourceValidationPreview.DiffTouchesOnlyTargetFiles
                && acceptedGeneratedDiffObserved.DiffSourceValidationPreview.PatchApplyInputPrepared
                && !acceptedGeneratedDiffObserved.DiffSourceValidationPreview.PatchDryRunExecutionEnabled
                && !acceptedGeneratedDiffObserved.DiffSourceValidationPreview.RequestCreationEnabled
                && !acceptedGeneratedDiffObserved.DiffSourceValidationPreview.MutationAllowed
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.Status == "HANDOFF_VALIDATED_PREVIEW"
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.DiffBodyAvailable
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.ValidationInputPrepared
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.ValidationAttempted
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.DiffValidationPassed
                && acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.PatchApplyInputPrepared
                && !acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.RequestCreationEnabled
                && !acceptedGeneratedDiffObserved.PlannerDiffValidationHandoffPreview.MutationAllowed
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.Status == "DRY_RUN_REQUEST_ENVELOPE_PREPARED"
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.WorkspaceId == workspaceId
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.ToolName == "patch.apply"
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.ExecutionTarget == "USER_LOCAL_AGENT"
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.ApprovalState == "REQUIRED_BEFORE_SNAPSHOT_DRY_RUN"
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.TargetFiles.Contains("src/Calculator.cs")
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.DiffValidationPassed
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.PatchApplyInputPrepared
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.DryRunOnly
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.SnapshotCreationRequiredForFullDryRun
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.SnapshotCreationEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.PatchDryRunExecutionEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.EnqueueEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.Claimable
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.ApprovalRequiredBeforeDryRun
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.ApprovalRequiredBeforeMutation
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.TestExecutionEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.FinalReportPublicationEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.PartialReindexEnabled
                && acceptedGeneratedDiffObserved.PatchDryRunRequestEnvelopePreview.RequestEnvelopePreview is not null
                && acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.Status == "PREFLIGHT_INPUT_REQUIRED"
                && acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.DiffValidationPassed
                && acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.PatchApplyInputPrepared
                && !acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.ExecutionAttempted
                && !acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.SnapshotCreated
                && !acceptedGeneratedDiffObserved.PatchDryRunPreflightPreview.MutationApplied
                && acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.Status == "NONWRITING_PREFLIGHT_REQUIRED"
                && acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.RequestEnvelopePrepared
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.NonWritingPreflightPassed
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.ApprovalRequestCreationEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.EnqueueEnabled
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.Claimable
                && !acceptedGeneratedDiffObserved.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.Status == "DRY_RUN_REQUEST_ENVELOPE_PREPARED"
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.EnqueueEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.Claimable
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.SnapshotCreationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.Status == "PREFLIGHT_PASSED"
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.Requested
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.ReadyForExecution
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.ExecutionAttempted
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.DiffValidationPassed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.NonWritingPreflightOnly
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.FileReadAttempted
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.ContextValidationAttempted
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.PreflightPassed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.Files.Count == 1
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.SnapshotCreated
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.MutationApplied
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.PatchApplyInputPrepared
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.PatchDryRunExecutionEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.RequestCreationEnabled
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.ApprovalRequiredBeforeMutation
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.MutationAllowed
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.TestExecutionEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.FinalReportPublicationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunPreflightPreview.PartialReindexEnabled
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.Status == "APPROVAL_HANDOFF_PREPARED"
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.WorkspaceId == workspaceId
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.RepositoryId == repositoryId
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.TargetFiles.Contains("src/Calculator.cs")
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.DiffValidationPassed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.RequestEnvelopePrepared
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.NonWritingPreflightRequired
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.NonWritingPreflightPassed
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.ApprovalState == "AWAITING_USER_APPROVAL"
                && acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.HandoffPreview is not null
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.ApprovalRequestCreationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.EnqueueEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.Claimable
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.SnapshotCreationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.PatchDryRunExecutionEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.TestExecutionEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.FinalReportPublicationEnabled
                && !acceptedGeneratedDiffPreflightObserved.PatchDryRunApprovalHandoffPreview.PartialReindexEnabled
                && generatedDiffWrongTargetObserved.GeneratedDiffAcceptancePreview.Status == "ACCEPTED_FOR_VALIDATION_PREVIEW"
                && generatedDiffWrongTargetObserved.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && generatedDiffWrongTargetObserved.DiffSourceValidationPreview.Status == "BLOCKED_TARGET_MISMATCH"
                && generatedDiffWrongTargetObserved.DiffSourceValidationPreview.RejectedFiles.Contains("src/Other.cs")
                && !generatedDiffWrongTargetObserved.DiffSourceValidationPreview.PatchApplyInputPrepared
                && generatedDiffWrongTargetObserved.PlannerDiffValidationHandoffPreview.Status == "HANDOFF_VALIDATION_BLOCKED"
                && generatedDiffWrongTargetObserved.PlannerDiffValidationHandoffPreview.ValidationAttempted
                && !generatedDiffWrongTargetObserved.PlannerDiffValidationHandoffPreview.DiffValidationPassed
                && !generatedDiffWrongTargetObserved.PlannerDiffValidationHandoffPreview.PatchApplyInputPrepared
                && !generatedDiffWrongTargetObserved.PlannerDiffValidationHandoffPreview.MutationAllowed
                && generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.Status == "DIFF_VALIDATION_REQUIRED"
                && !generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && !generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.PatchApplyInputPrepared
                && !generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.PatchDryRunExecutionEnabled
                && !generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !generatedDiffWrongTargetObserved.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && generatedDiffWrongTargetObserved.PatchDryRunApprovalHandoffPreview.Status == "DRY_RUN_ENVELOPE_REQUIRED"
                && !generatedDiffWrongTargetObserved.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && !generatedDiffWrongTargetObserved.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !generatedDiffWrongTargetObserved.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && validDiffPreview.Status == "VALID_DIFF_SOURCE_PREVIEW"
                && validDiffPreview.DiffProvided
                && validDiffPreview.DiffParsed
                && validDiffPreview.TouchedFiles.SequenceEqual(new[] { "src/Calculator.cs" })
                && validDiffPreview.RejectedFiles.Count == 0
                && validDiffPreview.DiffTouchesOnlyTargetFiles
                && validDiffPreview.PatchApplyInputPrepared
                && !validDiffPreview.PatchDryRunExecutionEnabled
                && !validDiffPreview.RequestCreationEnabled
                && validDiffPreview.ApprovalRequiredBeforeMutation
                && !validDiffPreview.MutationAllowed
                && validPreflightPreview.Status == "PREFLIGHT_PASSED"
                && validPreflightPreview.Requested
                && validPreflightPreview.ReadyForExecution
                && validPreflightPreview.ExecutionAttempted
                && validPreflightPreview.DiffValidationPassed
                && validPreflightPreview.NonWritingPreflightOnly
                && validPreflightPreview.FileReadAttempted
                && validPreflightPreview.ContextValidationAttempted
                && validPreflightPreview.PreflightPassed
                && validPreflightPreview.Files.Count == 1
                && !validPreflightPreview.SnapshotCreated
                && !validPreflightPreview.MutationApplied
                && validPreflightPreview.PatchApplyInputPrepared
                && !validPreflightPreview.PatchDryRunExecutionEnabled
                && !validPreflightPreview.RequestCreationEnabled
                && validPreflightPreview.ApprovalRequiredBeforeMutation
                && !validPreflightPreview.MutationAllowed
                && !validPreflightPreview.TestExecutionEnabled
                && !validPreflightPreview.FinalReportPublicationEnabled
                && !validPreflightPreview.PartialReindexEnabled
                && mismatchPreflightPreview.Status == "CONTEXT_MISMATCH"
                && mismatchPreflightPreview.ExecutionAttempted
                && mismatchPreflightPreview.DiffValidationPassed
                && !mismatchPreflightPreview.PreflightPassed
                && mismatchPreflightPreview.FailureCode == "CONTEXT_MISMATCH"
                && !mismatchPreflightPreview.SnapshotCreated
                && !mismatchPreflightPreview.MutationApplied
                && !mismatchPreflightPreview.PatchDryRunExecutionEnabled
                && !mismatchPreflightPreview.MutationAllowed
                && rejectedDiffPreview.Status == "BLOCKED_TARGET_MISMATCH"
                && rejectedDiffPreview.DiffProvided
                && rejectedDiffPreview.DiffParsed
                && rejectedDiffPreview.RejectedFiles.Contains("src/Other.cs")
                && !rejectedDiffPreview.DiffTouchesOnlyTargetFiles
                && !rejectedDiffPreview.PatchApplyInputPrepared
                && !rejectedDiffPreview.PatchDryRunExecutionEnabled
                && !rejectedDiffPreview.RequestCreationEnabled
                && !rejectedDiffPreview.MutationAllowed
                && search is not null
                && search.SearchSnippetsRedacted
                && search.OutputSummary.TryGetValue("snippetsIncluded", out var snippetsIncluded)
                && snippetsIncluded is false
                && search.OutputSummary.TryGetValue("matchedPaths", out var matchedPaths)
                && matchedPaths is IEnumerable<string> paths
                && paths.Contains("src/Calculator.cs", StringComparer.OrdinalIgnoreCase)
                && blocked.Status == "BLOCKED_PREVIEW"
                && !blocked.ExecutionAttempted
                && blocked.CandidateSelection.Status == "BLOCKED_PREVIEW"
                && !blocked.CandidateSelection.FileReadPlanPrepared
                && !blocked.CandidateSelection.FileContentRead
                && !blocked.CandidateSelection.MutationAllowed
                && blocked.SelectedFileRead.Status == "NOT_REQUESTED"
                && !blocked.SelectedFileRead.FileContentRead
                && blocked.PatchIntentPreview.Status == "READ_REQUIRED"
                && !blocked.PatchIntentPreview.PlanningInputPrepared
                && !blocked.PatchIntentPreview.MutationAllowed
                && blocked.PatchProposalPreview.Status == "READ_REQUIRED"
                && !blocked.PatchProposalPreview.ProposalPrepared
                && !blocked.PatchProposalPreview.DiffGenerated
                && !blocked.PatchProposalPreview.MutationAllowed
                && blocked.DiffSourceInputPreview.Status == "READ_REQUIRED"
                && !blocked.DiffSourceInputPreview.SourceEnabled
                && !blocked.DiffSourceInputPreview.MutationAllowed
                && blocked.PlannerDiffOutputPreview.Status == "READ_REQUIRED"
                && !blocked.PlannerDiffOutputPreview.OutputEnvelopePrepared
                && !blocked.PlannerDiffOutputPreview.MutationAllowed
                && blocked.GeneratedDiffAcceptancePreview.Status == "READ_REQUIRED"
                && !blocked.GeneratedDiffAcceptancePreview.GeneratedDiffAccepted
                && !blocked.GeneratedDiffAcceptancePreview.ForwardToValidationPreview
                && !blocked.GeneratedDiffAcceptancePreview.MutationAllowed
                && blocked.PlannerDiffValidationHandoffPreview.Status == "READ_REQUIRED"
                && !blocked.PlannerDiffValidationHandoffPreview.ValidationForwardingEnabled
                && !blocked.PlannerDiffValidationHandoffPreview.MutationAllowed
                && blocked.DiffSourceValidationPreview.Status == "READ_REQUIRED"
                && !blocked.DiffSourceValidationPreview.DiffProvided
                && !blocked.DiffSourceValidationPreview.PatchApplyInputPrepared
                && !blocked.DiffSourceValidationPreview.MutationAllowed
                && blocked.PatchDryRunRequestEnvelopePreview.Status == "READ_REQUIRED"
                && !blocked.PatchDryRunRequestEnvelopePreview.RequestEnvelopePrepared
                && !blocked.PatchDryRunRequestEnvelopePreview.PatchApplyInputPrepared
                && !blocked.PatchDryRunRequestEnvelopePreview.PatchDryRunExecutionEnabled
                && !blocked.PatchDryRunRequestEnvelopePreview.RequestCreationEnabled
                && !blocked.PatchDryRunRequestEnvelopePreview.EnqueueEnabled
                && !blocked.PatchDryRunRequestEnvelopePreview.Claimable
                && !blocked.PatchDryRunRequestEnvelopePreview.MutationAllowed
                && blocked.PatchDryRunPreflightPreview.Status == "DIFF_VALIDATION_REQUIRED"
                && !blocked.PatchDryRunPreflightPreview.ExecutionAttempted
                && !blocked.PatchDryRunPreflightPreview.MutationAllowed
                && blocked.PatchDryRunApprovalHandoffPreview.Status == "READ_REQUIRED"
                && !blocked.PatchDryRunApprovalHandoffPreview.RequestEnvelopePrepared
                && !blocked.PatchDryRunApprovalHandoffPreview.NonWritingPreflightPassed
                && !blocked.PatchDryRunApprovalHandoffPreview.ApprovalHandoffPrepared
                && !blocked.PatchDryRunApprovalHandoffPreview.RequestCreationEnabled
                && !blocked.PatchDryRunApprovalHandoffPreview.ApprovalRequestCreationEnabled
                && !blocked.PatchDryRunApprovalHandoffPreview.EnqueueEnabled
                && !blocked.PatchDryRunApprovalHandoffPreview.Claimable
                && !blocked.PatchDryRunApprovalHandoffPreview.SnapshotCreationEnabled
                && !blocked.PatchDryRunApprovalHandoffPreview.PatchDryRunExecutionEnabled
                && !blocked.PatchDryRunApprovalHandoffPreview.MutationAllowed
                && blocked.Blockers.Contains("agent is not paired")
                && !json.Contains("secret-token", StringComparison.Ordinal)
                && !json.Contains("hidden-content", StringComparison.Ordinal)
                && readJson.Contains("hidden-content", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("codex read-only observation contract self-test failed");
                return 1;
            }
            Console.WriteLine("codex-read-only-observation-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static bool RunGitForSelfTest(string workingDirectory, params string[] args)
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = "git",
                WorkingDirectory = workingDirectory,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };
            foreach (var arg in args)
            {
                startInfo.ArgumentList.Add(arg);
            }
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return false;
            }
            process.WaitForExit(10_000);
            return process.HasExited && process.ExitCode == 0;
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            return false;
        }
    }

    private static int SelfTestCodexServerPlanFetchContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-codex-server-plan-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(agentRoot);
            Directory.CreateDirectory(workspaceRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(Guid.Parse("11111111-1111-1111-1111-111111111111"), "workspace", workspaceRoot, true)]
            });
            var preview = app.BuildCliCodexCommandPreviewReport(
                "fix",
                "repair failing tests",
                workspaceRoot,
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                8);
            var readiness = app.BuildCliWebSessionServerPlanReadinessReport();
            var handoffPreview = new CliCodexPatchDryRunApprovalHandoffPreview(
                Schema: "learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1",
                Status: "APPROVAL_HANDOFF_PREPARED",
                Goal: "repair failing tests",
                WorkspaceId: Guid.Parse("11111111-1111-1111-1111-111111111111"),
                RepositoryId: Guid.Parse("33333333-3333-3333-3333-333333333333"),
                ToolName: "patch.apply",
                ExecutionTarget: "USER_LOCAL_AGENT",
                ApprovalKind: "SNAPSHOT_WRITING_DRY_RUN",
                ApprovalState: "AWAITING_USER_APPROVAL",
                TargetFiles: ["src/Calculator.cs"],
                DiffValidationPassed: true,
                RequestEnvelopePrepared: true,
                NonWritingPreflightRequired: true,
                NonWritingPreflightPassed: true,
                ApprovalHandoffPrepared: true,
                DryRunApprovalRequired: true,
                MutationApprovalRequired: true,
                RequestCreationEnabled: false,
                ApprovalRequestCreationEnabled: false,
                EnqueueEnabled: false,
                Claimable: false,
                SnapshotCreationEnabled: false,
                PatchDryRunExecutionEnabled: false,
                MutationAllowed: false,
                TestExecutionEnabled: false,
                FinalReportPublicationEnabled: false,
                PartialReindexEnabled: false,
                HandoffPreview: new Dictionary<string, object?>
                {
                    ["schema"] = "learnbot.local-agent.patch-dry-run-approval-handoff.v1",
                    ["approvalState"] = "AWAITING_USER_APPROVAL"
                },
                Blocker: null,
                Reason: "self-test handoff");
            var submissionBodyWithHandoff = BuildCliCodexServerSubmissionBody(preview, handoffPreview);
            var blocked = BuildCliCodexServerPlanFetchResult(
                preview,
                submissionBodyWithHandoff,
                readiness,
                status: "BLOCKED_AUTH_REQUIRED",
                attempted: false,
                networkCallEnabled: false,
                webTokenProvided: false,
                httpStatusCode: null,
                serverResponse: null,
                error: "web token is required; pass --web-token or set LEARNBOT_WEB_TOKEN");
            var json = JsonSerializer.Serialize(blocked, JsonOptions);

            var ok = blocked.Schema == "learnbot.local-agent.codex-server-plan-fetch-result.v1"
                && blocked.Status == "BLOCKED_AUTH_REQUIRED"
                && blocked.WebSessionReadiness.Schema == "learnbot.local-agent.web-session-server-plan-readiness.v1"
                && blocked.OneCyclePreview.Schema == "learnbot.local-agent.codex-one-cycle-preview.v1"
                && blocked.OneCyclePreview.ReadyForReadOnlyToolLoop
                && !blocked.OneCyclePreview.MutationAllowed
                && blocked.ReadOnlyServerBridge.Schema == "learnbot.local-agent.codex-read-only-server-bridge.v1"
                && blocked.ReadOnlyServerBridge.Status == "BLOCKED_OR_WAITING"
                && blocked.ReadOnlyServerBridge.FetchStatus == "BLOCKED_AUTH_REQUIRED"
                && blocked.ReadOnlyServerBridge.OneCycleReadyForReadOnlyToolLoop
                && !blocked.ReadOnlyServerBridge.AuthenticatedServerPlanReady
                && !blocked.ReadOnlyServerBridge.ServerPlanFetchAttempted
                && !blocked.ReadOnlyServerBridge.ServerPlanFetched
                && !blocked.ReadOnlyServerBridge.ServerPlanNetworkCallEnabled
                && !blocked.ReadOnlyServerBridge.EnvironmentTokenFallbackUsed
                && !blocked.ReadOnlyServerBridge.StoredSessionAuthUsed
                && !blocked.ReadOnlyServerBridge.StoredSessionAuthEnabled
                && !blocked.ReadOnlyServerBridge.RequestCreationEnabled
                && !blocked.ReadOnlyServerBridge.RunnerPreviewFetchEnabled
                && blocked.ReadOnlyServerBridge.RunnerPreviewEndpoint == "/api/code-agent/loop/runner/preview"
                && blocked.ReadOnlyServerBridge.SelectToolPreviewEndpoint == "/api/code-agent/loop/runner/select-tool-preview"
                && blocked.ReadOnlyServerBridge.EnqueueSelectedReadOnlyEndpoint == "/api/code-agent/loop/runner/enqueue-selected-read-only"
                && blocked.ReadOnlyServerBridge.RunStatusEndpoint == "/api/code-agent/loop/runs/{loopId}"
                && blocked.ReadOnlyServerBridge.AdvanceEndpoint == "/api/code-agent/loop/runs/{loopId}/advance"
                && !blocked.ReadOnlyServerBridge.AutoAdvanceAvailable
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.Schema == "learnbot.local-agent.codex-file-discovery-read-plan.v1"
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.DryRunOnly
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.PlanPrepared
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.FileDiscoveryPlanEnabled
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.FileReadPlanEnabled
                && !blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.ToolExecutionEnabled
                && !blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.FileContentRead
                && !blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.RequestCreationEnabled
                && !blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.MutationAllowed
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.CandidateTools.Contains("file.search")
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "workspace.tree"
                    && !envelope.RequestCreationEnabled
                    && !envelope.EnqueueEnabled
                    && !envelope.Claimable
                    && !envelope.ExecutionEnabled
                    && !envelope.MutationAllowed)
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "workspace.search"
                    && !envelope.RequestCreationEnabled
                    && !envelope.ExecutionEnabled
                    && !envelope.FileContentRead)
                && blocked.ReadOnlyServerBridge.FileDiscoveryReadPlan.RequestEnvelopePreviews.Any(envelope =>
                    envelope.ToolName == "git.status"
                    && !envelope.RequestCreationEnabled
                    && !envelope.ExecutionEnabled)
                && blocked.ReadOnlyServerBridge.FileDiscoveryPlanEnabled
                && blocked.ReadOnlyServerBridge.FileReadPlanEnabled
                && !blocked.ReadOnlyServerBridge.PatchDryRunEnabled
                && !blocked.ReadOnlyServerBridge.MutationAllowed
                && !blocked.ReadOnlyServerBridge.TokenSecretPrinted
                && blocked.ReadOnlyServerBridge.OrderedReadOnlyStages.Contains("file-discovery")
                && blocked.ReadOnlyServerBridge.OrderedReadOnlyStages.Contains("file-read")
                && blocked.ReadOnlyServerBridge.Blockers.Contains("web token is required before authenticated server loop/runner preview fetch")
                && blocked.WebSessionReadiness.Status == "BLOCKED_NO_WEB_SESSION"
                && !blocked.WebSessionReadiness.ServerPlanFetchFromStoredSessionEnabled
                && blocked.WebSessionReadiness.StoredSessionAuthReadiness.Schema == "learnbot.local-agent.web-session-stored-session-auth-readiness.v1"
                && !blocked.WebSessionReadiness.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !blocked.WebSessionReadiness.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && !blocked.WebSessionReadiness.StoredSessionTokenLoaded
                && !blocked.WebSessionReadiness.LocalAgentTokenUsed
                && !blocked.WebSessionReadiness.TokenSecretPrinted
                && !blocked.Attempted
                && !blocked.NetworkCallEnabled
                && !blocked.UsedLocalAgentToken
                && !blocked.WebTokenProvided
                && !blocked.TokenSecretPrinted
                && !blocked.RequestCreated
                && !blocked.MutationAllowed
                && blocked.Endpoint == "/api/code-agent/loop/runs"
                && blocked.Method == "POST"
                && blocked.ServerSubmissionPlan.ReadyForDisabledPlan
                && blocked.ServerSubmissionPlan.BodyPreview.TryGetValue("patchDryRunApprovalHandoffPreview", out var submittedHandoff)
                && ReferenceEquals(submittedHandoff, handoffPreview)
                && blocked.ServerSubmissionPlan.BodyPreview.TryGetValue("repositoryId", out var submittedRepositoryId)
                && submittedRepositoryId is Guid
                && blocked.Blockers.Contains("web token is required; pass --web-token or set LEARNBOT_WEB_TOKEN")
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("secret-web-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("codex server plan fetch contract self-test failed");
                return 1;
            }
            Console.WriteLine("codex-server-plan-fetch-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebLoginSessionPreviewContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-login-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });
            var loginPlan = app.BuildCliWebLoginPlanReport("jinsu.kim", null, rememberLogin: true);
            var missingIdentifier = app.BuildCliWebLoginPlanReport(null, null, rememberLogin: false);
            var session = app.BuildCliWebSessionStatusReport();
            var json = JsonSerializer.Serialize(new { loginPlan, missingIdentifier, session }, JsonOptions);

            var ok = loginPlan.Schema == "learnbot.local-agent.web-login-plan.v1"
                && loginPlan.Status == "DISABLED_PREVIEW"
                && loginPlan.Endpoint == "/api/auth/login"
                && loginPlan.DeviceSessionPlanEndpoint == "/api/auth/cli-device-session/plan"
                && loginPlan.DeviceSessionCreatePlanEndpoint == "/api/auth/cli-device-session/create/plan"
                && loginPlan.AbsoluteDeviceSessionCreatePlanEndpointPreview == "http://localhost:8083/api/auth/cli-device-session/create/plan"
                && loginPlan.IdentifierProvided
                && loginPlan.RememberLogin
                && !loginPlan.PasswordCollected
                && !loginPlan.NetworkCallEnabled
                && !loginPlan.LoginExecutionEnabled
                && !loginPlan.SessionStorageEnabled
                && !loginPlan.CookiePersistenceEnabled
                && !loginPlan.LocalAgentTokenUsed
                && !loginPlan.TokenSecretPrinted
                && loginPlan.BodyPreview.TryGetValue("password", out var passwordPreview)
                && passwordPreview is string passwordPreviewText
                && string.Equals(passwordPreviewText, "<not-collected>", StringComparison.Ordinal)
                && loginPlan.FollowUpCommands.Contains("learnbot session create-plan")
                && loginPlan.FollowUpCommands.Contains("POST /api/auth/cli-device-session/create/plan")
                && missingIdentifier.Blockers.Contains("login id or email is required for a future login execution.")
                && session.Schema == "learnbot.local-agent.web-session-status.v1"
                && session.Status == "ENV_TOKEN_AVAILABLE"
                && session.ArtifactValidation.Schema == "learnbot.local-agent.web-session-artifact-validation.v1"
                && session.ArtifactValidation.Status == "MISSING"
                && !session.ArtifactValidation.ReadAttempted
                && session.ArtifactValidation.EncryptionRequired
                && !session.ArtifactValidation.AccessTokenLoaded
                && !session.ArtifactValidation.RefreshTokenLoaded
                && !session.ArtifactValidation.TokenSecretPrinted
                && session.ArtifactValidation.ProductionCryptoPreviewRequirement.Schema == "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1"
                && session.ArtifactValidation.ProductionCryptoPreviewRequirement.ProofCommand == "learnbot session artifact-production-crypto-preview --preview-only"
                && !session.ArtifactValidation.ProductionCryptoPreviewRequirement.AutoRunEnabled
                && !session.ArtifactValidation.ProductionCryptoPreviewRequirement.ArtifactWriteEnabled
                && !session.ArtifactValidation.ProductionCryptoPreviewRequirement.StoredSessionLoadingEnabled
                && !session.ArtifactValidation.ProductionCryptoPreviewRequirement.TokenSecretPrinted
                && session.SecretProviderPlan.Schema == "learnbot.local-agent.web-session-secret-provider-plan.v1"
                && session.SecretProviderPlan.Status == "PRODUCTION_PROVIDER_DISABLED_PREVIEW"
                && session.SecretProviderPlan.Provider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && !session.SecretProviderPlan.ProviderProbeEnabled
                && session.SecretProviderPlan.ManualNoSecretProbeAvailable == OperatingSystem.IsWindows()
                && !session.SecretProviderPlan.ProductionEncryptionEnabled
                && !session.SecretProviderPlan.ProductionDecryptionEnabled
                && !session.SecretProviderPlan.ProductionStoredSessionLoadingEnabled
                && !session.SecretProviderPlan.TestOnlyProviderAcceptedForProduction
                && !session.SecretProviderPlan.PlaintextTokenSerializationAllowed
                && !session.SecretProviderPlan.TokenSecretPrinted
                && session.StoredSessionAuthReadiness.Schema == "learnbot.local-agent.web-session-stored-session-auth-readiness.v1"
                && session.StoredSessionAuthReadiness.Status == "STORED_SESSION_AUTH_DISABLED_PREVIEW"
                && !session.StoredSessionAuthReadiness.FileReadAttempted
                && !session.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !session.StoredSessionAuthReadiness.StoredSessionLoaded
                && !session.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && !session.StoredSessionAuthReadiness.TokenSecretPrinted
                && session.ClaimPlanEndpoint == "/api/auth/cli-device-session/claim/plan"
                && !session.ClaimPollingEnabled
                && session.EnvironmentWebTokenPresent
                && !string.IsNullOrWhiteSpace(session.EnvironmentWebTokenFingerprint)
                && session.UsableForServerPlanFetch
                && !session.LocalSessionArtifactWriteEnabled
                && session.LocalSessionArtifactEncryptedRequired
                && !session.LocalAgentTokenUsed
                && !session.TokenSecretPrinted
                && !session.LoginExecutionEnabled
                && !session.SessionStorageEnabled
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web login/session preview contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-login-session-preview-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static async Task<int> SelfTestWebSessionPlanFetchContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-plan-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });
            var devicePlan = await app.FetchCliWebSessionPlan("device-session", ["--offline"]);
            var createPlan = await app.FetchCliWebSessionPlan("device-session-create", ["--offline"]);
            var claimPlan = await app.FetchCliWebSessionPlan("claim", ["--offline", "--device-code", "secret-device-code"]);
            var claimResultPlan = await app.FetchCliWebSessionPlan("claim-result", ["--offline", "--claim-status", "secret-claim-status"]);
            var json = JsonSerializer.Serialize(new { devicePlan, createPlan, claimPlan, claimResultPlan }, JsonOptions);
            var ok = devicePlan.Schema == "learnbot.local-agent.web-session-plan-fetch-result.v1"
                && devicePlan.PlanKind == "device-session"
                && devicePlan.Status == "LOCAL_STATIC_FALLBACK"
                && !devicePlan.Attempted
                && !devicePlan.NetworkCallEnabled
                && devicePlan.FallbackUsed
                && !devicePlan.UsedLocalAgentToken
                && !devicePlan.TokenSecretPrinted
                && !devicePlan.RequestCreated
                && !devicePlan.DeviceCodeIssued
                && !devicePlan.SessionClaimed
                && !devicePlan.AccessTokenIssued
                && !devicePlan.RefreshTokenIssued
                && !devicePlan.CookiePersistenceEnabled
                && !devicePlan.LocalSessionArtifactWritten
                && devicePlan.Endpoint == "/api/auth/cli-device-session/plan"
                && devicePlan.LocalPlan.TryGetValue("schema", out var deviceSchema)
                && string.Equals(deviceSchema?.ToString(), "learnbot.local-agent.web-device-session-static-plan.v1", StringComparison.Ordinal)
                && createPlan.PlanKind == "device-session-create"
                && createPlan.Status == "LOCAL_STATIC_FALLBACK"
                && !createPlan.Attempted
                && !createPlan.NetworkCallEnabled
                && createPlan.FallbackUsed
                && !createPlan.UsedLocalAgentToken
                && !createPlan.TokenSecretPrinted
                && !createPlan.RequestCreated
                && !createPlan.DeviceCodeIssued
                && !createPlan.SessionClaimed
                && !createPlan.AccessTokenIssued
                && !createPlan.RefreshTokenIssued
                && !createPlan.CookiePersistenceEnabled
                && !createPlan.LocalSessionArtifactWritten
                && createPlan.Endpoint == "/api/auth/cli-device-session/create/plan"
                && createPlan.LocalPlan.TryGetValue("schema", out var createSchema)
                && string.Equals(createSchema?.ToString(), "learnbot.local-agent.web-device-session-create-static-plan.v1", StringComparison.Ordinal)
                && createPlan.LocalPlan.TryGetValue("userCodeFormat", out var userCodeFormat)
                && string.Equals(userCodeFormat?.ToString(), "XXXX-XXXX", StringComparison.Ordinal)
                && createPlan.LocalPlan.TryGetValue("expiresInSeconds", out var expiresInSeconds)
                && expiresInSeconds is 600
                && claimPlan.PlanKind == "claim"
                && claimPlan.Status == "LOCAL_STATIC_FALLBACK"
                && !claimPlan.Attempted
                && !claimPlan.NetworkCallEnabled
                && claimPlan.FallbackUsed
                && !claimPlan.UsedLocalAgentToken
                && !claimPlan.TokenSecretPrinted
                && !claimPlan.RequestCreated
                && !claimPlan.DeviceCodeIssued
                && !claimPlan.SessionClaimed
                && !claimPlan.AccessTokenIssued
                && !claimPlan.RefreshTokenIssued
                && !claimPlan.CookiePersistenceEnabled
                && !claimPlan.LocalSessionArtifactWritten
                && claimPlan.Endpoint == "/api/auth/cli-device-session/claim/plan"
                && claimPlan.LocalPlan.TryGetValue("schema", out var claimSchema)
                && string.Equals(claimSchema?.ToString(), "learnbot.local-agent.web-session-claim-static-plan.v1", StringComparison.Ordinal)
                && claimPlan.LocalPlan.TryGetValue("localSessionArtifactEncryptedRequired", out var encryptedRequired)
                && encryptedRequired is true
                && claimPlan.LocalPlan.TryGetValue("webSessionArtifactBodyPreview", out var artifactPreview)
                && artifactPreview is IReadOnlyDictionary<string, object?> artifact
                && string.Equals(artifact["schema"]?.ToString(), "learnbot.local-agent.web-session-artifact.v1", StringComparison.Ordinal)
                && string.Equals(artifact["encryptedAccessToken"]?.ToString(), "<encrypted-access-token>", StringComparison.Ordinal)
                && string.Equals(artifact["encryptedRefreshToken"]?.ToString(), "<encrypted-refresh-token>", StringComparison.Ordinal)
                && artifact.TryGetValue("encryption", out var encryption)
                && encryption is IReadOnlyDictionary<string, object?> encryptionPreview
                && encryptionPreview.TryGetValue("plaintextTokenSerializationAllowed", out var plaintextAllowed)
                && plaintextAllowed is false
                && claimResultPlan.PlanKind == "claim-result"
                && claimResultPlan.Status == "LOCAL_STATIC_FALLBACK"
                && !claimResultPlan.Attempted
                && !claimResultPlan.NetworkCallEnabled
                && claimResultPlan.FallbackUsed
                && !claimResultPlan.UsedLocalAgentToken
                && !claimResultPlan.TokenSecretPrinted
                && !claimResultPlan.RequestCreated
                && !claimResultPlan.SessionClaimed
                && !claimResultPlan.AccessTokenIssued
                && !claimResultPlan.RefreshTokenIssued
                && !claimResultPlan.LocalSessionArtifactWritten
                && claimResultPlan.Endpoint == "/api/auth/cli-device-session/claim-result/plan"
                && claimResultPlan.LocalPlan.TryGetValue("schema", out var claimResultSchema)
                && string.Equals(claimResultSchema?.ToString(), "learnbot.local-agent.web-session-claim-result-static-plan.v1", StringComparison.Ordinal)
                && claimResultPlan.LocalPlan.TryGetValue("claimResultRequired", out var claimResultRequired)
                && claimResultRequired is true
                && claimResultPlan.LocalPlan.TryGetValue("plaintextTokenSerializationAllowed", out var plaintextSerializationAllowed)
                && plaintextSerializationAllowed is false
                && claimResultPlan.LocalPlan.TryGetValue("artifactWriterPlanPreview", out var writerPreview)
                && writerPreview is IReadOnlyDictionary<string, object?> writer
                && string.Equals(writer["schema"]?.ToString(), "learnbot.local-agent.web-session-artifact-writer-plan.v1", StringComparison.Ordinal)
                && writer.TryGetValue("artifactBodyPreview", out var writerArtifactPreview)
                && writerArtifactPreview is IReadOnlyDictionary<string, object?> writerArtifact
                && string.Equals(writerArtifact["encryptedAccessToken"]?.ToString(), "<encrypted-access-token>", StringComparison.Ordinal)
                && writer.TryGetValue("write", out var writePreview)
                && writePreview is IReadOnlyDictionary<string, object?> write
                && write.TryGetValue("enabled", out var writeEnabled)
                && writeEnabled is false
                && write.TryGetValue("plaintextTokenSerializationAllowed", out var writerPlaintextAllowed)
                && writerPlaintextAllowed is false
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("secret-device-code", StringComparison.Ordinal)
                && !json.Contains("secret-claim-status", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session plan fetch contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-plan-fetch-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionServerPlanReadinessContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-server-plan-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", null);
            var blocked = app.BuildCliWebSessionServerPlanReadinessReport();
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");
            var ready = app.BuildCliWebSessionServerPlanReadinessReport();
            var json = JsonSerializer.Serialize(new { blocked, ready }, JsonOptions);

            var ok = blocked.Schema == "learnbot.local-agent.web-session-server-plan-readiness.v1"
                && blocked.Status == "BLOCKED_NO_WEB_SESSION"
                && blocked.ArtifactValidation.Schema == "learnbot.local-agent.web-session-artifact-validation.v1"
                && blocked.ArtifactValidation.Status == "MISSING"
                && !blocked.ArtifactValidation.ReadAttempted
                && !blocked.ArtifactValidation.AccessTokenLoaded
                && !blocked.ArtifactValidation.RefreshTokenLoaded
                && blocked.ArtifactValidation.EncryptionRequired
                && blocked.ArtifactValidation.ProductionCryptoPreviewRequirement.Schema == "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1"
                && !blocked.ArtifactValidation.ProductionCryptoPreviewRequirement.AutoRunEnabled
                && !blocked.ArtifactValidation.ProductionCryptoPreviewRequirement.StoredSessionLoadingEnabled
                && !blocked.ArtifactValidation.ProductionCryptoPreviewRequirement.TokenSecretPrinted
                && blocked.SecretProviderPlan.Schema == "learnbot.local-agent.web-session-secret-provider-plan.v1"
                && blocked.SecretProviderPlan.Status == "PRODUCTION_PROVIDER_DISABLED_PREVIEW"
                && !blocked.SecretProviderPlan.ProviderProbeEnabled
                && blocked.SecretProviderPlan.ManualNoSecretProbeAvailable == OperatingSystem.IsWindows()
                && !blocked.SecretProviderPlan.ProductionDecryptionEnabled
                && !blocked.SecretProviderPlan.ProductionStoredSessionLoadingEnabled
                && !blocked.SecretProviderPlan.TokenSecretPrinted
                && blocked.StoredSessionAuthReadiness.Schema == "learnbot.local-agent.web-session-stored-session-auth-readiness.v1"
                && !blocked.StoredSessionAuthReadiness.FileReadAttempted
                && !blocked.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !blocked.StoredSessionAuthReadiness.StoredSessionLoaded
                && !blocked.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && !blocked.StoredSessionAuthReadiness.TokenSecretPrinted
                && !blocked.StoredSessionReadable
                && !blocked.StoredSessionTokenLoaded
                && blocked.StoredSessionTokenFingerprint is null
                && !blocked.EnvironmentWebTokenPresent
                && !blocked.EnvironmentWebTokenUsableForServerPlanFetch
                && !blocked.StoredSessionUsableForServerPlanFetch
                && !blocked.ServerPlanFetchFromStoredSessionEnabled
                && !blocked.LocalSessionArtifactWriteEnabled
                && blocked.LocalSessionArtifactEncryptedRequired
                && !blocked.LocalAgentTokenUsed
                && !blocked.TokenSecretPrinted
                && !blocked.RequestCreated
                && !blocked.MutationAllowed
                && blocked.FollowUpCommand == "learnbot session create-plan"
                && blocked.Blockers.Contains("no LEARNBOT_WEB_TOKEN is present and stored web-session artifact loading is disabled")
                && ready.Status == "ENV_TOKEN_FALLBACK_READY"
                && ready.ArtifactValidation.Schema == "learnbot.local-agent.web-session-artifact-validation.v1"
                && !ready.ArtifactValidation.ReadAttempted
                && ready.ArtifactValidation.EncryptionRequired
                && ready.ArtifactValidation.ProductionCryptoPreviewRequirement.Schema == "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1"
                && !ready.ArtifactValidation.ProductionCryptoPreviewRequirement.AutoRunEnabled
                && !ready.ArtifactValidation.ProductionCryptoPreviewRequirement.StoredSessionLoadingEnabled
                && !ready.ArtifactValidation.ProductionCryptoPreviewRequirement.TokenSecretPrinted
                && ready.SecretProviderPlan.Schema == "learnbot.local-agent.web-session-secret-provider-plan.v1"
                && !ready.SecretProviderPlan.ProviderProbeEnabled
                && ready.SecretProviderPlan.ManualNoSecretProbeAvailable == OperatingSystem.IsWindows()
                && !ready.SecretProviderPlan.ProductionDecryptionEnabled
                && !ready.SecretProviderPlan.ProductionStoredSessionLoadingEnabled
                && !ready.SecretProviderPlan.TokenSecretPrinted
                && ready.StoredSessionAuthReadiness.Schema == "learnbot.local-agent.web-session-stored-session-auth-readiness.v1"
                && !ready.StoredSessionAuthReadiness.FileReadAttempted
                && !ready.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !ready.StoredSessionAuthReadiness.StoredSessionLoaded
                && !ready.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && !ready.StoredSessionAuthReadiness.TokenSecretPrinted
                && ready.EnvironmentWebTokenPresent
                && !string.IsNullOrWhiteSpace(ready.EnvironmentWebTokenFingerprint)
                && ready.EnvironmentWebTokenUsableForServerPlanFetch
                && !ready.StoredSessionUsableForServerPlanFetch
                && !ready.ServerPlanFetchFromStoredSessionEnabled
                && !ready.LocalSessionArtifactWriteEnabled
                && ready.LocalSessionArtifactEncryptedRequired
                && !ready.LocalAgentTokenUsed
                && !ready.TokenSecretPrinted
                && !ready.RequestCreated
                && !ready.MutationAllowed
                && ready.FollowUpCommand.Contains("--server-plan", StringComparison.Ordinal)
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session server-plan readiness contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-server-plan-readiness-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionSecretProviderPlanContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-secret-provider-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var provider = app.BuildCliWebSessionSecretProviderPlanReport();
            var session = app.BuildCliWebSessionStatusReport();
            var readiness = app.BuildCliWebSessionServerPlanReadinessReport();
            var json = JsonSerializer.Serialize(new { provider, session, readiness }, JsonOptions);

            var ok = provider.Schema == "learnbot.local-agent.web-session-secret-provider-plan.v1"
                && provider.Status == "PRODUCTION_PROVIDER_DISABLED_PREVIEW"
                && provider.Provider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && provider.OsSecretStoreRequired
                && !provider.ProviderProbeEnabled
                && provider.ManualNoSecretProbeAvailable == OperatingSystem.IsWindows()
                && !provider.ProductionEncryptionEnabled
                && !provider.ProductionDecryptionEnabled
                && !provider.ProductionStoredSessionLoadingEnabled
                && !provider.TestOnlyProviderAcceptedForProduction
                && !provider.PlaintextTokenSerializationAllowed
                && !provider.TokenSecretPrinted
                && !provider.LocalAgentTokenUsed
                && provider.FollowUpCommand == (OperatingSystem.IsWindows()
                    ? "learnbot session secret-provider-probe"
                    : "learnbot session artifact-reader-test-validate --test-only")
                && provider.Blockers.Count == 1
                && session.SecretProviderPlan.Schema == provider.Schema
                && !session.SecretProviderPlan.ProductionStoredSessionLoadingEnabled
                && readiness.SecretProviderPlan.Schema == provider.Schema
                && !readiness.SecretProviderPlan.ProductionStoredSessionLoadingEnabled
                && !readiness.StoredSessionTokenLoaded
                && !readiness.ServerPlanFetchFromStoredSessionEnabled
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session secret-provider plan contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-secret-provider-plan-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionSecretProviderProbeContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-secret-provider-probe-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var probe = app.BuildCliWebSessionSecretProviderProbeResult();
            var json = JsonSerializer.Serialize(probe, JsonOptions);
            var windows = OperatingSystem.IsWindows();

            var ok = probe.Schema == "learnbot.local-agent.web-session-secret-provider-probe-result.v1"
                && probe.Provider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && probe.WindowsDpapiCandidate == windows
                && probe.ProbeAttempted == windows
                && !probe.ProbeInputContainsTokenSecret
                && probe.ProtectSucceeded == windows
                && probe.UnprotectSucceeded == windows
                && probe.RoundTripSucceeded == windows
                && !probe.ProductionEncryptionEnabled
                && !probe.ProductionDecryptionEnabled
                && !probe.ProductionStoredSessionLoadingEnabled
                && !probe.PlaintextTokenSerializationAllowed
                && !probe.TokenSecretPrinted
                && !probe.LocalAgentTokenUsed
                && !probe.StoredSessionLoaded
                && (windows
                    ? probe.Status == "NO_SECRET_PROVIDER_PROBE_SUCCEEDED" && probe.Blockers.Count == 0 && probe.Error is null
                    : probe.Status == "NO_SECRET_PROVIDER_PROBE_BLOCKED" && probe.Blockers.Contains("Windows DPAPI probe is not available on this platform."))
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session secret-provider probe contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-secret-provider-probe-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionProductionArtifactCryptoPreviewContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-production-artifact-crypto-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var missingOptIn = app.BuildCliWebSessionProductionArtifactCryptoPreviewResult([]);
            var preview = app.BuildCliWebSessionProductionArtifactCryptoPreviewResult(["--preview-only"]);
            var json = JsonSerializer.Serialize(new { missingOptIn, preview }, JsonOptions);
            var windows = OperatingSystem.IsWindows();

            var ok = missingOptIn.Schema == "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1"
                && missingOptIn.Status == "BLOCKED_OR_FAILED"
                && !missingOptIn.PreviewOnly
                && !missingOptIn.CryptoAttempted
                && missingOptIn.Blockers.Contains("production artifact crypto preview requires explicit --preview-only.")
                && preview.Schema == "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1"
                && preview.Provider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && preview.PreviewOnly
                && preview.WindowsDpapiCandidate == windows
                && preview.ArtifactSchema == "learnbot.local-agent.web-session-artifact.v1"
                && preview.EncryptionRequired
                && !preview.PlaintextTokenSerializationAllowed
                && !preview.ArtifactWriteEnabled
                && !preview.LocalSessionArtifactWritten
                && !preview.ArtifactReadEnabled
                && !preview.StoredSessionLoaded
                && !preview.ProductionStoredSessionLoadingEnabled
                && !preview.TokenSecretPrinted
                && !preview.LocalAgentTokenUsed
                && (windows
                    ? preview.Status == "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED"
                        && preview.CryptoAttempted
                        && preview.EncryptedAccessTokenPresent
                        && preview.EncryptedRefreshTokenPresent
                        && preview.DecryptionVerified
                        && !string.IsNullOrWhiteSpace(preview.AccessTokenFingerprint)
                        && !string.IsNullOrWhiteSpace(preview.RefreshTokenFingerprint)
                        && !preview.PlaintextTokenSerializationDetected
                        && preview.Blockers.Count == 0
                        && preview.Error is null
                    : preview.Status == "BLOCKED_OR_FAILED"
                        && !preview.CryptoAttempted
                        && preview.Blockers.Contains("Windows DPAPI current-user provider is required for this preview."))
                && !File.Exists(WebSessionPath())
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("production-preview-access-token-material", StringComparison.Ordinal)
                && !json.Contains("production-preview-refresh-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session production artifact crypto preview contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-production-artifact-crypto-preview-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionProductionArtifactWriterPreviewContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-production-artifact-writer-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var missingOptIn = app.BuildCliWebSessionProductionArtifactWriterPreviewResult([
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var ready = app.BuildCliWebSessionProductionArtifactWriterPreviewResult([
                "--preview-only",
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var missingMetadata = app.BuildCliWebSessionProductionArtifactWriterPreviewResult(["--preview-only", "--approved"]);
            var writeRequested = app.BuildCliWebSessionProductionArtifactWriterPreviewResult([
                "--preview-only",
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z",
                "--write"
            ]);
            var json = JsonSerializer.Serialize(new { missingOptIn, ready, missingMetadata, writeRequested }, JsonOptions);
            var windows = OperatingSystem.IsWindows();

            var readyExpected = windows;
            var ok = missingOptIn.Schema == "learnbot.local-agent.web-session-production-artifact-writer-preview-result.v1"
                && missingOptIn.Status == "BLOCKED_OR_FAILED"
                && !missingOptIn.PreviewOnly
                && !missingOptIn.ArtifactBodyPreviewPrepared
                && missingOptIn.Blockers.Contains("production artifact writer preview requires explicit --preview-only.")
                && ready.Schema == "learnbot.local-agent.web-session-production-artifact-writer-preview-result.v1"
                && ready.PreviewOnly
                && ready.Preflight.Schema == "learnbot.local-agent.web-session-artifact-writer-preflight-result.v1"
                && ready.Preflight.ArtifactWriterPreflightPassed
                && ready.CryptoPreview.Schema == "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1"
                && ready.CryptoPreview.PreviewOnly
                && ready.ArtifactBodyPreviewPrepared == readyExpected
                && ready.Status == (readyExpected ? "PRODUCTION_ARTIFACT_WRITER_PREVIEW_READY" : "BLOCKED_OR_FAILED")
                && ready.BodyFieldNames.Contains("encryptedAccessToken")
                && ready.BodyFieldNames.Contains("encryptedRefreshToken")
                && ready.AtomicWritePlan.Schema == "learnbot.local-agent.web-session-production-artifact-atomic-write-plan.v1"
                && ready.AtomicWritePlan.SessionPath == WebSessionPath()
                && ready.AtomicWritePlan.TempPathPattern.EndsWith(".tmp-<nonce>", StringComparison.Ordinal)
                && ready.AtomicWritePlan.ParentDirectoryCreationRequired
                && ready.AtomicWritePlan.AtomicReplaceRequired
                && !ready.AtomicWritePlan.WriteRequested
                && !ready.AtomicWritePlan.WriteEnabled
                && !ready.AtomicWritePlan.WriteRefused
                && !ready.AtomicWritePlan.LocalSessionArtifactWritten
                && !ready.AtomicWritePlan.ArtifactReadAfterWriteEnabled
                && !ready.AtomicWritePlan.StoredSessionLoadingEnabled
                && !ready.AtomicWritePlan.PlaintextTokenSerializationAllowed
                && !ready.AtomicWritePlan.TokenSecretPrinted
                && !ready.AtomicWritePlan.LocalAgentTokenUsed
                && ready.EncryptionProvider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && !ready.PlaintextTokenSerializationAllowed
                && !ready.PlaintextTokenSerializationDetected
                && !ready.ArtifactWriteEnabled
                && !ready.LocalSessionArtifactWritten
                && !ready.ArtifactReadEnabled
                && !ready.StoredSessionLoaded
                && !ready.ProductionStoredSessionLoadingEnabled
                && !ready.TokenSecretPrinted
                && !ready.LocalAgentTokenUsed
                && (readyExpected
                    ? ready.Blockers.Count == 0
                        && ready.ArtifactBodyPreview is not null
                        && ready.ArtifactBodyPreview.TryGetValue("schema", out var schema)
                        && string.Equals(schema?.ToString(), "learnbot.local-agent.web-session-artifact.v1", StringComparison.Ordinal)
                        && ready.ArtifactBodyPreview.TryGetValue("encryptedAccessToken", out var encryptedAccessToken)
                        && string.Equals(encryptedAccessToken?.ToString(), "<dpapi-current-user-protected-access-token>", StringComparison.Ordinal)
                        && !string.IsNullOrWhiteSpace(ready.ArtifactBodyPreviewSha256)
                    : ready.Blockers.Contains("production artifact crypto preview proof is required before preparing the writer body preview."))
                && missingMetadata.Status == "BLOCKED_OR_FAILED"
                && !missingMetadata.Preflight.ArtifactWriterPreflightPassed
                && missingMetadata.Preflight.MissingOrInvalidFields.Contains("accessToken")
                && missingMetadata.Preflight.MissingOrInvalidFields.Contains("refreshToken")
                && missingMetadata.Preflight.MissingOrInvalidFields.Contains("expiresAt")
                && writeRequested.Status == "BLOCKED_OR_FAILED"
                && writeRequested.AtomicWritePlan.WriteRequested
                && !writeRequested.AtomicWritePlan.WriteEnabled
                && writeRequested.AtomicWritePlan.WriteRefused
                && !writeRequested.AtomicWritePlan.LocalSessionArtifactWritten
                && writeRequested.Blockers.Contains("local web-session artifact writing is still disabled; this command performs preflight only.")
                && !File.Exists(WebSessionPath())
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("production-preview-access-token-material", StringComparison.Ordinal)
                && !json.Contains("production-preview-refresh-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session production artifact writer preview contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-production-artifact-writer-preview-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionProductionArtifactReaderPreviewContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-production-artifact-reader-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            Directory.CreateDirectory(Path.GetDirectoryName(WebSessionPath())!);
            File.WriteAllText(WebSessionPath(), "{\"encryptedAccessToken\":\"secret-web-token\"}", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var missingOptIn = app.BuildCliWebSessionProductionArtifactReaderPreviewResult([]);
            var ready = app.BuildCliWebSessionProductionArtifactReaderPreviewResult(["--preview-only"]);
            var json = JsonSerializer.Serialize(new { missingOptIn, ready }, JsonOptions);
            var windows = OperatingSystem.IsWindows();

            var ok = missingOptIn.Schema == "learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1"
                && missingOptIn.Status == "BLOCKED_OR_FAILED"
                && !missingOptIn.PreviewOnly
                && !missingOptIn.FileReadAttempted
                && missingOptIn.Blockers.Contains("production artifact reader preview requires explicit --preview-only.")
                && ready.Schema == "learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1"
                && ready.PreviewOnly
                && ready.SessionPath == WebSessionPath()
                && ready.CryptoPreview.Schema == "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1"
                && ready.RequiredArtifactSchema == "learnbot.local-agent.web-session-artifact.v1"
                && ready.AcceptedEncryptionProvider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && ready.RequiredFields.Contains("encryptedAccessToken")
                && ready.RequiredFields.Contains("encryptedRefreshToken")
                && !ready.FileReadEnabled
                && !ready.FileReadAttempted
                && !ready.JsonParseEnabled
                && !ready.SchemaValidationEnabled
                && ready.ProductionDecryptionPrimitiveVerified == windows
                && !ready.ProductionDecryptionEnabled
                && !ready.AccessTokenLoaded
                && !ready.RefreshTokenLoaded
                && !ready.StoredSessionLoaded
                && !ready.StoredSessionUsableForServerPlanFetch
                && !ready.ServerPlanFetchFromStoredSessionEnabled
                && !ready.TokenRefreshEnabled
                && !ready.PlaintextTokenSerializationAllowed
                && !ready.TokenSecretPrinted
                && !ready.LocalAgentTokenUsed
                && ready.FollowUpCommand == "learnbot session server-plan-readiness"
                && (windows
                    ? ready.Status == "PRODUCTION_ARTIFACT_READER_PREVIEW_READY" && ready.Blockers.Count == 0
                    : ready.Status == "BLOCKED_OR_FAILED" && ready.Blockers.Contains("production artifact crypto preview proof is required before modeling reader/decrypt readiness."))
                && json.Contains("secret-web-token", StringComparison.Ordinal) == false
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("production-preview-access-token-material", StringComparison.Ordinal)
                && !json.Contains("production-preview-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session production artifact reader preview contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-production-artifact-reader-preview-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionStoredSessionAuthReadinessContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-stored-auth-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        var previousWebToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", "secret-web-token");

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            Directory.CreateDirectory(Path.GetDirectoryName(WebSessionPath())!);
            File.WriteAllText(WebSessionPath(), "{\"encryptedAccessToken\":\"secret-web-token\",\"encryptedRefreshToken\":\"secret-refresh-token\"}", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            var readiness = app.BuildCliWebSessionStoredSessionAuthReadinessReport();
            var session = app.BuildCliWebSessionStatusReport();
            var serverPlan = app.BuildCliWebSessionServerPlanReadinessReport();
            var json = JsonSerializer.Serialize(new { readiness, session, serverPlan }, JsonOptions);

            var ok = readiness.Schema == "learnbot.local-agent.web-session-stored-session-auth-readiness.v1"
                && readiness.Status == "STORED_SESSION_AUTH_DISABLED_PREVIEW"
                && readiness.SessionPath == WebSessionPath()
                && readiness.ReaderPreview.Schema == "learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1"
                && readiness.RequiresBrowserClaimResult
                && readiness.RequiresProductionArtifactRead
                && readiness.RequiresAccessToken
                && readiness.RequiresRefreshToken
                && readiness.RequiresExpiresAt
                && readiness.RequiresRefreshExpiresAt
                && !readiness.ExpiryValidationEnabled
                && !readiness.RefreshEligibilityCheckEnabled
                && !readiness.TokenRefreshEnabled
                && !readiness.AccessTokenLoaded
                && !readiness.RefreshTokenLoaded
                && !readiness.StoredSessionLoaded
                && !readiness.StoredSessionUsableForServerPlanFetch
                && !readiness.ServerPlanFetchFromStoredSessionEnabled
                && readiness.EnvironmentTokenFallbackAllowed
                && !readiness.FileReadAttempted
                && !readiness.JsonParseAttempted
                && !readiness.DecryptionAttempted
                && !readiness.NetworkRefreshAttempted
                && !readiness.RequestCreated
                && !readiness.MutationAllowed
                && !readiness.LocalAgentTokenUsed
                && !readiness.TokenSecretPrinted
                && readiness.FollowUpCommand == "learnbot session server-plan-readiness"
                && readiness.Blockers.Contains("stored-session token refresh is disabled.")
                && readiness.Blockers.Contains("stored-session authenticated server-plan fetch is disabled.")
                && session.StoredSessionAuthReadiness.Schema == readiness.Schema
                && !session.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !session.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && serverPlan.StoredSessionAuthReadiness.Schema == readiness.Schema
                && !serverPlan.StoredSessionAuthReadiness.TokenRefreshEnabled
                && !serverPlan.StoredSessionAuthReadiness.ServerPlanFetchFromStoredSessionEnabled
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-refresh-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("production-preview-access-token-material", StringComparison.Ordinal)
                && !json.Contains("production-preview-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session stored-session auth readiness contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-stored-session-auth-readiness-contract-ok");
            return 0;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            Environment.SetEnvironmentVariable("LEARNBOT_WEB_TOKEN", previousWebToken);
            if (Directory.Exists(root))
            {
                try
                {
                    Directory.Delete(root, recursive: true);
                }
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionArtifactValidationContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-artifact-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var missing = app.BuildCliWebSessionArtifactValidationReport();
            Directory.CreateDirectory(Path.GetDirectoryName(WebSessionPath())!);
            File.WriteAllText(WebSessionPath(), "{\"accessToken\":\"secret-web-token\"}", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var present = app.BuildCliWebSessionArtifactValidationReport();
            var json = JsonSerializer.Serialize(new { missing, present }, JsonOptions);

            var ok = missing.Schema == "learnbot.local-agent.web-session-artifact-validation.v1"
                && missing.Status == "MISSING"
                && !missing.FileExists
                && !missing.ReadAttempted
                && !missing.JsonParsed
                && !missing.SchemaValidated
                && !missing.Encrypted
                && missing.EncryptionRequired
                && !missing.AccessTokenLoaded
                && !missing.RefreshTokenLoaded
                && !missing.TokenSecretPrinted
                && !missing.LocalAgentTokenUsed
                && missing.ProductionCryptoPreviewRequirement.Schema == "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1"
                && missing.ProductionCryptoPreviewRequirement.RequiredBeforeProductionStoredSessionLoading
                && missing.ProductionCryptoPreviewRequirement.ProofCommand == "learnbot session artifact-production-crypto-preview --preview-only"
                && missing.ProductionCryptoPreviewRequirement.ProofSchema == "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1"
                && missing.ProductionCryptoPreviewRequirement.Provider == "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE"
                && missing.ProductionCryptoPreviewRequirement.PreviewOnlyRequired
                && !missing.ProductionCryptoPreviewRequirement.AutoRunEnabled
                && !missing.ProductionCryptoPreviewRequirement.ArtifactWriteEnabled
                && !missing.ProductionCryptoPreviewRequirement.ArtifactReadEnabled
                && !missing.ProductionCryptoPreviewRequirement.StoredSessionLoadingEnabled
                && !missing.ProductionCryptoPreviewRequirement.TokenSecretPrinted
                && !missing.ProductionCryptoPreviewRequirement.LocalAgentTokenUsed
                && missing.RequiredSchema == "learnbot.local-agent.web-session-artifact.v1"
                && missing.RequiredFields.Contains("encryptedAccessToken")
                && missing.RequiredFields.Contains("encryptedRefreshToken")
                && present.Status == "VALIDATION_DISABLED_FILE_PRESENT"
                && present.FileExists
                && !present.ReadAttempted
                && !present.JsonParsed
                && !present.SchemaValidated
                && !present.Encrypted
                && present.EncryptionRequired
                && !present.AccessTokenLoaded
                && !present.RefreshTokenLoaded
                && !present.TokenSecretPrinted
                && !present.LocalAgentTokenUsed
                && present.ProductionCryptoPreviewRequirement.Schema == "learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1"
                && !present.ProductionCryptoPreviewRequirement.AutoRunEnabled
                && !present.ProductionCryptoPreviewRequirement.ArtifactWriteEnabled
                && !present.ProductionCryptoPreviewRequirement.StoredSessionLoadingEnabled
                && !present.ProductionCryptoPreviewRequirement.TokenSecretPrinted
                && present.Blockers.Contains("stored web-session artifact validation is disabled until encrypted read/decrypt support is implemented")
                && !json.Contains("secret-web-token", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session artifact validation contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-artifact-validation-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionArtifactWriterPreflightContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-writer-preflight-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var ready = app.BuildCliWebSessionArtifactWriterPreflightResult([
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var missing = app.BuildCliWebSessionArtifactWriterPreflightResult([
                "--approved",
                "--expires-at",
                "not-a-date"
            ]);
            var unsafePlaintext = app.BuildCliWebSessionArtifactWriterPreflightResult([
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z",
                "--allow-plaintext-token-serialization"
            ]);
            var writeRequested = app.BuildCliWebSessionArtifactWriterPreflightResult([
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z",
                "--write"
            ]);
            var json = JsonSerializer.Serialize(new { ready, missing, unsafePlaintext, writeRequested }, JsonOptions);

            var ok = ready.Schema == "learnbot.local-agent.web-session-artifact-writer-preflight-result.v1"
                && ready.Status == "READY_FOR_DISABLED_WRITER"
                && ready.ClaimResultAccepted
                && ready.AccessTokenPresent
                && ready.RefreshTokenPresent
                && ready.ExpiresAtPresent
                && ready.RefreshExpiresAtPresent
                && ready.ExpiryFieldsValid
                && !ready.PlaintextTokenSerializationAllowed
                && !ready.PlaintextTokenSerializationRequested
                && ready.EncryptionRequired
                && ready.EncryptionProvider == "LOCAL_OS_SECRET_STORE_OR_DPAPI"
                && !ready.EncryptionProviderProbeEnabled
                && ready.AtomicReplaceRequired
                && ready.ArtifactWriterPreflightPassed
                && !ready.ArtifactWriteRequested
                && !ready.ArtifactWriterExecutionEnabled
                && !ready.LocalSessionArtifactWritten
                && !ready.LocalAgentTokenUsed
                && !ready.TokenSecretPrinted
                && ready.MissingOrInvalidFields.Count == 0
                && ready.ArtifactBodyPreview.TryGetValue("encryptedAccessToken", out var encryptedAccessToken)
                && string.Equals(encryptedAccessToken?.ToString(), "<encrypted-access-token>", StringComparison.Ordinal)
                && missing.Status == "BLOCKED_PRECONDITION_FAILED"
                && !missing.ArtifactWriterPreflightPassed
                && missing.MissingOrInvalidFields.Contains("accessToken")
                && missing.MissingOrInvalidFields.Contains("refreshToken")
                && missing.MissingOrInvalidFields.Contains("expiresAt")
                && unsafePlaintext.Status == "BLOCKED_PRECONDITION_FAILED"
                && unsafePlaintext.PlaintextTokenSerializationRequested
                && !unsafePlaintext.PlaintextTokenSerializationAllowed
                && unsafePlaintext.Blockers.Contains("plaintext token serialization is not allowed for web-session artifacts.")
                && writeRequested.Status == "BLOCKED_PRECONDITION_FAILED"
                && writeRequested.ArtifactWriteRequested
                && !writeRequested.ArtifactWriterExecutionEnabled
                && !writeRequested.LocalSessionArtifactWritten
                && writeRequested.Blockers.Contains("local web-session artifact writing is still disabled; this command performs preflight only.")
                && !File.Exists(WebSessionPath())
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("secret-access-token", StringComparison.Ordinal)
                && !json.Contains("secret-refresh-token", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session artifact writer preflight contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-artifact-writer-preflight-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionArtifactWriterTestWriteContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-writer-test-write-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var missingOptIn = app.BuildCliWebSessionArtifactWriterTestWriteResult([
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var written = app.BuildCliWebSessionArtifactWriterTestWriteResult([
                "--test-only",
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var artifactText = File.Exists(WebSessionPath()) ? File.ReadAllText(WebSessionPath()) : "";
            using var document = JsonDocument.Parse(artifactText);
            var rootElement = document.RootElement;
            var encryptedAccessToken = rootElement.GetProperty("encryptedAccessToken").GetString();
            var encryptedRefreshToken = rootElement.GetProperty("encryptedRefreshToken").GetString();
            var encryption = rootElement.GetProperty("encryption");
            var json = JsonSerializer.Serialize(new { missingOptIn, written }, JsonOptions);

            var ok = missingOptIn.Schema == "learnbot.local-agent.web-session-artifact-writer-test-write-result.v1"
                && missingOptIn.Status == "BLOCKED_PRECONDITION_FAILED"
                && !missingOptIn.TestOnlyMode
                && !missingOptIn.LocalSessionArtifactWritten
                && missingOptIn.Blockers.Contains("test-only artifact writing requires explicit --test-only.")
                && written.Status == "TEST_ONLY_ARTIFACT_WRITTEN"
                && written.TestOnlyMode
                && written.Preflight.ArtifactWriterPreflightPassed
                && written.ArtifactWriterExecutionEnabled
                && written.LocalSessionArtifactWritten
                && written.AtomicReplaceUsed
                && written.EncryptionProvider == "TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION"
                && !written.PlaintextTokenSerializationAllowed
                && !written.PlaintextTokenSerializationDetected
                && !written.TokenSecretPrinted
                && !written.LocalAgentTokenUsed
                && written.BytesWritten > 0
                && !string.IsNullOrWhiteSpace(written.ArtifactSha256)
                && File.Exists(WebSessionPath())
                && rootElement.GetProperty("schema").GetString() == "learnbot.local-agent.web-session-artifact.v1"
                && rootElement.GetProperty("serverUrl").GetString() == "http://localhost:8083"
                && !string.IsNullOrWhiteSpace(encryptedAccessToken)
                && !string.IsNullOrWhiteSpace(encryptedRefreshToken)
                && encryptedAccessToken!.Split('.').Length == 3
                && encryptedRefreshToken!.Split('.').Length == 3
                && encryption.GetProperty("required").GetBoolean()
                && encryption.GetProperty("provider").GetString() == "TEST_ONLY_AES_GCM_DERIVED_KEY_NOT_FOR_PRODUCTION"
                && !encryption.GetProperty("plaintextTokenSerializationAllowed").GetBoolean()
                && !encryption.GetProperty("keyPersisted").GetBoolean()
                && !artifactText.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !artifactText.Contains("test-only-refresh-token-material", StringComparison.Ordinal)
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session artifact writer test-write contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-artifact-writer-test-write-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static int SelfTestWebSessionArtifactReaderTestValidateContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-web-session-reader-test-validate-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var app = new LearnBotLocalAgent();
            app.SaveConfig(new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "secret-agent-token",
                Version = Version,
                Transport = "auto"
            });

            var missingOptIn = app.BuildCliWebSessionArtifactReaderTestValidateResult([]);
            var written = app.BuildCliWebSessionArtifactWriterTestWriteResult([
                "--test-only",
                "--approved",
                "--access-token-present",
                "--refresh-token-present",
                "--expires-at",
                "2026-07-03T12:00:00Z",
                "--refresh-expires-at",
                "2026-07-04T12:00:00Z"
            ]);
            var validated = app.BuildCliWebSessionArtifactReaderTestValidateResult(["--test-only"]);
            var json = JsonSerializer.Serialize(new { missingOptIn, written, validated }, JsonOptions);

            var ok = missingOptIn.Schema == "learnbot.local-agent.web-session-artifact-reader-test-validate-result.v1"
                && missingOptIn.Status == "BLOCKED_OR_INVALID"
                && !missingOptIn.TestOnlyMode
                && !missingOptIn.ReadAttempted
                && missingOptIn.Blockers.Contains("test-only artifact read/decrypt validation requires explicit --test-only.")
                && written.Status == "TEST_ONLY_ARTIFACT_WRITTEN"
                && validated.Status == "TEST_ONLY_ARTIFACT_DECRYPTED"
                && validated.TestOnlyMode
                && validated.FileExists
                && validated.ReadAttempted
                && validated.JsonParsed
                && validated.SchemaValidated
                && validated.EncryptionProviderAccepted
                && validated.DecryptionAttempted
                && validated.DecryptionSucceeded
                && !string.IsNullOrWhiteSpace(validated.AccessTokenFingerprint)
                && !string.IsNullOrWhiteSpace(validated.RefreshTokenFingerprint)
                && !validated.PlaintextTokenSerializationDetected
                && !validated.TokenSecretPrinted
                && !validated.LocalAgentTokenUsed
                && !validated.ProductionStoredSessionLoaded
                && validated.Blockers.Count == 0
                && !json.Contains("secret-agent-token", StringComparison.Ordinal)
                && !json.Contains("test-only-access-token-material", StringComparison.Ordinal)
                && !json.Contains("test-only-refresh-token-material", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("web session artifact reader test-validate contract self-test failed");
                return 1;
            }
            Console.WriteLine("web-session-artifact-reader-test-validate-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static async Task<int> SelfTestPairAtomicConfigContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-pair-atomic-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var agentRoot = Path.Combine(root, "agent");
            var configPath = Path.Combine(agentRoot, "agent.json");
            Directory.CreateDirectory(agentRoot);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", configPath);

            var app = new LearnBotLocalAgent();
            var failedWithoutExistingConfig = await ExpectPairHeartbeatFailure(app);
            var noConfigWritten = !File.Exists(configPath);

            var existing = new AgentConfig
            {
                ServerUrl = "http://localhost:8083",
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Token = "old-secret-token",
                Version = Version,
                Transport = "auto",
                Workspaces = [new AgentWorkspace(Guid.Parse("33333333-3333-3333-3333-333333333333"), "workspace", root, true)]
            };
            app.SaveConfig(existing);

            var failedWithExistingConfig = await ExpectPairHeartbeatFailure(app);
            var preserved = app.LoadConfigOrDefault();
            var existingConfigPreserved = preserved.ServerUrl == existing.ServerUrl
                && preserved.AgentId == existing.AgentId
                && preserved.Token == existing.Token
                && preserved.Transport == existing.Transport
                && preserved.Workspaces.Count == 1;

            if (!failedWithoutExistingConfig || !noConfigWritten || !failedWithExistingConfig || !existingConfigPreserved)
            {
                Console.Error.WriteLine("pair atomic config contract self-test failed");
                return 1;
            }
            Console.WriteLine("pair-atomic-config-contract-ok");
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
                catch
                {
                    // best effort cleanup
                }
            }
        }
    }

    private static async Task<bool> ExpectPairHeartbeatFailure(LearnBotLocalAgent app)
    {
        try
        {
            _ = await app.Run([
                "pair",
                "--server", "http://127.0.0.1:9",
                "--agent-id", "11111111-1111-1111-1111-111111111111",
                "--token", "new-secret-token",
                "--transport", "polling"
            ]);
            return false;
        }
        catch (HttpRequestException)
        {
            return true;
        }
    }

    private static int SelfTestWorkspaceTreeContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-workspace-tree-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src", "Features"));
            Directory.CreateDirectory(Path.Combine(workspaceRoot, ".git"));
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "node_modules", "pkg"));
            File.WriteAllText(Path.Combine(workspaceRoot, "README.md"), "# Project\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "src", "App.cs"), "class App {}\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "src", "Features", "Feature.cs"), "class Feature {}\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "node_modules", "pkg", "index.js"), "ignored\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent", "agent.json"));
            var config = new AgentConfig
            {
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var agent = new LearnBotLocalAgent();
            var tree = agent.ReadWorkspaceTree(config, workspaceId, ".", 10, 3);
            var limited = agent.ReadWorkspaceTree(config, workspaceId, ".", 2, 3);
            var escaped = agent.ReadWorkspaceTree(config, workspaceId, "..", 10, 3);

            var entries = tree.Output.TryGetValue("entries", out var rawEntries)
                ? rawEntries as List<Dictionary<string, object?>>
                : null;
            var skipped = tree.Output.TryGetValue("skippedDirectories", out var rawSkipped)
                ? rawSkipped as List<string>
                : null;
            var paths = entries?.Select(item => item.TryGetValue("path", out var value) ? value?.ToString() : null).ToHashSet(StringComparer.OrdinalIgnoreCase)
                ?? [];

            var ok = tree.Success
                && entries is not null
                && paths.Contains("README.md")
                && paths.Contains("src")
                && paths.Contains("src/App.cs")
                && paths.Contains("src/Features/Feature.cs")
                && !paths.Contains(".git")
                && !paths.Contains("node_modules")
                && skipped is not null
                && skipped.Contains(".git")
                && skipped.Contains("node_modules")
                && limited.Success
                && limited.Output.TryGetValue("truncated", out var truncated)
                && truncated is true
                && !escaped.Success
                && escaped.FailureCode == "PATH_ESCAPE";
            if (!ok)
            {
                Console.Error.WriteLine(tree.Error ?? limited.Error ?? escaped.Error ?? "workspace tree contract self-test failed");
                return 1;
            }

            Console.WriteLine("workspace-tree-contract-ok");
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

    private static int SelfTestWorkspaceSearchContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-workspace-search-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src", "Features"));
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "node_modules", "pkg"));
            File.WriteAllText(Path.Combine(workspaceRoot, "README.md"), "Search target lives here.\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "src", "App.cs"), "class App {\n    string SearchTarget = \"one\";\n}\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "src", "Features", "Feature.cs"), "class Feature {\n    string searchtarget = \"two\";\n}\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllText(Path.Combine(workspaceRoot, "node_modules", "pkg", "ignored.js"), "SearchTarget should not be returned.\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.WriteAllBytes(Path.Combine(workspaceRoot, "binary.dat"), [0, 1, 2, 3]);

            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent", "agent.json"));
            var config = new AgentConfig
            {
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var agent = new LearnBotLocalAgent();
            var search = agent.SearchWorkspaceText(config, workspaceId, "SearchTarget", ".", 10, 20, 4096);
            var limited = agent.SearchWorkspaceText(config, workspaceId, "SearchTarget", ".", 1, 20, 4096);
            var escaped = agent.SearchWorkspaceText(config, workspaceId, "SearchTarget", "..", 10, 20, 4096);
            var missingQuery = agent.SearchWorkspaceText(config, workspaceId, "", ".", 10, 20, 4096);

            var matches = search.Output.TryGetValue("matches", out var rawMatches)
                ? rawMatches as List<Dictionary<string, object?>>
                : null;
            var paths = matches?.Select(item => item.TryGetValue("path", out var value) ? value?.ToString() : null).ToHashSet(StringComparer.OrdinalIgnoreCase)
                ?? [];

            var ok = search.Success
                && matches is not null
                && paths.Contains("src/App.cs")
                && paths.Contains("src/Features/Feature.cs")
                && !paths.Contains("node_modules/pkg/ignored.js")
                && search.Output.TryGetValue("skippedFiles", out var skippedFiles)
                && skippedFiles is int skipped
                && skipped >= 1
                && limited.Success
                && limited.Output.TryGetValue("matchCount", out var limitedCount)
                && limitedCount is int count
                && count == 1
                && limited.Output.TryGetValue("truncated", out var truncated)
                && truncated is true
                && !escaped.Success
                && escaped.FailureCode == "PATH_ESCAPE"
                && !missingQuery.Success
                && missingQuery.FailureCode == "TOOL_FAILED";
            if (!ok)
            {
                Console.Error.WriteLine(search.Error ?? limited.Error ?? escaped.Error ?? missingQuery.Error ?? "workspace search contract self-test failed");
                return 1;
            }

            Console.WriteLine("workspace-search-contract-ok");
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

    private static int SelfTestNonGitWorkspaceContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-non-git-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var workspaceRoot = Path.Combine(root, "workspace");
            Directory.CreateDirectory(workspaceRoot);
            File.WriteAllText(Path.Combine(workspaceRoot, "README.md"), "# Plain folder\n", new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent", "agent.json"));
            var config = new AgentConfig
            {
                AgentId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };
            var agent = new LearnBotLocalAgent();
            var status = agent.ReadGitStatus(config, workspaceId).GetAwaiter().GetResult();
            var diff = agent.ReadGitDiff(config, workspaceId, null, 4096).GetAwaiter().GetResult();

            var ok = status.Success
                && status.Output.TryGetValue("nonGitWorkspace", out var statusNonGit)
                && statusNonGit is true
                && status.Output.TryGetValue("changes", out var changes)
                && changes is Array statusChanges
                && statusChanges.Length == 0
                && status.Output.TryGetValue("identityComplete", out var identityComplete)
                && identityComplete is false
                && diff.Success
                && diff.Output.TryGetValue("nonGitWorkspace", out var diffNonGit)
                && diffNonGit is true
                && diff.Output.TryGetValue("staged", out var staged)
                && string.Equals(staged?.ToString(), "", StringComparison.Ordinal)
                && diff.Output.TryGetValue("unstaged", out var unstaged)
                && string.Equals(unstaged?.ToString(), "", StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine(status.Error ?? diff.Error ?? "non-git workspace contract self-test failed");
                return 1;
            }

            Console.WriteLine("non-git-workspace-contract-ok");
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

    private static int SelfTestSnapshotCreate()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-self-test-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceRoot = Path.Combine(root, "workspace");
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src"));
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));
            var sourcePath = Path.Combine(workspaceRoot, "src", "App.cs");
            var sourceBytes = Encoding.UTF8.GetBytes("class App {}\n");
            File.WriteAllBytes(sourcePath, sourceBytes);
            var sourceHash = Sha256Hex(sourceBytes);
            using var inputJson = JsonDocument.Parse("""
            {
              "workspaceId": "11111111-1111-1111-1111-111111111111",
              "sourceRequestId": "22222222-2222-2222-2222-222222222222"
            }
            """);
            var files = new List<Dictionary<string, object?>>
            {
                new()
                {
                    ["path"] = "src/App.cs",
                    ["absolutePath"] = sourcePath,
                    ["expectedSha256"] = sourceHash,
                    ["actualSha256"] = sourceHash,
                    ["bytes"] = sourceBytes.LongLength,
                    ["hashMatches"] = true,
                    ["contextMatches"] = true
                }
            };

            var result = CreateSnapshot(Guid.Parse("11111111-1111-1111-1111-111111111111"), workspaceRoot, inputJson.RootElement, files);
            var manifest = result.Manifest;
            var manifestPath = manifest is not null && manifest.TryGetValue("relativeManifestPath", out var relativeManifestPath)
                ? Path.Combine(agentRoot, "snapshots", relativeManifestPath!.ToString()!.Replace('/', Path.DirectorySeparatorChar))
                : "";
            var copiedPath = manifest is not null
                && manifest.TryGetValue("files", out var manifestFiles)
                && manifestFiles is List<Dictionary<string, object?>> list
                && list.Count == 1
                && list[0].TryGetValue("snapshotRelativePath", out var snapshotRelativePath)
                    ? Path.Combine(Path.GetDirectoryName(manifestPath)!, snapshotRelativePath!.ToString()!.Replace('/', Path.DirectorySeparatorChar))
                    : "";

            var ok = result.Created
                && manifest is not null
                && File.Exists(manifestPath)
                && File.Exists(copiedPath)
                && manifest.TryGetValue("created", out var created)
                && created is true
                && manifest.TryGetValue("writesCompleted", out var writesCompleted)
                && writesCompleted is true;
            if (!ok)
            {
                Console.Error.WriteLine(result.Error ?? "snapshot create self-test failed");
                return 1;
            }
            Console.WriteLine("snapshot-create-ok");
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

    private static int SelfTestPatchApplyMemory()
    {
        var parsed = ParseUnifiedDiff("""
        --- a/src/App.cs
        +++ b/src/App.cs
        @@ -1,3 +1,4 @@
         class App {
        -    string Name = "old";
        +    string Name = "new";
        +    string Mode = "safe";
         }
        """);
        var mismatch = ParseUnifiedDiff("""
        --- a/src/App.cs
        +++ b/src/App.cs
        @@ -1,2 +1,2 @@
         class App {
        -    string Name = "missing";
        +    string Name = "new";
        """);
        var ok = parsed.Success
            && parsed.Files.Count == 1
            && TryApplyPatchToLines(
                ["class App {", "    string Name = \"old\";", "}"],
                parsed.Files[0],
                out var updated,
                out var error)
            && error is null
            && updated.SequenceEqual(["class App {", "    string Name = \"new\";", "    string Mode = \"safe\";", "}"])
            && mismatch.Success
            && mismatch.Files.Count == 1
            && !TryApplyPatchToLines(
                ["class App {", "    string Name = \"old\";"],
                mismatch.Files[0],
                out _,
                out var mismatchError)
            && !string.IsNullOrWhiteSpace(mismatchError);
        if (!ok)
        {
            Console.Error.WriteLine("patch apply memory self-test failed");
            return 1;
        }
        Console.WriteLine("patch-apply-memory-ok");
        return 0;
    }

    private static int SelfTestPatchDryRunContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-patch-dry-run-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var workspaceRoot = Path.Combine(root, "workspace");
            var agentRoot = Path.Combine(root, "agent");
            Directory.CreateDirectory(Path.Combine(workspaceRoot, "src"));
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(agentRoot, "agent.json"));

            var targetPath = Path.Combine(workspaceRoot, "src", "App.cs");
            var original = "class App {\n    string Name = \"old\";\n}\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var originalHash = Sha256Hex(File.ReadAllBytes(targetPath));
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
            using var requestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["workspaceId"] = workspaceId,
                ["input"] = new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceId,
                    ["sourceRequestId"] = "22222222-2222-2222-2222-222222222222",
                    ["dryRunOnly"] = true,
                    ["mutationAllowed"] = false,
                    ["diff"] = diff,
                    ["targetFiles"] = new[] { "src/App.cs" },
                    ["expectedFiles"] = new[]
                    {
                        new Dictionary<string, object?>
                        {
                            ["path"] = "src/App.cs",
                            ["sha256"] = originalHash
                        }
                    }
                }
            }, JsonOptions));
            var config = new AgentConfig
            {
                AgentId = Guid.Parse("33333333-3333-3333-3333-333333333333"),
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };

            var result = new LearnBotLocalAgent().DryRunPatchApply(config, workspaceId, requestJson.RootElement);
            var mismatchDiff = """
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,4 +1,5 @@
             class App {
            -    string Missing = "old";
            +    string Missing = "new";
             }
            """;
            using var mismatchRequestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["workspaceId"] = workspaceId,
                ["input"] = new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceId,
                    ["sourceRequestId"] = "22222222-2222-2222-2222-222222222222",
                    ["dryRunOnly"] = true,
                    ["mutationAllowed"] = false,
                    ["diff"] = mismatchDiff,
                    ["targetFiles"] = new[] { "src/App.cs" },
                    ["expectedFiles"] = new[]
                    {
                        new Dictionary<string, object?>
                        {
                            ["path"] = "src/App.cs",
                            ["sha256"] = originalHash
                        }
                    }
                }
            }, JsonOptions));
            var mismatchResult = new LearnBotLocalAgent().DryRunPatchApply(config, workspaceId, mismatchRequestJson.RootElement);
            var ok = !result.Success
                && result.Status == "REJECTED"
                && result.FailureCode == "UNSAFE_TOOL"
                && File.ReadAllText(targetPath, Encoding.UTF8) == original
                && result.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is false
                && result.Output.TryGetValue("snapshotCreated", out var snapshotCreated)
                && snapshotCreated is true
                && result.Output.TryGetValue("preflightPassed", out var preflightPassed)
                && preflightPassed is true
                && result.Output.TryGetValue("snapshotObservation", out var snapshotObservation)
                && snapshotObservation is Dictionary<string, object?> snapshot
                && snapshot.TryGetValue("created", out var snapshotObservationCreated)
                && snapshotObservationCreated is true
                && snapshot.TryGetValue("manifestPreview", out var manifestPreview)
                && manifestPreview is Dictionary<string, object?> manifest
                && manifest.TryGetValue("created", out var manifestCreated)
                && manifestCreated is true
                && manifest.TryGetValue("writesCompleted", out var writesCompleted)
                && writesCompleted is true
                && result.Output.TryGetValue("rollbackObservation", out var rollbackObservation)
                && rollbackObservation is Dictionary<string, object?> rollback
                && rollback.TryGetValue("restored", out var restored)
                && restored is false
                && rollback.TryGetValue("requiresUserApproval", out var requiresUserApproval)
                && requiresUserApproval is true
                && !mismatchResult.Success
                && mismatchResult.Status == "REJECTED"
                && mismatchResult.FailureCode == "CONTEXT_MISMATCH"
                && mismatchResult.Output.TryGetValue("preflightPassed", out var mismatchPreflightPassed)
                && mismatchPreflightPassed is false
                && File.ReadAllText(targetPath, Encoding.UTF8) == original;
            if (!ok)
            {
                Console.Error.WriteLine("patch dry-run contract self-test failed");
                return 1;
            }
            Console.WriteLine("patch-dry-run-contract-ok");
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

    private static int SelfTestToolResponseContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-tool-response-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            var workspaceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            var sourceRequestId = "22222222-2222-2222-2222-222222222222";
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
            using var requestJson = JsonDocument.Parse(JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["sessionId"] = sessionId,
                ["userId"] = userId,
                ["agentId"] = agentId,
                ["workspaceId"] = workspaceId,
                ["executionTarget"] = "USER_LOCAL_AGENT",
                ["toolName"] = "patch.apply",
                ["input"] = new Dictionary<string, object?>
                {
                    ["workspaceId"] = workspaceId,
                    ["sourceRequestId"] = sourceRequestId,
                    ["dryRunOnly"] = true,
                    ["mutationAllowed"] = false,
                    ["diff"] = diff,
                    ["targetFiles"] = new[] { "src/App.cs" },
                    ["expectedFiles"] = new[]
                    {
                        new Dictionary<string, object?>
                        {
                            ["path"] = "src/App.cs",
                            ["sha256"] = originalHash
                        }
                    }
                }
            }, JsonOptions));
            var config = new AgentConfig
            {
                AgentId = agentId,
                Workspaces = [new AgentWorkspace(workspaceId, "workspace", workspaceRoot, true)]
            };

            var response = new LearnBotLocalAgent().HandleTool(config, requestId, requestJson.RootElement, "patch.apply");
            var ok = response.SessionId == sessionId
                && response.RequestId == requestId
                && response.UserId == userId
                && response.AgentId == agentId
                && response.WorkspaceId == workspaceId
                && response.ExecutionTarget == "USER_LOCAL_AGENT"
                && response.ToolName == "patch.apply"
                && response.Status == "REJECTED"
                && response.FailureCode == "UNSAFE_TOOL"
                && File.ReadAllText(targetPath, Encoding.UTF8) == original
                && response.Output.TryGetValue("dryRun", out var dryRun)
                && dryRun is true
                && response.Output.TryGetValue("mutationApplied", out var mutationApplied)
                && mutationApplied is false
                && response.Output.TryGetValue("snapshotCreated", out var snapshotCreated)
                && snapshotCreated is true
                && response.Output.TryGetValue("snapshotObservation", out var snapshotObservation)
                && snapshotObservation is Dictionary<string, object?> snapshot
                && snapshot.TryGetValue("manifestPreview", out var manifestPreview)
                && manifestPreview is Dictionary<string, object?> manifest
                && manifest.TryGetValue("sourceRequestId", out var observedSourceRequestId)
                && string.Equals(observedSourceRequestId?.ToString(), sourceRequestId, StringComparison.Ordinal);
            if (!ok)
            {
                Console.Error.WriteLine("tool response contract self-test failed");
                return 1;
            }
            Console.WriteLine("tool-response-contract-ok");
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

    private static int SelfTestPatchWriteSequence()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-patch-write-" + Guid.NewGuid().ToString("N"));
        try
        {
            var workspaceRoot = Path.Combine(root, "workspace");
            var srcRoot = Path.Combine(workspaceRoot, "src");
            Directory.CreateDirectory(srcRoot);
            var targetPath = Path.Combine(srcRoot, "App.cs");
            var original = "class App {\r\n    string Name = \"old\";\r\n}\r\n";
            File.WriteAllText(targetPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            var originalHash = Sha256Hex(File.ReadAllBytes(targetPath));
            var parsed = ParseUnifiedDiff("""
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,3 +1,4 @@
             class App {
            -    string Name = "old";
            +    string Name = "new";
            +    string Mode = "safe";
             }
            """);
            var mismatch = ParseUnifiedDiff("""
            --- a/src/App.cs
            +++ b/src/App.cs
            @@ -1,2 +1,2 @@
             class App {
            -    string Name = "missing";
            +    string Name = "new";
            """);
            var escapedPath = Path.Combine(root, "escape.cs");
            File.WriteAllText(escapedPath, original, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            var writeResult = parsed.Success && parsed.Files.Count == 1
                ? TryWritePatchedFileWithRecheck(workspaceRoot, targetPath, parsed.Files[0], originalHash)
                : PatchWriteSequenceResult.Failed("parse failed");
            var updated = File.ReadAllText(targetPath);
            var unchangedHash = Sha256Hex(File.ReadAllBytes(targetPath));
            var mismatchResult = mismatch.Success && mismatch.Files.Count == 1
                ? TryWritePatchedFileWithRecheck(workspaceRoot, targetPath, mismatch.Files[0], unchangedHash)
                : PatchWriteSequenceResult.Failed("mismatch parse failed");
            var afterMismatch = File.ReadAllText(targetPath);
            var staleHashResult = parsed.Success && parsed.Files.Count == 1
                ? TryWritePatchedFileWithRecheck(workspaceRoot, targetPath, parsed.Files[0], originalHash)
                : PatchWriteSequenceResult.Failed("stale parse failed");
            var escapeResult = parsed.Success && parsed.Files.Count == 1
                ? TryWritePatchedFileWithRecheck(workspaceRoot, escapedPath, parsed.Files[0], Sha256Hex(File.ReadAllBytes(escapedPath)))
                : PatchWriteSequenceResult.Failed("escape parse failed");

            var ok = writeResult.Success
                && string.Equals(writeResult.BeforeSha256, originalHash, StringComparison.OrdinalIgnoreCase)
                && !string.Equals(writeResult.BeforeSha256, writeResult.AfterSha256, StringComparison.OrdinalIgnoreCase)
                && writeResult.LineEnding == "\r\n"
                && updated == "class App {\r\n    string Name = \"new\";\r\n    string Mode = \"safe\";\r\n}\r\n"
                && !mismatchResult.Success
                && afterMismatch == updated
                && !staleHashResult.Success
                && !escapeResult.Success;
            if (!ok)
            {
                Console.Error.WriteLine("patch write sequence self-test failed");
                return 1;
            }
            Console.WriteLine("patch-write-sequence-ok");
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

    private static int Help()
    {
        Console.WriteLine("""
        learnbot pair --server http://localhost:8083 [--workspace <path>] [--transport polling|websocket|auto]
        learnbot pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> [--transport polling|websocket|auto]
        learnbot status
        learnbot doctor
        learnbot m8 status
        learnbot m8 doctor
        learnbot login [--server <url>] [--login-id <id>] [--json]
        learnbot login --browser [--server <url>] [--no-open]
        learnbot login --plan [--login-id <login-id>|--email <email>] [--remember]
        learnbot session status
        learnbot session plan [--server <url>] [--offline]
        learnbot session create-plan [--server <url>] [--offline]
        learnbot session claim-plan [--server <url>] [--device-code <device-code>] [--offline]
        learnbot session claim-result-plan [--server <url>] [--claim-status <status>] [--offline]
        learnbot session artifact-writer-preflight --approved --access-token-present --refresh-token-present --expires-at <iso> --refresh-expires-at <iso>
        learnbot session artifact-writer-test-write --test-only --approved --access-token-present --refresh-token-present --expires-at <iso> --refresh-expires-at <iso>
        learnbot session artifact-reader-test-validate --test-only
        learnbot session artifact-production-crypto-preview --preview-only
        learnbot session artifact-production-writer-preview --preview-only --approved --access-token-present --refresh-token-present --expires-at <iso> --refresh-expires-at <iso>
        learnbot session artifact-production-reader-preview --preview-only
        learnbot session stored-session-auth-readiness
        learnbot session secret-provider-plan
        learnbot session secret-provider-probe
        learnbot session server-plan-readiness
        learnbot agent start [--once] [--interval-seconds 15] [--transport polling|websocket|auto] [--config <path>]
        learnbot agent status
        learnbot agent token
        learnbot agent stop
        learnbot agent logs [--tail 80]
        learnbot service run [--interval-seconds 15] [--transport polling|websocket|auto] [--config <path>]
        learnbot workspace add <path>
        learnbot workspace list
        learnbot file tree --workspace-id <workspace-id> [--path <relative-path>] [--max-entries <count>] [--max-depth <depth>]
        learnbot file search --workspace-id <workspace-id> --query <text> [--path <relative-path>] [--max-matches <count>] [--max-files <count>]
        learnbot file read --workspace-id <workspace-id> --path <relative-path>
        learnbot git status --workspace-id <workspace-id>
        learnbot git diff --workspace-id <workspace-id> [--path <relative-path>] [--max-bytes <bytes>]
        learnbot fix "<goal>" [--workspace <path>] [--json|--preview]
        learnbot review "<goal>" [--workspace <path>] [--json|--preview]
        learnbot open
        """);
        return 0;
    }

    private static int Unknown(string command)
    {
        Console.Error.WriteLine($"Unknown command: {command}");
        return 2;
    }
}

internal sealed class AgentConfig
{
    public string? ServerUrl { get; set; } = "http://localhost:8083";
    public Guid AgentId { get; set; }
    public string? Token { get; set; }
    public string Version { get; set; } = "0.1.0";
    public string Transport { get; set; } = "polling";
    public List<AgentWorkspace> Workspaces { get; set; } = [];
}

internal sealed record AgentWorkspace(Guid WorkspaceId, string Name, string Path, bool Approved);

internal sealed record StoredWebSession(
    string? ServerUrl,
    string AccessToken,
    string? RefreshToken,
    DateTimeOffset? ExpiresAt,
    DateTimeOffset? RefreshExpiresAt);

internal sealed record AgentRunState(
    string Status,
    int ProcessId,
    DateTimeOffset StartedAt,
    DateTimeOffset UpdatedAt,
    string? LastEvent,
    string LogPath,
    string? ConfiguredTransport,
    string? ActiveTransport,
    int WebSocketFailureCount,
    DateTimeOffset? NextWebSocketRetryAt);

internal sealed record WorkspaceResolution(
    bool Success,
    string Status,
    string? FailureCode,
    string? Error,
    AgentWorkspace? Workspace,
    string? Root)
{
    public static WorkspaceResolution Ok(AgentWorkspace workspace, string root) => new(true, "SUCCEEDED", null, null, workspace, root);

    public static WorkspaceResolution Fail(string status, string failureCode, string error) => new(false, status, failureCode, error, null, null);
}

internal sealed record ToolResult(
    bool Success,
    string Status,
    string? FailureCode,
    string? Error,
    Dictionary<string, object?> Output)
{
    public static ToolResult Ok(Dictionary<string, object?> output) => new(true, "SUCCEEDED", null, null, output);

    public static ToolResult Fail(string status, string failureCode, string error) => new(false, status, failureCode, error, new());

    public static ToolResult Fail(string status, string failureCode, string error, Dictionary<string, object?> output) => new(false, status, failureCode, error, output);
}

internal sealed record ExpectedFile(string Path, string Sha256);

internal sealed record GitIdentityValue(string? Value, string? Warning);

internal sealed record CliCodexPrepareResult(
    bool Success,
    string Message,
    CliCodexCommandPreviewReport? Preview,
    CliCodexServerPlanFetchResult? Result);

internal sealed record CliLocalRepositoryRef(Guid Id);

internal sealed record CliLocalGitIdentity(string? Branch, string? HeadCommit, string? RemoteUrl);

internal sealed record SnapshotCreationResult(bool Created, string? Error, Dictionary<string, object?>? Manifest)
{
    public static SnapshotCreationResult Succeeded(Dictionary<string, object?> manifest) => new(true, null, manifest);

    public static SnapshotCreationResult Failed(string error) => new(false, error, null);
}

internal sealed record SnapshotLayout(
    string ManifestId,
    string SnapshotsRoot,
    string SnapshotRoot,
    string RelativeManifestPath,
    string ManifestPath,
    List<SnapshotFilePath> Files);

internal sealed record SnapshotFilePath(string Path, string SnapshotRelativePath, string DestinationPath);

internal sealed record PatchLine(char Marker, string Text);

internal sealed record PatchWriteSequenceResult(
    bool Success,
    string? Error,
    string? BeforeSha256,
    string? AfterSha256,
    long? BeforeBytes,
    long? AfterBytes,
    string? LineEnding)
{
    public static PatchWriteSequenceResult Succeeded(
        string beforeSha256,
        string afterSha256,
        long beforeBytes,
        long afterBytes,
        string lineEnding) => new(true, null, beforeSha256, afterSha256, beforeBytes, afterBytes, lineEnding);

    public static PatchWriteSequenceResult Failed(string error) => new(false, error, null, null, null, null, null);
}

internal sealed class PatchHunk
{
    public PatchHunk(int oldStart, int oldCount)
    {
        OldStart = oldStart;
        OldCount = oldCount;
    }

    public int OldStart { get; }
    public int OldCount { get; }
    public List<PatchLine> Lines { get; } = [];
}

internal sealed class PatchFile
{
    public PatchFile(string path)
    {
        Path = path;
    }

    public string Path { get; }
    public List<PatchHunk> Hunks { get; } = [];
}

internal sealed record PatchParseResult(bool Success, List<PatchFile> Files, string? Error)
{
    public static PatchParseResult Ok(List<PatchFile> files) => new(true, files, null);

    public static PatchParseResult Fail(string error) => new(false, [], error);
}

internal sealed record HunkDryRunResult(
    int OldStart,
    int OldLineCount,
    bool ContextMatches,
    string Message);

internal sealed record ToolResponse(
    Guid SessionId,
    Guid RequestId,
    Guid UserId,
    Guid AgentId,
    Guid? WorkspaceId,
    string ExecutionTarget,
    string ToolName,
    string Status,
    Dictionary<string, object?> Output,
    string? FailureCode,
    string? Error,
    DateTimeOffset StartedAt,
    DateTimeOffset FinishedAt,
    string[] Warnings);
