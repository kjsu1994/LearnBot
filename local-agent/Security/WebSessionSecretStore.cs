using System.Diagnostics;
using System.Text;

internal sealed partial class LearnBotLocalAgent
{
    private const string MacOsKeychainWebSessionProvider = "MACOS_KEYCHAIN";
    private const string LinuxSecretServiceWebSessionProvider = "LINUX_SECRET_SERVICE";
    private const string WindowsDpapiWebSessionProvider = "WINDOWS_DPAPI_CURRENT_USER";

    private static string? CurrentWebSessionSecretProvider(out string? error)
    {
        error = null;
        if (OperatingSystem.IsWindows())
        {
            return WindowsDpapiWebSessionProvider;
        }
        if (OperatingSystem.IsMacOS())
        {
            if (!File.Exists("/usr/bin/security"))
            {
                error = "macOS Keychain command was not found: /usr/bin/security";
                return null;
            }
            return MacOsKeychainWebSessionProvider;
        }
        if (OperatingSystem.IsLinux())
        {
            if (!CommandExists("secret-tool"))
            {
                error = "Linux Secret Service command was not found. Install libsecret-tools or set LEARNBOT_WEB_TOKEN for this shell.";
                return null;
            }
            return LinuxSecretServiceWebSessionProvider;
        }

        error = "stored web session is supported only on Windows, Linux, and macOS";
        return null;
    }

    private static string BuildWebSessionSecretAccount(string serverUrl, string kind)
    {
        var serverHash = Sha256Hex(Encoding.UTF8.GetBytes(serverUrl.TrimEnd('/')))[..16];
        return "learnbot.web-session." + serverHash + "." + kind;
    }

    private static bool TryWriteWebSessionSecret(string provider, string account, string label, string secret, out string? error)
    {
        error = null;
        if (string.Equals(provider, MacOsKeychainWebSessionProvider, StringComparison.Ordinal))
        {
            return RunSecretProcess(
                "/usr/bin/security",
                ["add-generic-password", "-U", "-s", "LearnBot Web Session", "-a", account, "-l", label, "-w", secret],
                null,
                out _,
                out error);
        }

        if (string.Equals(provider, LinuxSecretServiceWebSessionProvider, StringComparison.Ordinal))
        {
            return RunSecretProcess(
                "secret-tool",
                ["store", "--label", label, "service", "learnbot", "account", account],
                secret,
                out _,
                out error);
        }

        error = "unsupported web session secret provider: " + provider;
        return false;
    }

    private static string? TryReadWebSessionSecret(string provider, string account)
    {
        if (string.Equals(provider, MacOsKeychainWebSessionProvider, StringComparison.Ordinal))
        {
            return RunSecretProcess(
                "/usr/bin/security",
                ["find-generic-password", "-s", "LearnBot Web Session", "-a", account, "-w"],
                null,
                out var output,
                out _)
                    ? output.TrimEnd('\r', '\n')
                    : null;
        }

        if (string.Equals(provider, LinuxSecretServiceWebSessionProvider, StringComparison.Ordinal))
        {
            return RunSecretProcess(
                "secret-tool",
                ["lookup", "service", "learnbot", "account", account],
                null,
                out var output,
                out _)
                    ? output.TrimEnd('\r', '\n')
                    : null;
        }

        return null;
    }

    private static bool CommandExists(string fileName)
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(directory, fileName);
                if (File.Exists(candidate))
                {
                    return true;
                }
            }
            catch (ArgumentException)
            {
            }
        }
        return false;
    }

    private static bool RunSecretProcess(
        string fileName,
        IReadOnlyList<string> arguments,
        string? standardInput,
        out string standardOutput,
        out string? error)
    {
        standardOutput = "";
        error = null;
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = fileName,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                RedirectStandardInput = standardInput is not null,
                UseShellExecute = false
            };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }

            using var process = Process.Start(startInfo);
            if (process is null)
            {
                error = "failed to start secret-store command";
                return false;
            }
            if (standardInput is not null)
            {
                process.StandardInput.Write(standardInput);
                process.StandardInput.Close();
            }

            standardOutput = process.StandardOutput.ReadToEnd();
            var standardError = process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode == 0)
            {
                return true;
            }

            error = string.IsNullOrWhiteSpace(standardError)
                ? "secret-store command failed with exit code " + process.ExitCode
                : standardError.Trim();
            return false;
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException)
        {
            error = ex.Message;
            return false;
        }
    }
}
