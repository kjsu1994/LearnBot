using System.Diagnostics;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

internal static class Program
{
    private const int UpdateRequiredExitCode = 20;

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test-update-handoff", StringComparer.OrdinalIgnoreCase))
        {
            var root = Path.Combine(Path.GetTempPath(), "learnbot-host-update-" + Guid.NewGuid().ToString("N"));
            try
            {
                Directory.CreateDirectory(root);
                var path = Path.Combine(root, "agent-update.json");
                File.WriteAllText(path, "{\"updateState\":\"UPDATE_REQUIRED\",\"updateUri\":\"https://learnbot.example.test/agent.appinstaller\"}");
                return TryReadUpdateUri(path, out var uri) && uri.StartsWith("https://", StringComparison.Ordinal) ? 0 : 1;
            }
            finally
            {
                try { Directory.Delete(root, recursive: true); } catch { }
            }
        }
        var identity = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        var suffix = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..16];
        using var mutex = new Mutex(false, @"Local\LearnBot.LocalAgent.StartupHost." + suffix);
        try
        {
            if (!mutex.WaitOne(TimeSpan.Zero)) return 0;
        }
        catch (AbandonedMutexException)
        {
        }

        try
        {
            var executable = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "app", "learnbot.exe"));
            if (!File.Exists(executable)) return 2;
            var startInfo = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            startInfo.ArgumentList.Add("agent");
            startInfo.ArgumentList.Add("start");
            startInfo.ArgumentList.Add("--transport");
            startInfo.ArgumentList.Add("auto");
            using var process = Process.Start(startInfo);
            if (process is null) return 3;
            process.WaitForExit();
            if (process.ExitCode == UpdateRequiredExitCode)
            {
                LaunchUpdateSetup();
                return 0;
            }
            return process.ExitCode;
        }
        finally
        {
            try { mutex.ReleaseMutex(); } catch (ApplicationException) { }
        }
    }

    private static void LaunchUpdateSetup()
    {
        var setup = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "setup", "LearnBotSetup.exe"));
        if (!File.Exists(setup)) return;
        var startInfo = new ProcessStartInfo
        {
            FileName = setup,
            UseShellExecute = true
        };
        var updateStatePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            ".learnbot",
            "agent-update.json");
        startInfo.ArgumentList.Add("--update-required");
        if (TryReadUpdateUri(updateStatePath, out var updateUri))
        {
            startInfo.ArgumentList.Add(updateUri);
        }
        Process.Start(startInfo);
    }

    private static bool TryReadUpdateUri(string path, out string updateUri)
    {
        updateUri = "";
        try
        {
            if (!File.Exists(path)) return false;
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            var state = root.TryGetProperty("updateState", out var stateElement) ? stateElement.GetString() : null;
            var uriValue = root.TryGetProperty("updateUri", out var uriElement) ? uriElement.GetString() : null;
            if (!string.Equals(state, "UPDATE_REQUIRED", StringComparison.OrdinalIgnoreCase)
                || !Uri.TryCreate(uriValue, UriKind.Absolute, out var uri)
                || uri.Scheme != Uri.UriSchemeHttps)
            {
                return false;
            }
            updateUri = uri.ToString();
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return false;
        }
    }
}
