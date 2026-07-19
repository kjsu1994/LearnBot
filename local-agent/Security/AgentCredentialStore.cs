using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private const string AgentCredentialSchema = "learnbot.local-agent.credential.v1";
    private const string AgentCredentialProvider = "WINDOWS_DPAPI_CURRENT_USER";

    private static string AgentCredentialPath() => Path.Combine(AgentDataDirectory(), "agent-credential.json");

    private static string PendingAgentCredentialPath() => Path.Combine(AgentDataDirectory(), "agent-credential.pending.json");

    private static StoredAgentCredential? TryReadAgentCredential(AgentConfig config)
    {
        var artifact = TryReadCredentialArtifact(AgentCredentialPath());
        if (artifact is null
            || artifact.AgentId != config.AgentId
            || !SameServer(artifact.ServerUrl, config.ServerUrl))
        {
            return null;
        }

        var token = TryUnprotectForCurrentUser(artifact.EncryptedToken);
        return string.IsNullOrWhiteSpace(token)
            ? null
            : new StoredAgentCredential(token, artifact.ExpiresAt);
    }

    private static bool TryWriteAgentCredential(
        AgentConfig config,
        string token,
        DateTimeOffset? expiresAt,
        out string? error)
    {
        error = null;
        if (config.AgentId == Guid.Empty || string.IsNullOrWhiteSpace(config.ServerUrl))
        {
            error = "agent id and server URL are required";
            return false;
        }

        var protectedToken = TryProtectForCurrentUser(token, out error);
        if (protectedToken is null)
        {
            return false;
        }

        var effectiveExpiresAt = expiresAt ?? DateTimeOffset.UtcNow.AddDays(30);
        config.CredentialExpiresAt = effectiveExpiresAt;
        return TryWriteCredentialArtifact(
            AgentCredentialPath(),
            new AgentCredentialArtifact(
                AgentCredentialSchema,
                config.ServerUrl.TrimEnd('/'),
                config.AgentId,
                protectedToken,
                effectiveExpiresAt,
                DateTimeOffset.UtcNow,
                null),
            out error);
    }

    private static bool TryStagePendingAgentCredential(
        AgentConfig config,
        Guid rotationId,
        string token,
        DateTimeOffset expiresAt,
        out string? error)
    {
        error = null;
        var protectedToken = TryProtectForCurrentUser(token, out error);
        return protectedToken is not null
            && TryWriteCredentialArtifact(
                PendingAgentCredentialPath(),
                new AgentCredentialArtifact(
                    AgentCredentialSchema,
                    config.ServerUrl!.TrimEnd('/'),
                    config.AgentId,
                    protectedToken,
                    expiresAt,
                    DateTimeOffset.UtcNow,
                    rotationId),
                out error);
    }

    private static PendingAgentCredential? TryReadPendingAgentCredential(AgentConfig config)
    {
        var artifact = TryReadCredentialArtifact(PendingAgentCredentialPath());
        if (artifact?.RotationId is null
            || artifact.AgentId != config.AgentId
            || !SameServer(artifact.ServerUrl, config.ServerUrl))
        {
            return null;
        }

        var token = TryUnprotectForCurrentUser(artifact.EncryptedToken);
        return string.IsNullOrWhiteSpace(token)
            ? null
            : new PendingAgentCredential(artifact.RotationId.Value, token, artifact.ExpiresAt);
    }

    private static bool TryPromotePendingAgentCredential(
        AgentConfig config,
        PendingAgentCredential pending,
        out string? error)
    {
        if (!TryWriteAgentCredential(config, pending.Token, pending.ExpiresAt, out error))
        {
            return false;
        }
        TryDeleteFile(PendingAgentCredentialPath());
        return true;
    }

    private static AgentCredentialArtifact? TryReadCredentialArtifact(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var artifact = JsonSerializer.Deserialize<AgentCredentialArtifact>(File.ReadAllText(path), JsonOptions);
            return artifact is not null
                && string.Equals(artifact.Schema, AgentCredentialSchema, StringComparison.Ordinal)
                && !string.IsNullOrWhiteSpace(artifact.EncryptedToken)
                ? artifact
                : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }

    private static bool TryWriteCredentialArtifact(string path, AgentCredentialArtifact artifact, out string? error)
    {
        error = null;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + ".tmp";
            var body = new
            {
                artifact.Schema,
                artifact.ServerUrl,
                artifact.AgentId,
                artifact.EncryptedToken,
                artifact.ExpiresAt,
                artifact.CreatedAt,
                artifact.RotationId,
                encryption = new
                {
                    provider = AgentCredentialProvider,
                    scope = "CurrentUser",
                    plaintextTokenSerializationAllowed = false
                }
            };
            File.WriteAllText(tempPath, JsonSerializer.Serialize(body, JsonOptions), new UTF8Encoding(false));
            File.Move(tempPath, path, overwrite: true);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            error = ex.Message;
            return false;
        }
    }

    private static bool SameServer(string? first, string? second) =>
        !string.IsNullOrWhiteSpace(first)
        && !string.IsNullOrWhiteSpace(second)
        && string.Equals(first.TrimEnd('/'), second.TrimEnd('/'), StringComparison.OrdinalIgnoreCase);

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    private static int SelfTestAgentCredentialStorageContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-agent-credential-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        const string secret = "legacy-secret-must-not-remain-in-json";
        try
        {
            var configPath = Path.Combine(root, "agent.json");
            Directory.CreateDirectory(root);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", configPath);
            File.WriteAllText(configPath, JsonSerializer.Serialize(new
            {
                serverUrl = "https://learnbot.example.test",
                agentId = "11111111-1111-1111-1111-111111111111",
                token = secret,
                version = "0.1.0",
                transport = "auto",
                workspaces = Array.Empty<object>()
            }, JsonOptions));

            var app = new LearnBotLocalAgent();
            var migrated = app.LoadConfigOrDefault();
            var configText = File.ReadAllText(configPath);
            var credentialText = File.ReadAllText(AgentCredentialPath());
            var reloaded = app.LoadConfigOrDefault();
            var passed = migrated.Token == secret
                && reloaded.Token == secret
                && migrated.CredentialExpiresAt <= DateTimeOffset.UtcNow.AddMinutes(1)
                && !configText.Contains(secret, StringComparison.Ordinal)
                && !credentialText.Contains(secret, StringComparison.Ordinal)
                && !configText.Contains("\"token\"", StringComparison.OrdinalIgnoreCase)
                && credentialText.Contains(AgentCredentialProvider, StringComparison.Ordinal);
            Console.WriteLine(passed ? "agent-credential-storage-contract-ok" : "agent-credential-storage-contract-failed");
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

    private static int SelfTestCorruptAgentCredentialContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-corrupt-credential-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Directory.CreateDirectory(root);
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent.json"));
            var agentId = Guid.Parse("11111111-1111-1111-1111-111111111111");
            File.WriteAllText(ConfigPath(), JsonSerializer.Serialize(new
            {
                serverUrl = "https://learnbot.example.test",
                agentId,
                installationId = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                version = Version,
                transport = "auto",
                workspaces = Array.Empty<object>()
            }, JsonOptions));
            File.WriteAllText(AgentCredentialPath(), JsonSerializer.Serialize(new
            {
                schema = AgentCredentialSchema,
                serverUrl = "https://learnbot.example.test",
                agentId,
                encryptedToken = "%%%not-base64%%%",
                expiresAt = DateTimeOffset.UtcNow.AddDays(30),
                createdAt = DateTimeOffset.UtcNow
            }, JsonOptions));

            var app = new LearnBotLocalAgent();
            var loaded = app.LoadConfigOrDefault();
            var passed = loaded.AgentId == agentId
                && loaded.Token is null
                && TryUnprotectForCurrentUser("%%%not-base64%%%") is null
                && TryUnprotectForCurrentUser("AA==") is null;
            Console.WriteLine(passed ? "corrupt-agent-credential-contract-ok" : "corrupt-agent-credential-contract-failed");
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

internal sealed record StoredAgentCredential(string Token, DateTimeOffset? ExpiresAt);

internal sealed record PendingAgentCredential(Guid RotationId, string Token, DateTimeOffset ExpiresAt);

internal sealed record AgentCredentialArtifact(
    string Schema,
    string ServerUrl,
    Guid AgentId,
    string EncryptedToken,
    DateTimeOffset ExpiresAt,
    DateTimeOffset CreatedAt,
    Guid? RotationId);
