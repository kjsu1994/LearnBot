using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

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

        return args[0].ToLowerInvariant() switch
        {
            "pair" => await Pair(args[1..]),
            "agent" => await Agent(args[1..]),
            "workspace" => await Workspace(args[1..]),
            "file" => FileCommand(args[1..]),
            "git" => await GitCommand(args[1..]),
            "status" => AgentStatus(),
            "doctor" => Doctor(),
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
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(agentId) || !Guid.TryParse(agentId, out var parsedAgentId))
        {
            Console.Error.WriteLine("Usage: learnbot pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> [--transport polling|websocket|auto]");
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

    private async Task<int> AgentStart(string[] args)
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
                await Task.Delay(TimeSpan.FromSeconds(intervalSeconds));
            } while (true);
        }
        finally
        {
            WriteRunState(finalStatus, finalEvent, transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
            Log(once ? "agent stopped after one poll" : "agent stopped");
        }

        return 0;
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

    private async Task PollOnce(AgentConfig config)
    {
        using var client = Client(config);
        using var response = await client.GetAsync("/api/local-agents/tools/next");
        if (response.StatusCode == System.Net.HttpStatusCode.NoContent)
        {
            Log("poll no tool request");
            Console.WriteLine("no tool request");
            return;
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
        Console.WriteLine($"{toolName}: {result.Status}");
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
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Workspace is not a Git worktree root.");
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
            return ToolResult.Fail("FAILED", "TOOL_FAILED", "Workspace is not a Git worktree root.");
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

            if (!hashMatches && !contextMatches)
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
    }

    private static StringContent Json(object value) =>
        new(JsonSerializer.Serialize(value, JsonOptions), Encoding.UTF8, "application/json");

    private static string? GetOption(string[] args, string name)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
        }
        return null;
    }

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
                && requiresUserApproval is true;
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
        learnbot pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> [--transport polling|websocket|auto]
        learnbot status
        learnbot doctor
        learnbot agent start [--once] [--interval-seconds 15] [--transport polling|websocket|auto]
        learnbot agent status
        learnbot agent token
        learnbot agent stop
        learnbot agent logs [--tail 80]
        learnbot workspace add <path>
        learnbot workspace list
        learnbot file tree --workspace-id <workspace-id> [--path <relative-path>] [--max-entries <count>] [--max-depth <depth>]
        learnbot file search --workspace-id <workspace-id> --query <text> [--path <relative-path>] [--max-matches <count>] [--max-files <count>]
        learnbot file read --workspace-id <workspace-id> --path <relative-path>
        learnbot git status --workspace-id <workspace-id>
        learnbot git diff --workspace-id <workspace-id> [--path <relative-path>] [--max-bytes <bytes>]
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
