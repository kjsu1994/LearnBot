#!/usr/bin/env sh
set -eu

server="http://localhost:8083"
workspace="$(pwd)"
transport="polling"
interval_seconds="15"
install_dir="${HOME}/.local/bin"
login_id="${LEARNBOT_AGENT_LOGIN_ID:-}"
no_service="false"
plan="false"

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "${script_dir}/.." && pwd)

while [ "$#" -gt 0 ]; do
    case "$1" in
        --server)
            server="$2"; shift 2 ;;
        --workspace|--workspace-path)
            workspace="$2"; shift 2 ;;
        --transport)
            transport="$2"; shift 2 ;;
        --interval-seconds)
            interval_seconds="$2"; shift 2 ;;
        --install-dir)
            install_dir="$2"; shift 2 ;;
        --login-id)
            login_id="$2"; shift 2 ;;
        --no-service)
            no_service="true"; shift ;;
        --plan|--preview)
            plan="true"; shift ;;
        -h|--help)
            cat <<'USAGE'
Usage: scripts/learnbot-bootstrap.sh [--server URL] [--workspace PATH] [--transport polling|websocket|auto]

Installs the LearnBot CLI, pairs the local agent when needed, approves the
workspace, and starts a user-level background service on Linux or macOS.
USAGE
            exit 0 ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2 ;;
    esac
done

case "$transport" in
    polling|websocket|auto) ;;
    *)
        echo "Invalid --transport: $transport" >&2
        exit 2 ;;
esac

workspace=$(CDPATH= cd -- "$workspace" && pwd)
os_name=$(uname -s)
arch_name=$(uname -m)

case "$os_name:$arch_name" in
    Linux:x86_64|Linux:amd64) rid="linux-x64"; service_kind="systemd-user" ;;
    Linux:aarch64|Linux:arm64) rid="linux-arm64"; service_kind="systemd-user" ;;
    Darwin:x86_64|Darwin:amd64) rid="osx-x64"; service_kind="launchd" ;;
    Darwin:arm64|Darwin:aarch64) rid="osx-arm64"; service_kind="launchd" ;;
    *)
        echo "Unsupported OS/architecture: $os_name $arch_name" >&2
        exit 2 ;;
esac

project="${repo_root}/local-agent/LearnBot.LocalAgent.csproj"
exe="${install_dir}/learnbot"
config_path="${HOME}/.learnbot/agent.json"

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

if [ "$plan" = "true" ]; then
    cat <<PLAN
{
  "schema": "learnbot.local-agent.bootstrap-plan.v1",
  "repoRoot": "$(json_escape "$repo_root")",
  "server": "$(json_escape "$server")",
  "workspace": "$(json_escape "$workspace")",
  "installDir": "$(json_escape "$install_dir")",
  "runtime": "$(json_escape "$rid")",
  "serviceKind": "$(json_escape "$service_kind")",
  "noService": $no_service
}
PLAN
    exit 0
fi

if ! command -v dotnet >/dev/null 2>&1; then
    echo "dotnet SDK is required to install LearnBot Local Agent." >&2
    exit 2
fi

if [ ! -f "$project" ]; then
    echo "Local Agent project was not found: $project" >&2
    exit 2
fi

stop_service_if_running() {
    if [ "$no_service" = "true" ]; then
        return
    fi
    if [ "$service_kind" = "systemd-user" ] && command -v systemctl >/dev/null 2>&1; then
        systemctl --user stop learnbot-local-agent.service >/dev/null 2>&1 || true
    elif [ "$service_kind" = "launchd" ] && command -v launchctl >/dev/null 2>&1; then
        uid=$(id -u)
        launchctl bootout "gui/${uid}/com.learnbot.local-agent" >/dev/null 2>&1 || true
        launchctl unload "${HOME}/Library/LaunchAgents/com.learnbot.local-agent.plist" >/dev/null 2>&1 || true
    fi
}

ensure_path_hint() {
    case ":$PATH:" in
        *":${install_dir}:"*) return ;;
    esac

    profile_file="${HOME}/.profile"
    if [ "$service_kind" = "launchd" ]; then
        profile_file="${HOME}/.zprofile"
    fi

    mkdir -p "$(dirname "$profile_file")"
    if [ ! -f "$profile_file" ] || ! grep -F "$install_dir" "$profile_file" >/dev/null 2>&1; then
        {
            echo ""
            echo "# LearnBot Local Agent"
            echo "export PATH=\"${install_dir}:\$PATH\""
        } >> "$profile_file"
    fi
    export PATH="${install_dir}:$PATH"
}

ensure_configured() {
    if "$exe" status >/tmp/learnbot-status.$$ 2>/dev/null && grep -F '"configured": true' /tmp/learnbot-status.$$ >/dev/null 2>&1; then
        "$exe" workspace add "$workspace" >/dev/null
        rm -f /tmp/learnbot-status.$$
        return
    fi
    rm -f /tmp/learnbot-status.$$

    if [ -n "$login_id" ]; then
        "$exe" login --server "$server" --login-id "$login_id"
    else
        "$exe" login --server "$server"
    fi
    "$exe" pair --server "$server" --workspace "$workspace" --transport "$transport"
}

xml_escape() {
    printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'
}

install_systemd_user_service() {
    if ! command -v systemctl >/dev/null 2>&1; then
        echo "systemctl was not found. Start manually: $exe service run --interval-seconds $interval_seconds --transport $transport --config $config_path"
        return
    fi
    unit_dir="${HOME}/.config/systemd/user"
    unit_path="${unit_dir}/learnbot-local-agent.service"
    mkdir -p "$unit_dir"
    cat > "$unit_path" <<UNIT
[Unit]
Description=LearnBot Local Agent

[Service]
ExecStart=${exe} service run --interval-seconds ${interval_seconds} --transport ${transport} --config ${config_path}
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload
    systemctl --user enable --now learnbot-local-agent.service
}

install_launch_agent() {
    plist_dir="${HOME}/Library/LaunchAgents"
    plist_path="${plist_dir}/com.learnbot.local-agent.plist"
    mkdir -p "$plist_dir"
    cat > "$plist_path" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.learnbot.local-agent</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml_escape "$exe")</string>
    <string>service</string>
    <string>run</string>
    <string>--interval-seconds</string>
    <string>$(xml_escape "$interval_seconds")</string>
    <string>--transport</string>
    <string>$(xml_escape "$transport")</string>
    <string>--config</string>
    <string>$(xml_escape "$config_path")</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
</dict>
</plist>
PLIST
    uid=$(id -u)
    launchctl bootstrap "gui/${uid}" "$plist_path" >/dev/null 2>&1 || launchctl load "$plist_path"
    launchctl enable "gui/${uid}/com.learnbot.local-agent" >/dev/null 2>&1 || true
    launchctl kickstart -k "gui/${uid}/com.learnbot.local-agent" >/dev/null 2>&1 || true
}

echo "==> Stop existing LearnBot service if needed"
stop_service_if_running

echo "==> Install LearnBot CLI"
mkdir -p "$install_dir"
dotnet publish "$project" -c Release -r "$rid" --self-contained false -o "$install_dir"
ensure_path_hint

echo "==> Configure login, pairing, and workspace"
ensure_configured

if [ "$no_service" = "true" ]; then
    echo "LearnBot CLI installed. Start foreground agent:"
    echo "  $exe service run --interval-seconds $interval_seconds --transport $transport --config $config_path"
    exit 0
fi

echo "==> Install and start background service"
if [ "$service_kind" = "systemd-user" ]; then
    install_systemd_user_service
else
    install_launch_agent
fi

echo "==> Final status"
"$exe" status
echo "LearnBot bootstrap completed."
