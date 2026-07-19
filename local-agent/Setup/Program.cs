using System.Diagnostics;
using System.Reflection;

if (args.Contains("--self-test-output-parser", StringComparer.OrdinalIgnoreCase))
{
    var passed = EnrollmentOutputParser.TryReadApprovalUrl("https://learnbot.example.test/settings/local-agent/device?user_code=ABCD-EFGH", out var url)
        && url.StartsWith("https://", StringComparison.Ordinal)
        && EnrollmentOutputParser.TryReadUserCode("User code: ABCD-EFGH", out var code)
        && code == "ABCD-EFGH"
        && SetupCommandBuilder.Connect("https://learnbot.example.test", reconnect: true).Contains("--reconnect")
        && SetupCommandBuilder.Disconnect(localOnly: true).SequenceEqual(["disconnect", "--local-only"])
        && UpdateRequiredForm.IsTrustedUpdateUri("https://learnbot.example.test/agent.appinstaller")
        && !UpdateRequiredForm.IsTrustedUpdateUri("http://learnbot.example.test/agent.appinstaller");
    Console.WriteLine(passed ? "setup-output-parser-ok" : "setup-output-parser-failed");
    Environment.ExitCode = passed ? 0 : 1;
    return;
}

ApplicationConfiguration.Initialize();
var updateArgumentIndex = Array.FindIndex(args, value => string.Equals(value, "--update-required", StringComparison.OrdinalIgnoreCase));
if (updateArgumentIndex >= 0)
{
    var updateUri = updateArgumentIndex + 1 < args.Length ? args[updateArgumentIndex + 1] : null;
    Application.Run(new UpdateRequiredForm(updateUri));
    return;
}
Application.Run(new SetupForm(ReadPublicBaseUrl()));

static string ReadPublicBaseUrl() =>
    Assembly.GetExecutingAssembly()
        .GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(item => item.Key == "LearnBotPublicBaseUrl")?
        .Value?
        .TrimEnd('/')
    ?? "https://learnbot.example.invalid";

internal sealed class SetupForm : Form
{
    private readonly string server;
    private readonly Label statusLabel;
    private readonly ProgressBar progress;
    private readonly Button retryButton;
    private readonly Button workspaceButton;
    private readonly Button browserButton;
    private readonly Button copyCodeButton;
    private readonly Button disconnectButton;
    private readonly Button reconnectButton;
    private readonly Button localCleanupButton;
    private Process? currentProcess;
    private string? approvalUrl;
    private string? userCode;

    internal SetupForm(string server)
    {
        this.server = server;
        Text = "LearnBot Local Agent 설정";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, 350);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;

        var title = new Label
        {
            Text = "이 PC를 LearnBot에 연결합니다",
            Font = new Font(Font.FontFamily, 16, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(28, 26)
        };
        var description = new Label
        {
            Text = "브라우저에서 이 PC를 승인한 다음 사용할 작업 폴더를 선택하세요.",
            AutoSize = true,
            Location = new Point(30, 72)
        };
        statusLabel = new Label
        {
            Text = "연결을 준비하고 있습니다…",
            AutoEllipsis = true,
            Location = new Point(30, 112),
            Size = new Size(455, 42)
        };
        progress = new ProgressBar
        {
            Style = ProgressBarStyle.Marquee,
            Location = new Point(30, 158),
            Size = new Size(455, 18)
        };
        retryButton = new Button
        {
            Text = "다시 시도",
            Enabled = false,
            Location = new Point(280, 248),
            Size = new Size(95, 34)
        };
        workspaceButton = new Button
        {
            Text = "작업 폴더 선택",
            Enabled = false,
            Location = new Point(385, 248),
            Size = new Size(100, 34)
        };
        browserButton = new Button
        {
            Text = "브라우저 다시 열기",
            Enabled = false,
            Location = new Point(30, 198),
            Size = new Size(135, 34)
        };
        copyCodeButton = new Button
        {
            Text = "코드 복사",
            Enabled = false,
            Location = new Point(175, 198),
            Size = new Size(95, 34)
        };
        disconnectButton = new Button
        {
            Text = "연결 해제",
            Enabled = true,
            Location = new Point(30, 248),
            Size = new Size(95, 34)
        };
        reconnectButton = new Button
        {
            Text = "이 PC 다시 연결",
            Enabled = true,
            Location = new Point(135, 248),
            Size = new Size(135, 34)
        };
        localCleanupButton = new Button
        {
            Text = "이 PC 데이터만 정리",
            Enabled = false,
            Location = new Point(30, 293),
            Size = new Size(160, 34)
        };
        retryButton.Click += async (_, _) => await EnrollAsync();
        workspaceButton.Click += async (_, _) => await SelectWorkspaceAsync();
        browserButton.Click += (_, _) => OpenApprovalUrl();
        copyCodeButton.Click += (_, _) => CopyUserCode();
        disconnectButton.Click += async (_, _) => await DisconnectAsync();
        reconnectButton.Click += async (_, _) => await ConfirmReconnectAsync();
        localCleanupButton.Click += async (_, _) => await CleanupLocalOnlyAsync();
        Shown += async (_, _) => await EnrollAsync();
        FormClosing += (_, _) => StopCurrentProcess();
        Controls.AddRange([title, description, statusLabel, progress, browserButton, copyCodeButton, disconnectButton, reconnectButton, localCleanupButton, retryButton, workspaceButton]);
    }

    private async Task EnrollAsync(bool reconnect = false)
    {
        if (!Uri.TryCreate(server, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps
            || uri.Host.EndsWith(".invalid", StringComparison.OrdinalIgnoreCase))
        {
            ShowFailure("이 설치 패키지에 운영 HTTPS 주소가 설정되지 않았습니다.");
            return;
        }

        approvalUrl = null;
        userCode = null;
        SetBusy("브라우저가 열리면 로그인하고 이 PC 연결을 승인하세요…");
        var exitCode = await RunLearnBotAsync(HandleConnectOutput, SetupCommandBuilder.Connect(server, reconnect));
        if (exitCode != 0)
        {
            ShowFailure("연결하지 못했습니다. 네트워크와 브라우저 승인 상태를 확인하세요.");
            return;
        }
        progress.Style = ProgressBarStyle.Blocks;
        statusLabel.Text = "PC 연결이 완료되었습니다. 이제 LearnBot에서 사용할 폴더를 선택하세요.";
        retryButton.Enabled = false;
        workspaceButton.Enabled = true;
        disconnectButton.Enabled = true;
        reconnectButton.Enabled = true;
        localCleanupButton.Enabled = false;
    }

    private async Task SelectWorkspaceAsync()
    {
        using var picker = new FolderBrowserDialog
        {
            Description = "LearnBot이 접근할 작업 폴더를 선택하세요.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false
        };
        if (picker.ShowDialog(this) != DialogResult.OK) return;

        SetBusy("선택한 작업 폴더를 등록하고 Local Agent를 시작하고 있습니다…");
        var exitCode = await RunLearnBotAsync(null, "workspace", "add", picker.SelectedPath);
        if (exitCode != 0)
        {
            ShowFailure("작업 폴더를 등록하지 못했습니다. 다른 폴더를 선택해 보세요.");
            workspaceButton.Enabled = true;
            return;
        }

        var host = Path.Combine(AppContext.BaseDirectory, "..", "host", "LearnBotAgentHost.exe");
        if (File.Exists(host))
        {
            Process.Start(new ProcessStartInfo(host) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
        }
        progress.Style = ProgressBarStyle.Blocks;
        statusLabel.Text = "설정이 완료되었습니다. 이 창을 닫고 LearnBot을 사용하세요.";
        retryButton.Enabled = false;
        workspaceButton.Enabled = false;
        disconnectButton.Enabled = true;
        reconnectButton.Enabled = true;
        localCleanupButton.Enabled = false;
    }

    private async Task ConfirmReconnectAsync()
    {
        if (MessageBox.Show(
                this,
                "기존 서버 장치를 확인하거나 폐기한 뒤 새 Local Agent 연결을 만듭니다. 계속할까요?",
                "이 PC 다시 연결",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Warning) != DialogResult.OK)
        {
            return;
        }
        await EnrollAsync(reconnect: true);
    }

    private async Task DisconnectAsync()
    {
        if (MessageBox.Show(
                this,
                "이 PC의 Local Agent 연결과 로컬 설정을 삭제합니다. 작업 폴더의 파일은 삭제되지 않습니다.",
                "LearnBot Local Agent 연결 해제",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Warning) != DialogResult.OK)
        {
            return;
        }
        SetBusy("서버에서 이 PC 연결을 해제하고 로컬 Agent 데이터를 삭제하고 있습니다…");
        var exitCode = await RunLearnBotAsync(null, "disconnect");
        if (exitCode != 0)
        {
            ShowFailure("연결을 해제하지 못했습니다. LearnBot 웹에서 장치를 확인한 뒤 다시 시도하세요.");
            localCleanupButton.Enabled = true;
            return;
        }
        progress.Style = ProgressBarStyle.Blocks;
        statusLabel.Text = "연결이 해제되었습니다. 작업 폴더의 파일은 변경되지 않았습니다.";
        retryButton.Enabled = true;
        workspaceButton.Enabled = false;
        disconnectButton.Enabled = false;
        reconnectButton.Enabled = true;
        localCleanupButton.Enabled = false;
    }

    private async Task CleanupLocalOnlyAsync()
    {
        if (MessageBox.Show(
                this,
                "서버의 장치 폐기 여부와 관계없이 이 PC의 Local Agent 설정만 삭제합니다. 작업 폴더 파일과 웹 로그인은 유지됩니다.",
                "이 PC 데이터만 정리",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Warning) != DialogResult.OK)
        {
            return;
        }
        SetBusy("이 PC의 Local Agent 데이터만 삭제하고 있습니다…");
        var exitCode = await RunLearnBotAsync(null, SetupCommandBuilder.Disconnect(localOnly: true));
        if (exitCode != 0)
        {
            ShowFailure("이 PC의 Local Agent 데이터를 삭제하지 못했습니다.");
            localCleanupButton.Enabled = true;
            return;
        }
        progress.Style = ProgressBarStyle.Blocks;
        statusLabel.Text = "이 PC의 Local Agent 데이터가 정리되었습니다. 서버 장치는 LearnBot 웹에서 확인하세요.";
        retryButton.Enabled = true;
        workspaceButton.Enabled = false;
        disconnectButton.Enabled = false;
        reconnectButton.Enabled = true;
        localCleanupButton.Enabled = false;
    }

    private async Task<int> RunLearnBotAsync(Action<string>? onOutput, params string[] arguments)
    {
        var executable = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "app", "learnbot.exe"));
        if (!File.Exists(executable)) return -1;
        var startInfo = new ProcessStartInfo
        {
            FileName = executable,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        foreach (var argument in arguments) startInfo.ArgumentList.Add(argument);
        currentProcess = Process.Start(startInfo);
        if (currentProcess is null) return -1;
        var output = PumpOutputAsync(currentProcess.StandardOutput, onOutput);
        var error = currentProcess.StandardError.ReadToEndAsync();
        await currentProcess.WaitForExitAsync();
        await Task.WhenAll(output, error);
        var exitCode = currentProcess.ExitCode;
        currentProcess.Dispose();
        currentProcess = null;
        return exitCode;
    }

    private static async Task PumpOutputAsync(StreamReader reader, Action<string>? onOutput)
    {
        while (await reader.ReadLineAsync() is { } line)
        {
            onOutput?.Invoke(line);
        }
    }

    private void HandleConnectOutput(string line)
    {
        if (EnrollmentOutputParser.TryReadApprovalUrl(line, out var parsedUrl))
        {
            approvalUrl = parsedUrl;
            browserButton.Enabled = true;
        }
        if (EnrollmentOutputParser.TryReadUserCode(line, out var parsedCode))
        {
            userCode = parsedCode;
            copyCodeButton.Enabled = true;
            statusLabel.Text = "브라우저에서 이 PC를 승인하세요. 사용자 코드: " + parsedCode;
        }
    }

    private void OpenApprovalUrl()
    {
        if (approvalUrl is null) return;
        Process.Start(new ProcessStartInfo(approvalUrl) { UseShellExecute = true });
    }

    private void CopyUserCode()
    {
        if (!string.IsNullOrWhiteSpace(userCode)) Clipboard.SetText(userCode);
    }

    private void SetBusy(string message)
    {
        statusLabel.Text = message;
        progress.Style = ProgressBarStyle.Marquee;
        retryButton.Enabled = false;
        workspaceButton.Enabled = false;
        browserButton.Enabled = false;
        copyCodeButton.Enabled = false;
        disconnectButton.Enabled = false;
        reconnectButton.Enabled = false;
        localCleanupButton.Enabled = false;
    }

    private void ShowFailure(string message)
    {
        progress.Style = ProgressBarStyle.Blocks;
        statusLabel.Text = message;
        retryButton.Enabled = true;
        disconnectButton.Enabled = true;
        reconnectButton.Enabled = true;
    }

    private void StopCurrentProcess()
    {
        try
        {
            if (currentProcess is { HasExited: false }) currentProcess.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException)
        {
        }
    }
}

internal static class EnrollmentOutputParser
{
    internal static bool TryReadApprovalUrl(string line, out string url)
    {
        var value = line.Trim();
        var valid = Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps;
        url = valid ? value : "";
        return valid;
    }

    internal static bool TryReadUserCode(string line, out string code)
    {
        const string prefix = "User code:";
        if (!line.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            code = "";
            return false;
        }
        code = line[prefix.Length..].Trim();
        return !string.IsNullOrWhiteSpace(code);
    }
}

internal sealed class UpdateRequiredForm : Form
{
    private readonly string? updateUri;
    private readonly Label statusLabel;

    internal UpdateRequiredForm(string? updateUri)
    {
        this.updateUri = IsTrustedUpdateUri(updateUri) ? updateUri : null;
        Text = "LearnBot Local Agent 업데이트";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(500, 205);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;

        var title = new Label
        {
            Text = "Local Agent 업데이트가 필요합니다",
            Font = new Font(Font.FontFamily, 15, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(25, 24)
        };
        statusLabel = new Label
        {
            Text = this.updateUri is null
                ? "업데이트 주소를 확인할 수 없습니다. LearnBot 웹의 Local Agent 설정에서 설치 파일을 다시 여세요."
                : "새 작업 수신을 중지했습니다. 아래 버튼으로 App Installer를 열어 업데이트를 완료하세요.",
            Location = new Point(27, 70),
            Size = new Size(445, 54)
        };
        var updateButton = new Button
        {
            Text = "업데이트 설치 열기",
            Enabled = this.updateUri is not null,
            Location = new Point(287, 145),
            Size = new Size(125, 34)
        };
        var closeButton = new Button
        {
            Text = "닫기",
            Location = new Point(420, 145),
            Size = new Size(55, 34)
        };
        updateButton.Click += (_, _) => OpenUpdate();
        closeButton.Click += (_, _) => Close();
        Controls.AddRange([title, statusLabel, updateButton, closeButton]);
    }

    internal static bool IsTrustedUpdateUri(string? value) =>
        Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps;

    private void OpenUpdate()
    {
        if (updateUri is null) return;
        Process.Start(new ProcessStartInfo(updateUri) { UseShellExecute = true });
        statusLabel.Text = "App Installer에서 업데이트를 완료한 뒤 시작 메뉴에서 LearnBot Local Agent를 실행하세요.";
    }
}

internal static class SetupCommandBuilder
{
    internal static string[] Connect(string server, bool reconnect)
    {
        var arguments = new List<string> { "connect", "--server", server, "--transport", "auto" };
        if (reconnect) arguments.Add("--reconnect");
        return [.. arguments];
    }

    internal static string[] Disconnect(bool localOnly) =>
        localOnly ? ["disconnect", "--local-only"] : ["disconnect"];
}
