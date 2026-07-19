using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

internal sealed partial class LearnBotLocalAgent
{
    private static string ReadApplicationVersion()
    {
        var assembly = typeof(LearnBotLocalAgent).Assembly;
        var informationalVersion = assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?
            .InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informationalVersion))
        {
            return informationalVersion.Split('+', 2)[0];
        }
        return assembly.GetName().Version?.ToString(3) ?? "0.0.0";
    }

    private static string ConfiguredPublicBaseUrl()
    {
        var value = typeof(LearnBotLocalAgent).Assembly
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(item => string.Equals(item.Key, "LearnBotPublicBaseUrl", StringComparison.Ordinal))?
            .Value;
        return string.IsNullOrWhiteSpace(value) ? "https://learnbot.example.invalid" : value.TrimEnd('/');
    }

    private static bool ConfiguredAllowInsecurePrivateNetwork()
    {
        var value = typeof(LearnBotLocalAgent).Assembly
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(item => string.Equals(item.Key, "LearnBotAllowInsecurePrivateNetwork", StringComparison.Ordinal))?
            .Value;
        return bool.TryParse(value, out var enabled) && enabled;
    }

    private static IDisposable? TryAcquireAgentInstanceLock()
    {
        var identity = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        var suffix = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..16];
        var mutex = new Mutex(false, @"Local\LearnBot.LocalAgent." + suffix);
        try
        {
            if (!mutex.WaitOne(TimeSpan.Zero))
            {
                mutex.Dispose();
                return null;
            }
        }
        catch (AbandonedMutexException)
        {
        }
        return new OwnedMutex(mutex);
    }

    private AgentConfig? TryReloadPairedRuntimeConfig()
    {
        var config = LoadConfigOrDefault();
        return config.AgentId != Guid.Empty && !string.IsNullOrWhiteSpace(config.Token)
            ? config
            : null;
    }

    private static int SelfTestRuntimeConfigReloadContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "learnbot-runtime-reload-" + Guid.NewGuid().ToString("N"));
        var previousConfig = Environment.GetEnvironmentVariable("LEARNBOT_AGENT_CONFIG");
        try
        {
            Environment.SetEnvironmentVariable("LEARNBOT_AGENT_CONFIG", Path.Combine(root, "agent.json"));
            var app = new LearnBotLocalAgent();
            var config = new AgentConfig
            {
                ServerUrl = "https://learnbot.example.test",
                AgentId = Guid.Parse("11111111-1111-1111-1111-111111111111"),
                Token = "runtime-reload-token",
                Version = Version,
                Transport = "auto"
            };
            app.SaveConfig(config);
            var before = app.TryReloadPairedRuntimeConfig();
            config.Workspaces.Add(new AgentWorkspace(
                Guid.Parse("22222222-2222-2222-2222-222222222222"),
                "workspace",
                root,
                true));
            app.SaveConfig(config);
            var after = app.TryReloadPairedRuntimeConfig();
            var passed = before?.Workspaces.Count == 0
                && after?.Workspaces.Count == 1
                && after.Workspaces[0].WorkspaceId == config.Workspaces[0].WorkspaceId;
            Console.WriteLine(passed ? "runtime-config-reload-contract-ok" : "runtime-config-reload-contract-failed");
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

    private sealed class OwnedMutex(Mutex mutex) : IDisposable
    {
        public void Dispose()
        {
            try
            {
                mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
            }
            mutex.Dispose();
        }
    }
}
