using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private const string PendingEnrollmentSchema = "learnbot.local-agent.pending-enrollment.v1";

    private static string PendingEnrollmentPath() => Path.Combine(AgentDataDirectory(), "agent-enrollment.pending.json");

    private static bool TryWritePendingEnrollment(PendingEnrollmentCandidate candidate, out string? error)
    {
        error = null;
        var encryptedToken = TryProtectForCurrentUser(candidate.Token, out error);
        if (encryptedToken is null) return false;
        try
        {
            var path = PendingEnrollmentPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + ".tmp";
            var body = new
            {
                schema = PendingEnrollmentSchema,
                candidate.ServerUrl,
                candidate.InstallationId,
                candidate.EnrollmentId,
                candidate.AgentId,
                encryptedToken,
                candidate.ExpiresAt,
                candidate.ConfirmBy,
                createdAt = DateTimeOffset.UtcNow,
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

    private static PendingEnrollmentCandidate? TryReadPendingEnrollment(AgentConfig config, string server)
    {
        try
        {
            var path = PendingEnrollmentPath();
            if (!File.Exists(path)) return null;
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            if (RequiredString(root, "schema") != PendingEnrollmentSchema
                || !SameServer(RequiredString(root, "serverUrl"), server)
                || root.GetProperty("installationId").GetGuid() != config.InstallationId)
            {
                return null;
            }
            var encryptedToken = RequiredString(root, "encryptedToken");
            var token = TryUnprotectForCurrentUser(encryptedToken);
            if (string.IsNullOrWhiteSpace(token)) return null;
            return new PendingEnrollmentCandidate(
                RequiredString(root, "serverUrl"),
                config.InstallationId,
                root.GetProperty("enrollmentId").GetGuid(),
                root.GetProperty("agentId").GetGuid(),
                token,
                root.GetProperty("expiresAt").GetDateTimeOffset(),
                root.GetProperty("confirmBy").GetDateTimeOffset());
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or FormatException)
        {
            return null;
        }
    }

    private static int SelfTestEnrollmentConfirmationRecoveryContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-enrollment-recovery-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent.json"));
            var app = new LearnBotLocalAgent();
            var config = new AgentConfig { InstallationId = Guid.Parse("11111111-1111-1111-1111-111111111111") };
            app.EnsureEnrollmentSkeleton(config, "https://learnbot.example.test", "auto");
            var candidate = new PendingEnrollmentCandidate(
                "https://learnbot.example.test",
                config.InstallationId,
                Guid.Parse("22222222-2222-2222-2222-222222222222"),
                Guid.Parse("33333333-3333-3333-3333-333333333333"),
                "candidate-token-response-loss",
                DateTimeOffset.UtcNow.AddDays(30),
                DateTimeOffset.UtcNow.AddMinutes(10));
            var written = TryWritePendingEnrollment(candidate, out _);
            var restartedApp = new LearnBotLocalAgent();
            var restartedConfig = restartedApp.LoadConfigOrDefault();
            var recovered = TryReadPendingEnrollment(restartedConfig, candidate.ServerUrl);
            var artifactText = File.ReadAllText(PendingEnrollmentPath());
            var passed = written
                && restartedConfig.InstallationId == candidate.InstallationId
                && recovered?.EnrollmentId == candidate.EnrollmentId
                && recovered.AgentId == candidate.AgentId
                && recovered.Token == candidate.Token
                && !artifactText.Contains(candidate.Token, StringComparison.Ordinal)
                && IsTransientEnrollmentStatus(System.Net.HttpStatusCode.ServiceUnavailable)
                && !IsTransientEnrollmentStatus(System.Net.HttpStatusCode.BadRequest);
            if (passed)
            {
                restartedApp.PersistConfirmedEnrollmentCandidate(recovered!, restartedConfig, "auto");
                var active = restartedApp.LoadConfigOrDefault();
                passed = active.AgentId == candidate.AgentId
                    && active.Token == candidate.Token
                    && active.InstallationId == candidate.InstallationId
                    && !File.Exists(PendingEnrollmentPath());
            }
            Console.WriteLine(passed ? "enrollment-confirmation-recovery-contract-ok" : "enrollment-confirmation-recovery-contract-failed");
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

internal sealed record PendingEnrollmentCandidate(
    string ServerUrl,
    Guid InstallationId,
    Guid EnrollmentId,
    Guid AgentId,
    string Token,
    DateTimeOffset ExpiresAt,
    DateTimeOffset ConfirmBy);
