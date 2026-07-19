# Windows package inputs

This directory contains templates consumed by the Windows release pipeline. Package layout:

- `app/learnbot.exe`: `LearnBot.LocalAgent.csproj` self-contained output.
- `setup/LearnBotSetup.exe`: `Setup/LearnBot.LocalAgent.Setup.csproj` self-contained output.
- `host/LearnBotAgentHost.exe`: `StartupHost/LearnBot.LocalAgent.StartupHost.csproj` self-contained output.
- `Assets/*.png`: MSIX visual assets supplied by the release pipeline.

Required literal substitutions are `{{VERSION}}` (four-part MSIX version), `{{PUBLISHER}}` (the signing certificate subject), `{{APPINSTALLER_URI}}` (the server-specific pilot or stable manifest URL), and `{{PACKAGE_URI}}` (the server-specific URL of the immutable signed MSIX). `{{PUBLIC_BASE_URL}}` is retained as a release-template input for compatibility but is not embedded in the package manifest. `{{TIMESTAMP_URI}}` is used by signing orchestration for RFC 3161 timestamping and is intentionally absent from the manifests.

For a portable pilot build, pass `LearnBotPublicBaseUrl=https://learnbot.portable.invalid` and `LearnBotAllowInsecurePrivateNetwork=true` to main, Setup, and StartupHost. The web UI activates the installed package with `learnbot-local-agent://connect?server=<current-origin>`; the activation parser accepts only an origin, allows HTTP only for an RFC1918 IPv4 literal, and never accepts credentials in the URI. Once approved, the runtime persists that server origin in the user's Agent configuration and requires update URLs to remain same-origin.

The signed portable MSIX can be copied unchanged to another LearnBot server. Run `Set-LocalAgentServerRelease.ps1` on that server to generate its `.appinstaller` and channel `release.json`; do not rebuild or re-sign the MSIX merely because the server IP or port changed.

Sign PE files and the final MSIX with SHA-256, timestamp them using `{{TIMESTAMP_URI}}`, verify manifest publisher identity, and run `signtool verify /pa /all` before publication.
