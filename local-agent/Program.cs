using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

var app = new LearnBotLocalAgent();
return await app.Run(args);

internal sealed class LearnBotLocalAgent
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
            "doctor" => Doctor(),
            "open" => Open(),
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
        config.ServerUrl = server.TrimEnd('/');
        config.AgentId = parsedAgentId;
        config.Token = token;
        config.Version = Version;
        config.Transport = transport;
        SaveConfig(config);
        await SendHeartbeat(config);
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
                        await SendHeartbeat(config);
                        if (transport == "polling")
                        {
                            activeTransport = "polling";
                            WriteRunState("running", "heartbeat", transport, activeTransport, webSocketFailures, nextWebSocketRetryAt);
                        }
                        else if (shouldTryWebSocket)
                        {
                            webSocketFailures++;
                            var retryDelay = WebSocketRetryDelay(webSocketFailures);
                            nextWebSocketRetryAt = DateTimeOffset.UtcNow.Add(retryDelay);
                            activeTransport = "polling-fallback";
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
        var config = LoadConfigOrDefault();
        var state = LoadRunState();
        Console.WriteLine(JsonSerializer.Serialize(new
        {
            configured = !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty,
            config.ServerUrl,
            config.AgentId,
            config.Version,
            transport = NormalizeTransport(config.Transport),
            workspaces = config.Workspaces.Count,
            logPath = LogPath(),
            statePath = StatePath(),
            running = state is not null && state.Status == "running" && IsProcessRunning(state.ProcessId),
            state
        }, JsonOptions));
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
        var config = LoadConfigOrDefault();
        Console.WriteLine(JsonSerializer.Serialize(new
        {
            version = Version,
            configPath = ConfigPath(),
            logPath = LogPath(),
            statePath = StatePath(),
            paired = !string.IsNullOrWhiteSpace(config.Token) && config.AgentId != Guid.Empty,
            serverUrl = config.ServerUrl,
            transport = NormalizeTransport(config.Transport),
            workspaceCount = config.Workspaces.Count,
            safeMode = "Only read-only tools are handled. File mutation tools are rejected."
        }, JsonOptions));
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

    private async Task SendHeartbeat(AgentConfig config)
    {
        using var client = Client(config);
        using var response = await client.PostAsync("/api/local-agents/heartbeat", Json(HeartbeatPayload(config)));
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
            await SendWebSocketEnvelope(socket, config.AgentId, "hello", null, HeartbeatPayload(config), timeout.Token);
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

    private static object HeartbeatPayload(AgentConfig config) => new
    {
        agentId = config.AgentId,
        version = config.Version,
        capabilities = new[] { "agent.status", "agent.doctor", "workspace.list", "file.read", "git.status", "git.diff" },
        workspaces = config.Workspaces.Select(workspace => new
        {
            workspace.WorkspaceId,
            workspace.Name,
            rootPath = workspace.Path,
            workspace.Approved
        })
    };

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
            default:
                status = "REJECTED";
                failureCode = "UNSAFE_TOOL";
                error = "This Local Agent skeleton rejects file, git, command, patch, and rollback tools by default.";
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

    private static int Help()
    {
        Console.WriteLine("""
        learnbot pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> [--transport polling|websocket|auto]
        learnbot agent start [--once] [--interval-seconds 15] [--transport polling|websocket|auto]
        learnbot agent status
        learnbot agent token
        learnbot agent stop
        learnbot agent logs [--tail 80]
        learnbot workspace add <path>
        learnbot workspace list
        learnbot file read --workspace-id <workspace-id> --path <relative-path>
        learnbot git status --workspace-id <workspace-id>
        learnbot git diff --workspace-id <workspace-id> [--path <relative-path>] [--max-bytes <bytes>]
        learnbot doctor
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
}

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
