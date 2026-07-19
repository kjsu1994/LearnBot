using System.Net.Http.Headers;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private static DateTimeOffset nextCredentialRotationAttemptAt = DateTimeOffset.MinValue;

    private async Task TryRotateAgentCredential(AgentConfig config)
    {
        if (DateTimeOffset.UtcNow < nextCredentialRotationAttemptAt) return;

        var pending = TryReadPendingAgentCredential(config);
        if (pending is not null)
        {
            nextCredentialRotationAttemptAt = DateTimeOffset.UtcNow.AddMinutes(15);
            await TryConfirmPendingCredential(config, pending);
            return;
        }
        if (config.CredentialExpiresAt is null
            || config.CredentialExpiresAt > DateTimeOffset.UtcNow.AddDays(7))
        {
            return;
        }

        nextCredentialRotationAttemptAt = DateTimeOffset.UtcNow.AddMinutes(15);
        try
        {
            using var client = Client(config);
            using var response = await client.PostAsync(
                "/api/local-agents/self/credential-rotations",
                Json(new { agentVersion = Version }));
            if (!response.IsSuccessStatusCode)
            {
                Log("credential rotation request failed: HTTP " + (int)response.StatusCode);
                return;
            }
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var root = document.RootElement;
            var rotationId = root.GetProperty("rotationId").GetGuid();
            var token = RequiredString(root, "token");
            if (!DateTimeOffset.TryParse(RequiredString(root, "expiresAt"), out var expiresAt))
            {
                throw new JsonException("Credential rotation expiry is invalid.");
            }
            if (!TryStagePendingAgentCredential(config, rotationId, token, expiresAt, out var error))
            {
                Log("credential rotation could not protect candidate credential: " + error);
                return;
            }
            await TryConfirmPendingCredential(config, new PendingAgentCredential(rotationId, token, expiresAt));
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            Log("credential rotation failed: " + ex.Message);
        }
    }

    private async Task<bool> TryConfirmPendingCredential(AgentConfig config, PendingAgentCredential pending)
    {
        try
        {
            using var client = new HttpClient { BaseAddress = new Uri(config.ServerUrl!.TrimEnd('/')) };
            client.DefaultRequestHeaders.Add("X-Local-Agent-Token", pending.Token);
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("learnbot-local-agent", Version));
            using var response = await client.PostAsync(
                $"/api/local-agents/self/credential-rotations/{pending.RotationId}/confirm",
                Json(new { }));
            if (!response.IsSuccessStatusCode)
            {
                Log("credential rotation confirmation failed: HTTP " + (int)response.StatusCode);
                return false;
            }
            if (!TryPromotePendingAgentCredential(config, pending, out var error))
            {
                Log("credential rotation promotion failed: " + error);
                return false;
            }
            config.Token = pending.Token;
            config.CredentialExpiresAt = pending.ExpiresAt;
            config.Version = Version;
            SaveConfig(config);
            Log("credential rotated successfully");
            return true;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            Log("credential rotation confirmation failed: " + ex.Message);
            return false;
        }
    }

    private async Task<bool> RecoverPendingCredentialBeforeHeartbeat(
        AgentConfig config,
        bool once,
        CancellationToken cancellationToken)
    {
        while (TryReadPendingAgentCredential(config) is { } pending)
        {
            Console.WriteLine("Recovering a pending Local Agent credential rotation before reconnecting…");
            if (await TryConfirmPendingCredential(config, pending))
            {
                return true;
            }
            if (once || cancellationToken.IsCancellationRequested)
            {
                return false;
            }
            Log("pending credential recovery will retry in 30 seconds");
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(30), cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return false;
            }
        }
        return true;
    }

    private static int SelfTestCredentialRotationRecoveryContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-credential-recovery-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent.json"));
            var app = new LearnBotLocalAgent();
            var config = new AgentConfig
            {
                ServerUrl = "https://learnbot.example.test",
                AgentId = Guid.Parse("11111111-1111-1111-1111-111111111111"),
                Token = "old-active-token",
                CredentialExpiresAt = DateTimeOffset.UtcNow.AddDays(1),
                Version = Version
            };
            app.SaveConfig(config);
            var rotationId = Guid.Parse("22222222-2222-2222-2222-222222222222");
            var candidateExpiry = DateTimeOffset.UtcNow.AddDays(30);
            var staged = TryStagePendingAgentCredential(config, rotationId, "new-candidate-token", candidateExpiry, out _);

            var restartedApp = new LearnBotLocalAgent();
            var restartedConfig = restartedApp.LoadConfigOrDefault();
            var pending = TryReadPendingAgentCredential(restartedConfig);
            var survivedRestart = staged
                && restartedConfig.Token == "old-active-token"
                && pending?.RotationId == rotationId
                && pending.Token == "new-candidate-token";
            var promoted = pending is not null
                && TryPromotePendingAgentCredential(restartedConfig, pending, out _);
            if (promoted)
            {
                restartedConfig.Token = pending!.Token;
                restartedConfig.CredentialExpiresAt = pending.ExpiresAt;
                restartedApp.SaveConfig(restartedConfig);
            }
            var recovered = restartedApp.LoadConfigOrDefault();
            var passed = survivedRestart
                && promoted
                && recovered.Token == "new-candidate-token"
                && TryReadPendingAgentCredential(recovered) is null;
            Console.WriteLine(passed ? "credential-rotation-recovery-contract-ok" : "credential-rotation-recovery-contract-failed");
            return passed ? 0 : 1;
        }
        finally
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", previousConfig);
            if (Directory.Exists(root))
            {
                try { Directory.Delete(root, recursive: true); } catch { }
            }
        }
    }
}
