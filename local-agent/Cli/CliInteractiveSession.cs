using System.Net.Http.Headers;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private Guid? _cliChatConversationId;
    private Guid? _cliChatParentTurnId;
    private List<string> _cliAdviceFixGoals = [];

    private async Task<int> CliChat(string[] args)
    {
        var workspace = GetOption(args, "--workspace") ?? Environment.CurrentDirectory;
        var inheritedArgs = BuildCliChatInheritedArgs(args, workspace);
        Console.WriteLine("LearnBot Agent CLI");
        Console.WriteLine("작업을 자연어로 입력하세요. LLM이 답변/검토/수정/컨텍스트 읽기 여부를 판단합니다. /help, /exit 사용 가능");
        Console.WriteLine("workspace: " + ResolveCommandWorkspaceRoot(workspace));

        while (true)
        {
            Console.Write("learnbot> ");
            var line = Console.ReadLine();
            if (line is null)
            {
                return 0;
            }

            line = line.Trim();
            if (line.Length == 0)
            {
                continue;
            }
            if (TryResolveAdviceSelection(line, out var selectedAdviceGoal))
            {
                await RunCliInteractiveTurn("fix", selectedAdviceGoal, inheritedArgs, previewOnly: false);
                continue;
            }

            var directive = ParseCliChatDirective(line);
            switch (directive.Command)
            {
                case "exit":
                    return 0;
                case "help":
                    PrintCliChatHelp();
                    continue;
                case "clear":
                    _cliChatConversationId = null;
                    _cliChatParentTurnId = null;
                    Console.WriteLine("대화 세션 컨텍스트를 초기화했습니다.");
                    continue;
                case "context":
                    await PrintCliSessionContext(inheritedArgs);
                    continue;
                case "status":
                    AgentStatus();
                    continue;
                case "doctor":
                    Doctor();
                    continue;
                case "open":
                    Open();
                    continue;
                case "review":
                    await RunCliInteractiveTurn("review", directive.Goal, inheritedArgs, previewOnly: false);
                    continue;
                case "fix":
                    await RunCliInteractiveTurn("fix", directive.Goal, inheritedArgs, previewOnly: false);
                    continue;
                case "preview":
                    await RunCliInteractiveTurn("fix", directive.Goal, inheritedArgs, previewOnly: true);
                    continue;
                default:
                    await RunCliInteractiveTurn(null, directive.Goal, inheritedArgs, previewOnly: false);
                    continue;
            }
        }
    }

    private async Task<int> RunCliInteractiveTurn(string? intentHint, string goal, string[] inheritedArgs, bool previewOnly)
    {
        if (string.IsNullOrWhiteSpace(goal))
        {
            Console.Error.WriteLine("요청 내용을 입력하세요.");
            return 2;
        }

        var workspace = GetOption(inheritedArgs, "--workspace") ?? Environment.CurrentDirectory;
        var repositoryId = GetOption(inheritedArgs, "--repository-id");
        var spaceId = GetOption(inheritedArgs, "--space-id");
        var maxSteps = Math.Clamp(ParseInt(GetOption(inheritedArgs, "--max-steps"), 6), 1, 20);
        var webToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN")
            ?? await ReadStoredWebAccessTokenWithRefresh(LoadConfigOrDefault().ServerUrl);
        if (string.IsNullOrWhiteSpace(webToken))
        {
            Console.Error.WriteLine("로그인이 필요합니다. 먼저 실행하세요: learnbot login");
            return 1;
        }

        var prepared = await PrepareCodexServerRun("fix", goal, workspace, repositoryId, spaceId, maxSteps, webToken);
        if (!prepared.Success && IsUnauthorizedFailure(prepared.Message))
        {
            var refreshedToken = await ForceRefreshStoredWebAccessToken(LoadConfigOrDefault().ServerUrl);
            if (!string.IsNullOrWhiteSpace(refreshedToken) && !string.Equals(refreshedToken, webToken, StringComparison.Ordinal))
            {
                webToken = refreshedToken;
                prepared = await PrepareCodexServerRun("fix", goal, workspace, repositoryId, spaceId, maxSteps, webToken);
            }
        }
        if (!prepared.Success || prepared.Preview?.ServerSubmissionPlan.RepositoryId is not Guid resolvedRepositoryId)
        {
            Console.Error.WriteLine(prepared.Message);
            return 1;
        }

        CliInteractiveTurnResult result;
        try
        {
            result = await FetchCliInteractiveTurn(
                webToken,
                resolvedRepositoryId,
                prepared.Preview.ServerSubmissionPlan.SpaceId,
                prepared.Preview.ServerSubmissionPlan.AgentId,
                prepared.Preview.ServerSubmissionPlan.WorkspaceId,
                goal,
                intentHint,
                maxSteps);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            Console.Error.WriteLine("대화형 세션 판단에 실패했습니다. 파일 변경은 실행하지 않았습니다. " + ex.Message);
            return 1;
        }

        if (result.ConversationId is not null)
        {
            _cliChatConversationId = result.ConversationId;
        }
        if (result.TurnId is not null)
        {
            _cliChatParentTurnId = result.TurnId;
        }

        if (!string.IsNullOrWhiteSpace(result.Answer)
            && !string.Equals(result.Intent, "ADVISE", StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(result.Answer);
        }

        if (string.Equals(result.Intent, "ADVISE", StringComparison.OrdinalIgnoreCase))
        {
            return await ExecuteCliAdviceContextRead(
                webToken,
                resolvedRepositoryId,
                prepared.Preview.ServerSubmissionPlan.AgentId,
                prepared.Preview.ServerSubmissionPlan.WorkspaceId,
                result);
        }

        if (string.Equals(result.Intent, "READ_CONTEXT", StringComparison.OrdinalIgnoreCase))
        {
            return await ExecuteCliContextRead(
                webToken,
                resolvedRepositoryId,
                prepared.Preview.ServerSubmissionPlan.AgentId,
                prepared.Preview.ServerSubmissionPlan.WorkspaceId,
                result);
        }

        if (!result.ShouldRunCommand || string.IsNullOrWhiteSpace(result.Command))
        {
            return 0;
        }

        var command = string.Equals(result.Command, "review", StringComparison.OrdinalIgnoreCase) ? "review" : "fix";
        var commandGoal = string.IsNullOrWhiteSpace(result.Goal) ? goal : result.Goal;
        return await CodexCommandPreview(command, BuildCliChatCommandArgs(inheritedArgs, commandGoal, previewOnly));
    }

    private async Task<CliInteractiveTurnResult> FetchCliInteractiveTurn(
        string webToken,
        Guid repositoryId,
        Guid? spaceId,
        Guid? agentId,
        Guid? workspaceId,
        string message,
        string? intentHint,
        int maxSteps)
    {
        var server = (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        using var client = new HttpClient
        {
            BaseAddress = new Uri(server),
            Timeout = TimeSpan.FromMinutes(2)
        };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));

        using var response = await client.PostAsync("/api/code-agent/interactive/turns", Json(new
        {
            repositoryId,
            spaceId,
            conversationId = _cliChatConversationId,
            parentTurnId = _cliChatParentTurnId,
            message,
            intentHint,
            maxSteps,
            agentId,
            workspaceId
        }));
        var body = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException("HTTP " + (int)response.StatusCode + " " + body);
        }
        using var document = JsonDocument.Parse(body);
        var root = document.RootElement;
        return new CliInteractiveTurnResult(
            TryGetGuid(root, "conversationId"),
            TryGetGuid(root, "turnId"),
            TryGetString(root, "intent"),
            TryGetString(root, "command"),
            TryGetString(root, "goal"),
            TryGetString(root, "answer"),
            TryGetBool(root, "shouldRunCommand").GetValueOrDefault(false),
            TryGetBool(root, "contextRequired").GetValueOrDefault(false),
            TryGetStringArray(root, "targetFiles"),
            TryGetToolPlan(root, "toolPlan")
        );
    }

    private async Task<int> ExecuteCliAdviceContextRead(
        string webToken,
        Guid repositoryId,
        Guid? agentId,
        Guid? workspaceId,
        CliInteractiveTurnResult result)
    {
        if (!result.ContextRequired || (result.TargetFiles.Count == 0 && result.ToolPlan.Count == 0))
        {
            return 0;
        }
        if (workspaceId is not Guid resolvedWorkspaceId || result.ConversationId is not Guid conversationId || result.TurnId is not Guid turnId)
        {
            Console.Error.WriteLine("ADVISE requires workspace/conversation context.");
            return 1;
        }

        var config = LoadConfigOrDefault();
        var targetFiles = result.TargetFiles;
        var toolPlan = result.ToolPlan;
        for (var round = 0; round < 3; round++)
        {
            var files = new List<Dictionary<string, object?>>();
            var toolResults = new List<Dictionary<string, object?>>();
            var warnings = new List<string>();
            foreach (var step in BuildContextReadSteps(toolPlan, targetFiles).Take(12))
            {
                var response = ExecuteCliReadOnlyStep(config, resolvedWorkspaceId, step);
                toolResults.Add(ToCliToolResult(step, response));
                if (string.Equals(step.Tool, "file.read", StringComparison.OrdinalIgnoreCase))
                {
                    files.Add(ToCliContextReadFile(Convert.ToString(step.Input.GetValueOrDefault("path")) ?? "", response));
                }
                if (response.Status != "SUCCEEDED")
                {
                    warnings.Add(step.Tool + ": " + (response.Error ?? response.FailureCode ?? response.Status));
                }
            }

            var post = await PostCliContextReadResult(webToken, repositoryId, conversationId, turnId, agentId, workspaceId, files, toolResults, warnings);
            if (!string.IsNullOrWhiteSpace(post.Answer))
            {
                Console.WriteLine(post.Answer);
            }
            if (post.AdviceFixGoals.Count > 0)
            {
                _cliAdviceFixGoals = post.AdviceFixGoals.ToList();
            }
            if (!post.ContextRequired)
            {
                return warnings.Count == 0 ? 0 : 1;
            }
            targetFiles = post.TargetFiles;
            toolPlan = post.ToolPlan;
            if (targetFiles.Count == 0 && toolPlan.Count == 0)
            {
                return 1;
            }
        }
        Console.Error.WriteLine("ADVISE context loop exceeded the safe round limit.");
        return 1;
    }

    private async Task<int> ExecuteCliContextRead(
        string webToken,
        Guid repositoryId,
        Guid? agentId,
        Guid? workspaceId,
        CliInteractiveTurnResult result)
    {
        if (!result.ContextRequired || result.TargetFiles.Count == 0)
        {
            return 0;
        }
        if (workspaceId is not Guid resolvedWorkspaceId || result.ConversationId is not Guid conversationId || result.TurnId is not Guid turnId)
        {
            Console.Error.WriteLine("컨텍스트 읽기에 필요한 workspace/conversation 정보가 부족합니다.");
            return 1;
        }

        var config = LoadConfigOrDefault();
        var files = new List<Dictionary<string, object?>>();
        var toolResults = new List<Dictionary<string, object?>>();
        var warnings = new List<string>();
        foreach (var path in result.TargetFiles.Take(8))
        {
            var request = BuildToolRequest(resolvedWorkspaceId, new Dictionary<string, object?>
            {
                ["path"] = path,
                ["maxBytes"] = DefaultMaxReadBytes
            });
            var response = HandleTool(config, Guid.NewGuid(), request, "file.read");
            toolResults.Add(new Dictionary<string, object?>
            {
                ["tool"] = "file.read",
                ["path"] = path,
                ["status"] = response.Status,
                ["failureCode"] = response.FailureCode,
                ["error"] = response.Error
            });
            files.Add(ToCliContextReadFile(path, response));
            if (response.Status != "SUCCEEDED")
            {
                warnings.Add(path + ": " + (response.Error ?? response.FailureCode ?? response.Status));
            }
        }

        await PostCliContextReadResult(webToken, repositoryId, conversationId, turnId, agentId, workspaceId, files, toolResults, warnings);
        var succeeded = files.Count(file => string.Equals(file.GetValueOrDefault("status")?.ToString(), "SUCCEEDED", StringComparison.OrdinalIgnoreCase));
        Console.WriteLine($"컨텍스트 읽기 완료: {succeeded}/{files.Count}개 파일");
        return warnings.Count == 0 ? 0 : 1;
    }

    private static IReadOnlyList<CliToolPlanStep> BuildContextReadSteps(IReadOnlyList<CliToolPlanStep> toolPlan, IReadOnlyList<string> targetFiles)
    {
        if (toolPlan.Count > 0)
        {
            return toolPlan;
        }
        return targetFiles
            .Take(8)
            .Select(path => new CliToolPlanStep("file.read", new Dictionary<string, object?>
            {
                ["path"] = path,
                ["maxBytes"] = DefaultMaxReadBytes
            }))
            .ToList();
    }

    private ToolResponse ExecuteCliReadOnlyStep(AgentConfig config, Guid workspaceId, CliToolPlanStep step)
    {
        var tool = step.Tool;
        if (!new[] { "workspace.tree", "workspace.search", "file.read", "git.status", "git.diff" }.Contains(tool, StringComparer.OrdinalIgnoreCase))
        {
            tool = "workspace.tree";
        }
        var input = new Dictionary<string, object?>(step.Input, StringComparer.OrdinalIgnoreCase)
        {
            ["workspaceId"] = workspaceId
        };
        var request = BuildToolRequest(workspaceId, input);
        return HandleTool(config, Guid.NewGuid(), request, tool);
    }

    private static Dictionary<string, object?> ToCliToolResult(CliToolPlanStep step, ToolResponse response)
    {
        return new Dictionary<string, object?>
        {
            ["tool"] = step.Tool,
            ["input"] = step.Input,
            ["status"] = response.Status,
            ["failureCode"] = response.FailureCode,
            ["error"] = response.Error,
            ["output"] = response.Output
        };
    }

    private static Dictionary<string, object?> ToCliContextReadFile(string requestedPath, ToolResponse response)
    {
        var file = new Dictionary<string, object?>
        {
            ["path"] = response.Output.TryGetValue("path", out var path) ? path : requestedPath,
            ["requestedPath"] = requestedPath,
            ["status"] = response.Status,
            ["failureCode"] = response.FailureCode,
            ["error"] = response.Error
        };
        foreach (var key in new[] { "bytes", "returnedBytes", "truncated", "sha256", "content" })
        {
            if (response.Output.TryGetValue(key, out var value))
            {
                file[key] = value;
            }
        }
        return file;
    }

    private async Task<CliContextReadPostResult> PostCliContextReadResult(
        string webToken,
        Guid repositoryId,
        Guid conversationId,
        Guid turnId,
        Guid? agentId,
        Guid? workspaceId,
        List<Dictionary<string, object?>> files,
        List<Dictionary<string, object?>> toolResults,
        List<string> warnings)
    {
        var server = (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        using var client = new HttpClient
        {
            BaseAddress = new Uri(server),
            Timeout = TimeSpan.FromMinutes(2)
        };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));

        using var response = await client.PostAsync("/api/code-agent/interactive/context/read-result", Json(new
        {
            repositoryId,
            conversationId,
            turnId,
            agentId,
            workspaceId,
            files,
            toolResults,
            warnings
        }));
        var body = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException("HTTP " + (int)response.StatusCode + " " + body);
        }
        using var document = JsonDocument.Parse(body);
        var root = document.RootElement;
        return new CliContextReadPostResult(
            TryGetString(root, "answer"),
            TryGetBool(root, "contextRequired").GetValueOrDefault(false),
            TryGetStringArray(root, "targetFiles"),
            TryGetToolPlan(root, "toolPlan"),
            TryGetAdviceFixGoals(root)
        );
    }

    private async Task PrintCliSessionContext(string[] inheritedArgs)
    {
        if (_cliChatConversationId is not Guid conversationId)
        {
            Console.WriteLine("현재 대화 세션에 저장된 컨텍스트가 없습니다.");
            return;
        }
        var repositoryId = GetOption(inheritedArgs, "--repository-id");
        if (string.IsNullOrWhiteSpace(repositoryId))
        {
            Console.WriteLine("세션 컨텍스트 조회에는 --repository-id가 필요합니다.");
            return;
        }
        var webToken = Environment.GetEnvironmentVariable("LEARNBOT_WEB_TOKEN")
            ?? await ReadStoredWebAccessTokenWithRefresh(LoadConfigOrDefault().ServerUrl);
        if (string.IsNullOrWhiteSpace(webToken))
        {
            Console.WriteLine("로그인이 필요합니다. 먼저 실행하세요: learnbot login");
            return;
        }

        var server = (LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083").TrimEnd('/');
        using var client = new HttpClient { BaseAddress = new Uri(server), Timeout = TimeSpan.FromMinutes(2) };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", webToken);
        using var response = await client.GetAsync("/api/code-agent/interactive/sessions/" + conversationId + "/context?repositoryId=" + Uri.EscapeDataString(repositoryId));
        var body = await response.Content.ReadAsStringAsync();
        Console.WriteLine(response.IsSuccessStatusCode ? body : "세션 컨텍스트 조회 실패: HTTP " + (int)response.StatusCode + " " + body);
    }

    private static CliChatDirective ParseCliChatDirective(string line)
    {
        if (!line.StartsWith("/", StringComparison.Ordinal))
        {
            return new CliChatDirective("auto", line);
        }

        var parts = line[1..].Split(' ', 2, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var command = parts.Length == 0 ? "help" : parts[0].ToLowerInvariant();
        var goal = parts.Length > 1 ? parts[1].Trim() : "";
        return command switch
        {
            "q" or "quit" or "exit" => new CliChatDirective("exit", ""),
            "h" or "help" or "?" => new CliChatDirective("help", ""),
            "clear" => new CliChatDirective("clear", ""),
            "context" => new CliChatDirective("context", ""),
            "status" => new CliChatDirective("status", ""),
            "doctor" => new CliChatDirective("doctor", ""),
            "open" => new CliChatDirective("open", ""),
            "review" => new CliChatDirective("review", string.IsNullOrWhiteSpace(goal) ? "review current workspace changes" : goal),
            "fix" => new CliChatDirective("fix", goal),
            "preview" => new CliChatDirective("preview", goal),
            _ => new CliChatDirective("auto", line)
        };
    }

    private bool TryResolveAdviceSelection(string line, out string goal)
    {
        goal = "";
        if (_cliAdviceFixGoals.Count == 0)
        {
            return false;
        }
        var clean = line.Trim();
        if (!int.TryParse(clean, out var index))
        {
            return false;
        }
        if (index < 1 || index > _cliAdviceFixGoals.Count)
        {
            return false;
        }
        goal = _cliAdviceFixGoals[index - 1];
        return !string.IsNullOrWhiteSpace(goal);
    }

    private static string[] BuildCliChatInheritedArgs(string[] args, string workspace)
    {
        var inherited = new List<string> { "--workspace", workspace };
        AddInheritedOption(args, inherited, "--repository-id");
        AddInheritedOption(args, inherited, "--space-id");
        AddInheritedOption(args, inherited, "--max-steps");
        AddInheritedOption(args, inherited, "--poll-timeout");
        AddInheritedOption(args, inherited, "--approval-timeout");
        AddInheritedFlag(args, inherited, "--no-auto-loop");
        AddInheritedFlag(args, inherited, "--no-apply");
        return inherited.ToArray();
    }

    private static string[] BuildCliChatCommandArgs(string[] inheritedArgs, string goal, bool previewOnly)
    {
        var commandArgs = new List<string>(inheritedArgs);
        if (previewOnly)
        {
            commandArgs.Add("--preview");
        }
        if (!string.IsNullOrWhiteSpace(goal))
        {
            commandArgs.Add("--goal");
            commandArgs.Add(goal);
        }
        return commandArgs.ToArray();
    }

    private static void AddInheritedOption(string[] args, List<string> inherited, string name)
    {
        var value = GetOption(args, name);
        if (string.IsNullOrWhiteSpace(value))
        {
            return;
        }
        inherited.Add(name);
        inherited.Add(value);
    }

    private static void AddInheritedFlag(string[] args, List<string> inherited, string name)
    {
        if (args.Contains(name, StringComparer.OrdinalIgnoreCase))
        {
            inherited.Add(name);
        }
    }

    private static IReadOnlyList<string> TryGetStringArray(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var array) || array.ValueKind != JsonValueKind.Array)
        {
            return [];
        }
        var values = new List<string>();
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
            {
                values.Add(item.GetString()!);
            }
        }
        return values;
    }

    private static IReadOnlyList<CliToolPlanStep> TryGetToolPlan(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var array) || array.ValueKind != JsonValueKind.Array)
        {
            return [];
        }
        var steps = new List<CliToolPlanStep>();
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty("tool", out var toolNode))
            {
                continue;
            }
            var tool = toolNode.GetString() ?? "";
            if (string.IsNullOrWhiteSpace(tool))
            {
                continue;
            }
            var input = new Dictionary<string, object?>(StringComparer.OrdinalIgnoreCase);
            if (item.TryGetProperty("input", out var inputNode) && inputNode.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in inputNode.EnumerateObject())
                {
                    input[property.Name] = JsonScalarValue(property.Value);
                }
            }
            steps.Add(new CliToolPlanStep(tool, input));
        }
        return steps;
    }

    private static IReadOnlyList<string> TryGetAdviceFixGoals(JsonElement element)
    {
        if (!element.TryGetProperty("adviceCandidates", out var array) || array.ValueKind != JsonValueKind.Array)
        {
            return [];
        }
        var goals = new List<string>();
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty("recommendedFixGoal", out var goalNode))
            {
                continue;
            }
            var goal = goalNode.GetString();
            if (!string.IsNullOrWhiteSpace(goal))
            {
                goals.Add(goal);
            }
        }
        return goals;
    }

    private static object? JsonScalarValue(JsonElement value)
    {
        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number when value.TryGetInt32(out var intValue) => intValue,
            JsonValueKind.Number when value.TryGetInt64(out var longValue) => longValue,
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        };
    }

    private static void PrintCliChatHelp()
    {
        Console.WriteLine("""
        일반 자연어       LLM이 답변/검토/수정/컨텍스트 읽기 여부를 판단합니다.
        /fix <goal>       수정 의도를 명시하고 승인 후 적용합니다.
        /review <goal>    검토 의도를 명시합니다.
        /preview <goal>   적용하지 않고 실행 계획만 확인합니다.
        /context          현재 세션에 저장된 읽기 컨텍스트를 조회합니다.
        /clear            현재 CLI 대화 세션 컨텍스트를 초기화합니다.
        /status           Local Agent 상태를 봅니다.
        /doctor           로컬 설정과 실행 상태를 진단합니다.
        /open             브라우저에서 LearnBot 코드 화면을 엽니다.
        /exit             대화형 CLI를 종료합니다.
        """);
    }

    private static int SelfTestCliChatContract()
    {
        var natural = ParseCliChatDirective("홈페이지 메인화면을 더 세련되게 개선해줘");
        var read = ParseCliChatDirective("agent.md 읽고와");
        var fix = ParseCliChatDirective("/fix failing tests");
        var review = ParseCliChatDirective("/review");
        var preview = ParseCliChatDirective("/preview 로그인 만료 처리를 확인해줘");
        var context = ParseCliChatDirective("/context");
        var clear = ParseCliChatDirective("/clear");
        var exit = ParseCliChatDirective("/q");
        var inherited = BuildCliChatInheritedArgs(
            ["--workspace", "C:\\work", "--repository-id", "repo-1", "--max-steps", "9", "--no-apply"],
            "C:\\work");
        var commandArgs = BuildCliChatCommandArgs(inherited, "repair css", previewOnly: true);

        var ok = natural.Command == "auto"
            && natural.Goal == "홈페이지 메인화면을 더 세련되게 개선해줘"
            && read.Command == "auto"
            && read.Goal == "agent.md 읽고와"
            && fix.Command == "fix"
            && fix.Goal == "failing tests"
            && review.Command == "review"
            && review.Goal == "review current workspace changes"
            && preview.Command == "preview"
            && preview.Goal == "로그인 만료 처리를 확인해줘"
            && context.Command == "context"
            && clear.Command == "clear"
            && exit.Command == "exit"
            && commandArgs.Contains("--workspace", StringComparer.Ordinal)
            && commandArgs.Contains("C:\\work", StringComparer.Ordinal)
            && commandArgs.Contains("--repository-id", StringComparer.Ordinal)
            && commandArgs.Contains("repo-1", StringComparer.Ordinal)
            && commandArgs.Contains("--max-steps", StringComparer.Ordinal)
            && commandArgs.Contains("9", StringComparer.Ordinal)
            && commandArgs.Contains("--no-apply", StringComparer.Ordinal)
            && commandArgs.Contains("--preview", StringComparer.Ordinal)
            && commandArgs.Contains("--goal", StringComparer.Ordinal)
            && commandArgs.Contains("repair css", StringComparer.Ordinal);
        if (!ok)
        {
            Console.Error.WriteLine("cli chat contract self-test failed");
            return 1;
        }
        Console.WriteLine("cli-chat-contract-ok");
        return 0;
    }
}

internal sealed record CliChatDirective(string Command, string Goal);

internal sealed record CliInteractiveTurnResult(
    Guid? ConversationId,
    Guid? TurnId,
    string? Intent,
    string? Command,
    string? Goal,
    string? Answer,
    bool ShouldRunCommand,
    bool ContextRequired,
    IReadOnlyList<string> TargetFiles,
    IReadOnlyList<CliToolPlanStep> ToolPlan);

internal sealed record CliToolPlanStep(string Tool, Dictionary<string, object?> Input);

internal sealed record CliContextReadPostResult(
    string? Answer,
    bool ContextRequired,
    IReadOnlyList<string> TargetFiles,
    IReadOnlyList<CliToolPlanStep> ToolPlan,
    IReadOnlyList<string> AdviceFixGoals);
