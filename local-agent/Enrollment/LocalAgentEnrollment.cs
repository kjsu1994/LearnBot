using System.Net;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private async Task<int> Connect(string[] args)
    {
        var config = LoadConfigOrDefault();
        var server = (GetOption(args, "--server") ?? ConfiguredPublicBaseUrl()).TrimEnd('/');
        if (!TryValidateEnrollmentServer(server, out var serverUri, out var serverError))
        {
            Console.Error.WriteLine(serverError);
            return 2;
        }

        var transport = NormalizeTransport(GetOption(args, "--transport") ?? "auto");
        var workspace = GetOption(args, "--workspace");
        var timeoutSeconds = Math.Clamp(ParseInt(GetOption(args, "--timeout-seconds"), 600), 60, 900);
        EnsureEnrollmentSkeleton(config, server, transport);
        if (TryReadPendingEnrollment(config, server) is { } pendingEnrollment)
        {
            return await ConfirmEnrollmentCandidate(pendingEnrollment, config, transport, workspace);
        }

        var reuseDecision = DecideEnrollmentReuse(
            config,
            server,
            args.Contains("--reconnect", StringComparer.OrdinalIgnoreCase));
        if (reuseDecision == EnrollmentReuseDecision.ValidateExisting)
        {
            try
            {
                await SendHeartbeat(config);
                Console.WriteLine("Local Agent is already connected.");
                return 0;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
            {
                Console.Error.WriteLine("The existing Local Agent pairing could not be validated: " + ex.Message);
                Console.Error.WriteLine("Retry when the server is available. Use --reconnect only after reviewing or revoking the existing device in LearnBot.");
                return 1;
            }
        }
        if (reuseDecision == EnrollmentReuseDecision.BlockedDifferentServer)
        {
            Console.Error.WriteLine("This Local Agent is already paired with a different server. Use --reconnect only after reviewing or revoking the existing device.");
            return 2;
        }
        if (reuseDecision == EnrollmentReuseDecision.BlockedCredentialUnavailable)
        {
            Console.Error.WriteLine("Existing pairing metadata was found, but its protected credential is unavailable for this Windows user.");
            Console.Error.WriteLine("Use --reconnect only after reviewing or revoking the existing device in LearnBot.");
            return 2;
        }

        try
        {
            using var client = new HttpClient { BaseAddress = serverUri, Timeout = TimeSpan.FromSeconds(30) };
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var create = await client.PostAsync("/api/local-agents/enrollments", Json(new
            {
                installationId = config.InstallationId,
                machineName = Environment.MachineName,
                platform = "windows",
                osName = "Windows",
                osVersion = Environment.OSVersion.VersionString,
                architecture = RuntimeInformation.OSArchitecture.ToString().ToLowerInvariant(),
                agentVersion = Version
            }));
            var createBody = await create.Content.ReadAsStringAsync();
            if (!create.IsSuccessStatusCode)
            {
                Console.Error.WriteLine("Failed to start Local Agent enrollment: HTTP " + (int)create.StatusCode);
                return 1;
            }

            using var createJson = JsonDocument.Parse(createBody);
            var root = createJson.RootElement;
            var deviceCode = RequiredString(root, "deviceCode");
            var userCode = RequiredString(root, "userCode");
            var intervalSeconds = root.TryGetProperty("intervalSeconds", out var intervalElement)
                ? Math.Clamp(intervalElement.GetInt32(), 5, 30)
                : 5;
            var approvalPath = root.TryGetProperty("verificationUriCompletePath", out var completePath)
                ? completePath.GetString()
                : root.TryGetProperty("verificationUriPath", out var pathElement)
                    ? pathElement.GetString()
                    : null;
            if (string.IsNullOrWhiteSpace(approvalPath))
            {
                throw new JsonException("Enrollment response did not include a verification URI.");
            }
            var approvalUri = new Uri(serverUri, approvalPath);
            Console.WriteLine("Approve this PC in your LearnBot browser:");
            Console.WriteLine(approvalUri);
            Console.WriteLine("User code: " + userCode);
            if (!args.Contains("--no-open", StringComparer.OrdinalIgnoreCase))
            {
                TryOpenUrl(approvalUri.ToString());
            }

            var deadline = DateTimeOffset.UtcNow.AddSeconds(timeoutSeconds);
            if (root.TryGetProperty("expiresAt", out var expiresElement)
                && DateTimeOffset.TryParse(expiresElement.GetString(), out var serverExpiry)
                && serverExpiry < deadline)
            {
                deadline = serverExpiry;
            }

            while (DateTimeOffset.UtcNow < deadline)
            {
                await Task.Delay(TimeSpan.FromSeconds(intervalSeconds));
                HttpResponseMessage exchange;
                try
                {
                    exchange = await client.PostAsync(
                        "/api/local-agents/enrollments/exchange",
                        Json(new { deviceCode }));
                }
                catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
                {
                    Console.Error.WriteLine("Enrollment exchange response was unavailable; retrying the same device session.");
                    continue;
                }
                using (exchange)
                {
                var exchangeBody = await exchange.Content.ReadAsStringAsync();
                if (exchange.StatusCode == HttpStatusCode.TooManyRequests)
                {
                    intervalSeconds = Math.Min(30, intervalSeconds + 5);
                    continue;
                }
                if (IsTransientEnrollmentStatus(exchange.StatusCode))
                {
                    continue;
                }
                if (!exchange.IsSuccessStatusCode)
                {
                    Console.Error.WriteLine("Failed to complete Local Agent enrollment: HTTP " + (int)exchange.StatusCode);
                    return 1;
                }

                using var exchangeJson = JsonDocument.Parse(exchangeBody);
                var exchangeRoot = exchangeJson.RootElement;
                var status = exchangeRoot.TryGetProperty("status", out var statusElement)
                    ? statusElement.GetString()?.Trim().ToUpperInvariant()
                    : null;
                if (status is "PENDING" or "AUTHORIZATION_PENDING" or "PENDING_BROWSER_APPROVAL")
                {
                    continue;
                }
                if (status is "SLOW_DOWN")
                {
                    intervalSeconds = Math.Min(30, intervalSeconds + 5);
                    continue;
                }
                if (status is not "APPROVED")
                {
                    Console.Error.WriteLine("Local Agent enrollment ended with status: " + (status ?? "FAILED"));
                    return 1;
                }

                var agentId = exchangeRoot.GetProperty("agentId").GetGuid();
                var token = RequiredString(exchangeRoot, "token");
                var enrollmentId = exchangeRoot.GetProperty("enrollmentId").GetGuid();
                var expiresAt = exchangeRoot.TryGetProperty("expiresAt", out var credentialExpiryElement)
                    && DateTimeOffset.TryParse(credentialExpiryElement.GetString(), out var credentialExpiry)
                        ? credentialExpiry
                        : DateTimeOffset.UtcNow.AddDays(30);
                if (!DateTimeOffset.TryParse(RequiredString(exchangeRoot, "confirmBy"), out var confirmBy))
                {
                    throw new JsonException("Enrollment confirmation deadline is invalid.");
                }
                var candidate = new PendingEnrollmentCandidate(
                    server,
                    config.InstallationId,
                    enrollmentId,
                    agentId,
                    token,
                    expiresAt,
                    confirmBy);
                if (!TryWritePendingEnrollment(candidate, out var pendingError))
                {
                    Console.Error.WriteLine("Enrollment candidate could not be protected: " + pendingError);
                    return 1;
                }
                return await ConfirmEnrollmentCandidate(candidate, config, transport, workspace);
                }
            }

            Console.Error.WriteLine("Local Agent enrollment expired before approval.");
            return 1;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or UriFormatException)
        {
            Console.Error.WriteLine("Local Agent enrollment failed: " + ex.Message);
            return 1;
        }
    }

    private async Task<int> ConfirmEnrollmentCandidate(
        PendingEnrollmentCandidate candidate,
        AgentConfig previousConfig,
        string transport,
        string? workspace)
    {
        while (DateTimeOffset.UtcNow < candidate.ConfirmBy)
        {
            try
            {
                using var client = new HttpClient
                {
                    BaseAddress = new Uri(candidate.ServerUrl),
                    Timeout = TimeSpan.FromSeconds(30)
                };
                client.DefaultRequestHeaders.Add("X-Local-Agent-Token", candidate.Token);
                client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
                using var response = await client.PostAsync(
                    $"/api/local-agents/enrollments/{candidate.EnrollmentId}/confirm",
                    Json(new { }));
                if (response.IsSuccessStatusCode)
                {
                    var enrolled = PersistConfirmedEnrollmentCandidate(candidate, previousConfig, transport);
                    if (!string.IsNullOrWhiteSpace(workspace))
                    {
                        var workspaceResult = await WorkspaceAdd(workspace);
                        if (workspaceResult != 0) return workspaceResult;
                        enrolled = LoadConfigOrDefault();
                    }
                    await SendHeartbeat(enrolled);
                    Console.WriteLine("Local Agent connected.");
                    return 0;
                }
                if (!IsTransientEnrollmentStatus(response.StatusCode))
                {
                    if (response.StatusCode is HttpStatusCode.BadRequest
                        or HttpStatusCode.Unauthorized
                        or HttpStatusCode.Forbidden
                        or HttpStatusCode.NotFound
                        or HttpStatusCode.Gone)
                    {
                        TryDeleteFile(PendingEnrollmentPath());
                    }
                    Console.Error.WriteLine("Enrollment confirmation failed: HTTP " + (int)response.StatusCode);
                    return 1;
                }
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
            {
                Console.Error.WriteLine("Enrollment confirmation response was unavailable; retrying the protected candidate.");
            }
            await Task.Delay(TimeSpan.FromSeconds(5));
        }
        TryDeleteFile(PendingEnrollmentPath());
        Console.Error.WriteLine("Enrollment confirmation expired. Start the connection again.");
        return 1;
    }

    private void EnsureEnrollmentSkeleton(AgentConfig config, string server, string transport)
    {
        if (config.AgentId != Guid.Empty) return;
        config.ServerUrl = server;
        config.Version = Version;
        config.Transport = transport;
        SaveConfig(config);
    }

    private AgentConfig PersistConfirmedEnrollmentCandidate(
        PendingEnrollmentCandidate candidate,
        AgentConfig previousConfig,
        string transport)
    {
        var enrolled = new AgentConfig
        {
            ServerUrl = candidate.ServerUrl,
            AgentId = candidate.AgentId,
            Token = candidate.Token,
            CredentialExpiresAt = candidate.ExpiresAt,
            InstallationId = candidate.InstallationId,
            Version = Version,
            Transport = transport,
            Workspaces = previousConfig.Workspaces
        };
        SaveConfig(enrolled);
        TryDeleteFile(PendingEnrollmentPath());
        return enrolled;
    }

    private static bool IsTransientEnrollmentStatus(HttpStatusCode status) =>
        status is HttpStatusCode.RequestTimeout
            or HttpStatusCode.TooManyRequests
            or HttpStatusCode.InternalServerError
            or HttpStatusCode.BadGateway
            or HttpStatusCode.ServiceUnavailable
            or HttpStatusCode.GatewayTimeout;

    private static string RequiredString(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value)
            || value.ValueKind != JsonValueKind.String
            || string.IsNullOrWhiteSpace(value.GetString()))
        {
            throw new JsonException("Missing required response field: " + property);
        }
        return value.GetString()!;
    }

    private static bool TryValidateEnrollmentServer(string value, out Uri uri, out string? error)
    {
        return ServerOriginPolicy.TryValidateServerOrigin(
            value,
            ConfiguredPublicBaseUrl(),
            ConfiguredAllowInsecurePrivateNetwork(),
            out uri,
            out error);
    }

    private static EnrollmentReuseDecision DecideEnrollmentReuse(AgentConfig config, string server, bool reconnect)
    {
        if (reconnect || config.AgentId == Guid.Empty)
        {
            return EnrollmentReuseDecision.Enroll;
        }
        if (!SameServer(config.ServerUrl, server))
        {
            return EnrollmentReuseDecision.BlockedDifferentServer;
        }
        return string.IsNullOrWhiteSpace(config.Token)
            ? EnrollmentReuseDecision.BlockedCredentialUnavailable
            : EnrollmentReuseDecision.ValidateExisting;
    }

    private static int SelfTestEnrollmentReuseContract()
    {
        var paired = new AgentConfig
        {
            ServerUrl = "https://learnbot.example.test",
            AgentId = Guid.Parse("11111111-1111-1111-1111-111111111111"),
            Token = "protected-at-rest-runtime-token"
        };
        var passed = DecideEnrollmentReuse(paired, "https://learnbot.example.test", reconnect: false) == EnrollmentReuseDecision.ValidateExisting
            && DecideEnrollmentReuse(paired, "https://other.example.test", reconnect: false) == EnrollmentReuseDecision.BlockedDifferentServer
            && DecideEnrollmentReuse(paired, "https://other.example.test", reconnect: true) == EnrollmentReuseDecision.Enroll
            && DecideEnrollmentReuse(new AgentConfig
            {
                ServerUrl = paired.ServerUrl,
                AgentId = paired.AgentId
            }, paired.ServerUrl, reconnect: false) == EnrollmentReuseDecision.BlockedCredentialUnavailable
            && DecideEnrollmentReuse(new AgentConfig(), paired.ServerUrl, reconnect: false) == EnrollmentReuseDecision.Enroll;
        Console.WriteLine(passed ? "enrollment-reuse-contract-ok" : "enrollment-reuse-contract-failed");
        return passed ? 0 : 1;
    }

    private static int SelfTestServerOriginPolicyContract()
    {
        const string configured = "http://192.168.1.72:8083";
        var passed = TryValidateEnrollmentServer(
                ConfiguredPublicBaseUrl(),
                out _,
                out _)
            && ServerOriginPolicy.TryValidateServerOrigin(
                configured,
                configured,
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && !ServerOriginPolicy.TryValidateServerOrigin(
                configured,
                configured,
                allowInsecurePrivateNetwork: false,
                out _,
                out _)
            && !ServerOriginPolicy.TryValidateServerOrigin(
                "http://192.168.1.73:8083",
                configured,
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && !ServerOriginPolicy.TryValidateServerOrigin(
                "http://learnbot.internal:8083",
                "http://learnbot.internal:8083",
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && !ServerOriginPolicy.TryValidateServerOrigin(
                "http://203.0.113.10:8083",
                "http://203.0.113.10:8083",
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && ServerOriginPolicy.TryValidateServerOrigin(
                "http://10.20.30.40:8083",
                "https://learnbot.portable.invalid",
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && !ServerOriginPolicy.TryValidateServerOrigin(
                "http://203.0.113.10:8083",
                "https://learnbot.portable.invalid",
                allowInsecurePrivateNetwork: true,
                out _,
                out _)
            && ServerOriginPolicy.TryValidateServerOrigin(
                "https://learnbot.example.test",
                configured,
                allowInsecurePrivateNetwork: false,
                out _,
                out _)
            && ServerOriginPolicy.TryValidateServerOrigin(
                "http://localhost:8083",
                configured,
                allowInsecurePrivateNetwork: false,
                out _,
                out _);
        Console.WriteLine(passed ? "server-origin-policy-contract-ok" : "server-origin-policy-contract-failed");
        return passed ? 0 : 1;
    }
}

internal enum EnrollmentReuseDecision
{
    Enroll,
    ValidateExisting,
    BlockedDifferentServer,
    BlockedCredentialUnavailable
}
