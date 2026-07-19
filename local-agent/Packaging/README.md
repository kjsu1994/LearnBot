# Windows package inputs

This directory contains templates consumed by the Windows release pipeline. Package layout:

- `app/learnbot.exe`: `LearnBot.LocalAgent.csproj` self-contained output.
- `setup/LearnBotSetup.exe`: `Setup/LearnBot.LocalAgent.Setup.csproj` self-contained output.
- `host/LearnBotAgentHost.exe`: `StartupHost/LearnBot.LocalAgent.StartupHost.csproj` self-contained output.
- `Assets/*.png`: MSIX visual assets supplied by the release pipeline.

Required literal substitutions are `{{VERSION}}` (four-part MSIX version), `{{PUBLISHER}}` (the signing certificate subject), `{{PUBLIC_BASE_URL}}` (fixed HTTPS origin), `{{APPINSTALLER_URI}}` (pilot or stable manifest URL), and `{{PACKAGE_URI}}` (immutable signed MSIX URL). `{{TIMESTAMP_URI}}` is used by signing orchestration for RFC 3161 timestamping and is intentionally absent from the manifests.

Pass `LearnBotPublicBaseUrl={{PUBLIC_BASE_URL}}` to the main and Setup project builds. Never derive the origin from an HTTP Host header or place a credential in an activation URI.

Sign PE files and the final MSIX with SHA-256, timestamp them using `{{TIMESTAMP_URI}}`, verify manifest publisher identity, and run `signtool verify /pa /all` before publication.
