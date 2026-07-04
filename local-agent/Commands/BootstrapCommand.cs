using System.Diagnostics;
using System.Runtime.Versioning;
using System.Security.Principal;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private int Bootstrap(string[] args)
    {
        var repoRoot = GetOption(args, "--repo")
            ?? Environment.GetEnvironmentVariable("LEARNBOT_REPO_ROOT")
            ?? FindLearnBotRepositoryRoot(Directory.GetCurrentDirectory());
        var workspace = Path.GetFullPath(GetOption(args, "--workspace") ?? Directory.GetCurrentDirectory());
        var server = GetOption(args, "--server") ?? "http://localhost:8083";
        var transport = NormalizeTransport(GetOption(args, "--transport") ?? "polling");
        var intervalSeconds = Math.Clamp(ParseInt(GetOption(args, "--interval-seconds"), 15), 5, 300);
        var loginId = GetOption(args, "--login-id");

        if (args.Contains("--plan", StringComparer.OrdinalIgnoreCase)
            || args.Contains("--preview", StringComparer.OrdinalIgnoreCase))
        {
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                schema = "learnbot.local-agent.bootstrap-command-plan.v1",
                repoRoot,
                workspace,
                server,
                transport,
                intervalSeconds,
                operatingSystem = OperatingSystem.IsWindows()
                    ? "windows"
                    : OperatingSystem.IsMacOS()
                        ? "macos"
                        : OperatingSystem.IsLinux()
                            ? "linux"
                            : "unsupported",
                script = ResolveBootstrapScript(repoRoot)
            }, JsonOptions));
            return 0;
        }

        if (string.IsNullOrWhiteSpace(repoRoot))
        {
            Console.Error.WriteLine("LearnBot repository root was not found.");
            Console.Error.WriteLine("Run from the LearnBot repository, set LEARNBOT_REPO_ROOT, or pass --repo <path>.");
            return 2;
        }

        if (OperatingSystem.IsWindows())
        {
            return BootstrapWindows(repoRoot, workspace, server, transport, intervalSeconds, loginId);
        }

        if (OperatingSystem.IsLinux() || OperatingSystem.IsMacOS())
        {
            return BootstrapUnix(repoRoot, workspace, server, transport, intervalSeconds, loginId);
        }

        Console.Error.WriteLine("learnbot bootstrap supports Windows, Linux, and macOS.");
        return 2;
    }

    private int Restart(string[] args)
    {
        var install = args.Contains("--install", StringComparer.OrdinalIgnoreCase)
            || args.Contains("--upgrade", StringComparer.OrdinalIgnoreCase);
        var repoRoot = GetOption(args, "--repo")
            ?? Environment.GetEnvironmentVariable("LEARNBOT_REPO_ROOT")
            ?? FindLearnBotRepositoryRoot(Directory.GetCurrentDirectory());

        if (OperatingSystem.IsWindows())
        {
            return RestartWindows(install, repoRoot);
        }

        if (install)
        {
            if (string.IsNullOrWhiteSpace(repoRoot))
            {
                Console.Error.WriteLine("Usage: learnbot restart --install [--repo <LearnBot repo path>]");
                Console.Error.WriteLine("Could not find the LearnBot repository from the current directory.");
                return 2;
            }
            return BootstrapUnix(
                repoRoot,
                Path.GetFullPath(GetOption(args, "--workspace") ?? Directory.GetCurrentDirectory()),
                GetOption(args, "--server") ?? LoadConfigOrDefault().ServerUrl ?? "http://localhost:8083",
                NormalizeTransport(GetOption(args, "--transport") ?? LoadConfigOrDefault().Transport),
                Math.Clamp(ParseInt(GetOption(args, "--interval-seconds"), 15), 5, 300),
                GetOption(args, "--login-id"));
        }

        if (OperatingSystem.IsLinux())
        {
            return RunProcess("systemctl", ["--user", "restart", "learnbot-local-agent.service"]);
        }
        if (OperatingSystem.IsMacOS())
        {
            return RunProcess("/bin/sh", ["-lc", "launchctl kickstart -k gui/$(id -u)/com.learnbot.local-agent"]);
        }

        Console.Error.WriteLine("learnbot restart supports Windows, Linux, and macOS.");
        return 2;
    }

    [SupportedOSPlatform("windows")]
    private static int BootstrapWindows(
        string repoRoot,
        string workspace,
        string server,
        string transport,
        int intervalSeconds,
        string? loginId)
    {
        var script = Path.Combine(repoRoot, "scripts", "learnbot-bootstrap.ps1");
        if (!File.Exists(script))
        {
            Console.Error.WriteLine("Bootstrap script was not found: " + script);
            return 2;
        }

        var command = "$ErrorActionPreference='Stop'; "
            + "Set-Location -LiteralPath " + PowerShellQuote(repoRoot) + "; "
            + "& " + PowerShellQuote(script)
            + " -Server " + PowerShellQuote(server)
            + " -Workspace " + PowerShellQuote(workspace)
            + " -Transport " + PowerShellQuote(transport)
            + " -IntervalSeconds " + intervalSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture)
            + (string.IsNullOrWhiteSpace(loginId) ? "" : " -LoginId " + PowerShellQuote(loginId));

        try
        {
            StartPowerShellElevated(command, waitForExit: false);
            Console.WriteLine("LearnBot bootstrap started in an elevated PowerShell window.");
            return 0;
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            Console.Error.WriteLine("Failed to start bootstrap helper: " + ex.Message);
            Console.Error.WriteLine("Run from administrator PowerShell: .\\scripts\\learnbot-bootstrap.ps1");
            return 1;
        }
    }

    private static int BootstrapUnix(
        string repoRoot,
        string workspace,
        string server,
        string transport,
        int intervalSeconds,
        string? loginId)
    {
        var script = Path.Combine(repoRoot, "scripts", "learnbot-bootstrap.sh");
        if (!File.Exists(script))
        {
            Console.Error.WriteLine("Bootstrap script was not found: " + script);
            return 2;
        }

        var arguments = new List<string>
        {
            script,
            "--server", server,
            "--workspace", workspace,
            "--transport", transport,
            "--interval-seconds", intervalSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture)
        };
        if (!string.IsNullOrWhiteSpace(loginId))
        {
            arguments.Add("--login-id");
            arguments.Add(loginId);
        }

        return RunProcess("/bin/sh", arguments);
    }

    [SupportedOSPlatform("windows")]
    private static int RestartWindows(bool install, string? repoRoot)
    {
        if (install && string.IsNullOrWhiteSpace(repoRoot))
        {
            Console.Error.WriteLine("Usage: learnbot restart --install [--repo <LearnBot repo path>]");
            Console.Error.WriteLine("Could not find scripts\\local-agent-install.ps1 from the current directory.");
            return 2;
        }

        var command = install
            ? BuildInstallRestartPowerShell(repoRoot!)
            : BuildServiceRestartPowerShell();
        try
        {
            StartPowerShellElevated(command, waitForExit: !install);
            Console.WriteLine(install
                ? "LearnBot reinstall/restart started in an elevated PowerShell window."
                : "LearnBot Local Agent service restart requested.");
            if (install)
            {
                Console.WriteLine("This command exits immediately so the installer can replace learnbot.dll without this process locking it.");
            }
            return 0;
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            Console.Error.WriteLine("Failed to start restart helper: " + ex.Message);
            Console.Error.WriteLine("Run from administrator PowerShell: Stop-Service LearnBotLocalAgent; .\\scripts\\local-agent-install.ps1 -Action install -AddToUserPath; Start-Service LearnBotLocalAgent");
            return 1;
        }
    }

    private static string? ResolveBootstrapScript(string? repoRoot)
    {
        if (string.IsNullOrWhiteSpace(repoRoot))
        {
            return null;
        }
        return OperatingSystem.IsWindows()
            ? Path.Combine(repoRoot, "scripts", "learnbot-bootstrap.ps1")
            : Path.Combine(repoRoot, "scripts", "learnbot-bootstrap.sh");
    }

    private static string? FindLearnBotRepositoryRoot(string startDirectory)
    {
        var directory = new DirectoryInfo(Path.GetFullPath(startDirectory));
        while (directory is not null)
        {
            var installScript = Path.Combine(directory.FullName, "scripts", "local-agent-install.ps1");
            var serviceScript = Path.Combine(directory.FullName, "scripts", "local-agent.ps1");
            var projectFile = Path.Combine(directory.FullName, "local-agent", "LearnBot.LocalAgent.csproj");
            if (File.Exists(installScript) && File.Exists(serviceScript) && File.Exists(projectFile))
            {
                return directory.FullName;
            }
            directory = directory.Parent;
        }
        return null;
    }

    private static string BuildServiceRestartPowerShell() =>
        "$ErrorActionPreference='Stop'; "
        + "Restart-Service -Name LearnBotLocalAgent -Force; "
        + "Get-Service -Name LearnBotLocalAgent";

    private static string BuildInstallRestartPowerShell(string repoRoot) =>
        "$ErrorActionPreference='Stop'; "
        + "Start-Sleep -Seconds 2; "
        + "Set-Location -LiteralPath " + PowerShellQuote(repoRoot) + "; "
        + "$service = Get-Service -Name LearnBotLocalAgent -ErrorAction SilentlyContinue; "
        + "if ($null -ne $service -and $service.Status -ne 'Stopped') { "
        + "Stop-Service -Name LearnBotLocalAgent -Force -ErrorAction Stop; "
        + "$service.WaitForStatus('Stopped','00:00:30'); "
        + "} "
        + "& .\\scripts\\local-agent-install.ps1 -Action install -AddToUserPath; "
        + "Start-Service -Name LearnBotLocalAgent -ErrorAction Stop; "
        + "Get-Service -Name LearnBotLocalAgent";

    private static string PowerShellQuote(string value) =>
        "'" + value.Replace("'", "''", StringComparison.Ordinal) + "'";

    [SupportedOSPlatform("windows")]
    private static void StartPowerShellElevated(string command, bool waitForExit)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            UseShellExecute = true,
            WindowStyle = ProcessWindowStyle.Normal
        };
        startInfo.ArgumentList.Add("-NoProfile");
        startInfo.ArgumentList.Add("-ExecutionPolicy");
        startInfo.ArgumentList.Add("Bypass");
        startInfo.ArgumentList.Add("-Command");
        startInfo.ArgumentList.Add(command);

        if (!IsAdministrator())
        {
            startInfo.Verb = "runas";
        }

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("PowerShell process was not started.");
        if (waitForExit)
        {
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                throw new InvalidOperationException("Restart helper exited with code " + process.ExitCode + ".");
            }
        }
    }

    [SupportedOSPlatform("windows")]
    private static bool IsAdministrator()
    {
        using var identity = WindowsIdentity.GetCurrent();
        var principal = new WindowsPrincipal(identity);
        return principal.IsInRole(WindowsBuiltInRole.Administrator);
    }

    private static int RunProcess(string fileName, IReadOnlyList<string> arguments)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = fileName,
            UseShellExecute = false
        };
        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        using var process = Process.Start(startInfo);
        if (process is null)
        {
            Console.Error.WriteLine("Failed to start process: " + fileName);
            return 1;
        }
        process.WaitForExit();
        return process.ExitCode;
    }
}
