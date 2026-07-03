using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

internal sealed partial class LearnBotLocalAgent
{
    private CliWebSessionSecretProviderProbeResult BuildCliWebSessionSecretProviderProbeResult()
    {
        const string provider = "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE";
        var windowsCandidate = OperatingSystem.IsWindows();
        var blockers = new List<string>();
        var attempted = false;
        var protectSucceeded = false;
        var unprotectSucceeded = false;
        var roundTripSucceeded = false;
        string? error = null;

        if (!windowsCandidate)
        {
            blockers.Add("Windows DPAPI probe is not available on this platform.");
        }
        else
        {
            attempted = true;
            try
            {
                var sentinel = Encoding.UTF8.GetBytes("learnbot-dpapi-no-secret-probe-v1");
                var protectedBytes = WindowsDpapiProvider.ProtectForCurrentUser(sentinel);
                protectSucceeded = protectedBytes.Length > 0;
                var unprotected = WindowsDpapiProvider.UnprotectForCurrentUser(protectedBytes);
                unprotectSucceeded = unprotected.Length > 0;
                roundTripSucceeded = sentinel.SequenceEqual(unprotected);
                if (!roundTripSucceeded)
                {
                    blockers.Add("Windows DPAPI current-user probe did not round-trip the no-secret sentinel.");
                }
            }
            catch (Exception ex)
            {
                error = ex.Message;
                blockers.Add("Windows DPAPI current-user probe failed.");
            }
        }

        var ready = attempted && protectSucceeded && unprotectSucceeded && roundTripSucceeded;
        return new CliWebSessionSecretProviderProbeResult(
            Schema: "learnbot.local-agent.web-session-secret-provider-probe-result.v1",
            Status: ready ? "NO_SECRET_PROVIDER_PROBE_SUCCEEDED" : "NO_SECRET_PROVIDER_PROBE_BLOCKED",
            Provider: provider,
            WindowsDpapiCandidate: windowsCandidate,
            ProbeAttempted: attempted,
            ProbeInputContainsTokenSecret: false,
            ProtectSucceeded: protectSucceeded,
            UnprotectSucceeded: unprotectSucceeded,
            RoundTripSucceeded: roundTripSucceeded,
            ProductionEncryptionEnabled: false,
            ProductionDecryptionEnabled: false,
            ProductionStoredSessionLoadingEnabled: false,
            PlaintextTokenSerializationAllowed: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            StoredSessionLoaded: false,
            Blockers: blockers,
            Error: error,
            Reason: "This command probes only the local OS secret-store primitive with a non-secret sentinel. It does not encrypt, decrypt, read, write, load, or print web-session token material.");
    }

    private CliWebSessionProductionArtifactCryptoPreviewResult BuildCliWebSessionProductionArtifactCryptoPreviewResult(string[] args)
    {
        const string provider = "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE";
        var previewOnly = args.Contains("--preview-only", StringComparer.OrdinalIgnoreCase);
        var windowsCandidate = OperatingSystem.IsWindows();
        var blockers = new List<string>();
        var cryptoAttempted = false;
        var accessEncrypted = false;
        var refreshEncrypted = false;
        var accessDecrypted = false;
        var refreshDecrypted = false;
        var plaintextDetected = false;
        string? accessFingerprint = null;
        string? refreshFingerprint = null;
        string? error = null;

        if (!previewOnly)
        {
            blockers.Add("production artifact crypto preview requires explicit --preview-only.");
        }
        if (!windowsCandidate)
        {
            blockers.Add("Windows DPAPI current-user provider is required for this preview.");
        }

        if (previewOnly && windowsCandidate)
        {
            cryptoAttempted = true;
            try
            {
                const string accessPlaceholder = "production-preview-access-token-material";
                const string refreshPlaceholder = "production-preview-refresh-token-material";
                var protectedAccess = WindowsDpapiProvider.ProtectUtf8ForCurrentUser(accessPlaceholder);
                var protectedRefresh = WindowsDpapiProvider.ProtectUtf8ForCurrentUser(refreshPlaceholder);
                accessEncrypted = !string.IsNullOrWhiteSpace(protectedAccess);
                refreshEncrypted = !string.IsNullOrWhiteSpace(protectedRefresh);
                plaintextDetected = protectedAccess.Contains(accessPlaceholder, StringComparison.Ordinal)
                    || protectedAccess.Contains(refreshPlaceholder, StringComparison.Ordinal)
                    || protectedRefresh.Contains(accessPlaceholder, StringComparison.Ordinal)
                    || protectedRefresh.Contains(refreshPlaceholder, StringComparison.Ordinal);

                var decryptedAccess = WindowsDpapiProvider.UnprotectUtf8ForCurrentUser(protectedAccess);
                var decryptedRefresh = WindowsDpapiProvider.UnprotectUtf8ForCurrentUser(protectedRefresh);
                accessDecrypted = decryptedAccess == accessPlaceholder;
                refreshDecrypted = decryptedRefresh == refreshPlaceholder;
                accessFingerprint = TokenFingerprint(decryptedAccess);
                refreshFingerprint = TokenFingerprint(decryptedRefresh);

                if (!accessDecrypted || !refreshDecrypted)
                {
                    blockers.Add("production artifact crypto preview could not verify DPAPI round-trip for placeholder token material.");
                }
                if (plaintextDetected)
                {
                    blockers.Add("plaintext placeholder token material was detected in protected preview output.");
                }
            }
            catch (Exception ex)
            {
                error = ex.Message;
                blockers.Add("production artifact crypto preview failed.");
            }
        }

        var succeeded = cryptoAttempted && accessEncrypted && refreshEncrypted && accessDecrypted && refreshDecrypted && !plaintextDetected && blockers.Count == 0;
        return new CliWebSessionProductionArtifactCryptoPreviewResult(
            Schema: "learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1",
            Status: succeeded ? "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED" : "BLOCKED_OR_FAILED",
            Provider: provider,
            PreviewOnly: previewOnly,
            WindowsDpapiCandidate: windowsCandidate,
            CryptoAttempted: cryptoAttempted,
            ArtifactSchema: "learnbot.local-agent.web-session-artifact.v1",
            EncryptionRequired: true,
            EncryptedAccessTokenPresent: accessEncrypted,
            EncryptedRefreshTokenPresent: refreshEncrypted,
            DecryptionVerified: accessDecrypted && refreshDecrypted,
            AccessTokenFingerprint: accessFingerprint,
            RefreshTokenFingerprint: refreshFingerprint,
            PlaintextTokenSerializationAllowed: false,
            PlaintextTokenSerializationDetected: plaintextDetected,
            ArtifactWriteEnabled: false,
            LocalSessionArtifactWritten: false,
            ArtifactReadEnabled: false,
            StoredSessionLoaded: false,
            ProductionStoredSessionLoadingEnabled: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            Blockers: blockers,
            Error: error,
            Reason: "This preview verifies DPAPI-backed production artifact crypto metadata with placeholder token material only. It does not write web-session.json, read stored sessions, load credentials, refresh tokens, or print token material.");
    }

    private CliWebSessionProductionArtifactWriterPreviewResult BuildCliWebSessionProductionArtifactWriterPreviewResult(string[] args)
    {
        var previewOnly = args.Contains("--preview-only", StringComparer.OrdinalIgnoreCase);
        var writeRequested = args.Contains("--write", StringComparer.OrdinalIgnoreCase);
        var filteredArgs = args.Where(arg => !string.Equals(arg, "--preview-only", StringComparison.OrdinalIgnoreCase)).ToArray();
        var preflight = BuildCliWebSessionArtifactWriterPreflightResult(filteredArgs);
        var cryptoPreview = previewOnly
            ? BuildCliWebSessionProductionArtifactCryptoPreviewResult(["--preview-only"])
            : BuildCliWebSessionProductionArtifactCryptoPreviewResult([]);
        var blockers = preflight.Blockers.ToList();
        if (!previewOnly)
        {
            blockers.Add("production artifact writer preview requires explicit --preview-only.");
        }
        if (cryptoPreview.Status != "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED")
        {
            blockers.Add("production artifact crypto preview proof is required before preparing the writer body preview.");
        }

        var bodyPreviewPrepared = previewOnly && preflight.ArtifactWriterPreflightPassed && cryptoPreview.Status == "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED";
        var bodyPreview = bodyPreviewPrepared
            ? BuildProductionEncryptedWebSessionArtifactShapePreview(preflight.ServerUrl)
            : null;
        var bodySha = bodyPreviewPrepared
            ? Sha256Hex(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(bodyPreview, JsonOptions)))
            : null;

        return new CliWebSessionProductionArtifactWriterPreviewResult(
            Schema: "learnbot.local-agent.web-session-production-artifact-writer-preview-result.v1",
            Status: bodyPreviewPrepared ? "PRODUCTION_ARTIFACT_WRITER_PREVIEW_READY" : "BLOCKED_OR_FAILED",
            PreviewOnly: previewOnly,
            SessionPath: WebSessionPath(),
            Preflight: preflight,
            CryptoPreview: cryptoPreview,
            ArtifactBodyPreviewPrepared: bodyPreviewPrepared,
            ArtifactBodyPreview: bodyPreview,
            ArtifactBodyPreviewSha256: bodySha,
            AtomicWritePlan: BuildProductionArtifactAtomicWritePlan(writeRequested),
            BodyFieldNames: [
                "schema",
                "serverUrl",
                "encryptedAccessToken",
                "encryptedRefreshToken",
                "expiresAt",
                "refreshExpiresAt",
                "createdAt",
                "encryption"
            ],
            EncryptionProvider: "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE",
            PlaintextTokenSerializationAllowed: false,
            PlaintextTokenSerializationDetected: false,
            ArtifactWriteEnabled: false,
            LocalSessionArtifactWritten: false,
            ArtifactReadEnabled: false,
            StoredSessionLoaded: false,
            ProductionStoredSessionLoadingEnabled: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            Blockers: blockers,
            Reason: "This preview combines approved claim-result metadata and DPAPI crypto proof to prepare the production artifact body shape in memory only. It does not persist web-session.json, load stored sessions, refresh tokens, or print token material.");
    }

    private CliWebSessionProductionArtifactReaderPreviewResult BuildCliWebSessionProductionArtifactReaderPreviewResult(string[] args)
    {
        var previewOnly = args.Contains("--preview-only", StringComparer.OrdinalIgnoreCase);
        var cryptoPreview = previewOnly
            ? BuildCliWebSessionProductionArtifactCryptoPreviewResult(["--preview-only"])
            : BuildCliWebSessionProductionArtifactCryptoPreviewResult([]);
        var blockers = new List<string>();
        if (!previewOnly)
        {
            blockers.Add("production artifact reader preview requires explicit --preview-only.");
        }
        if (cryptoPreview.Status != "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED")
        {
            blockers.Add("production artifact crypto preview proof is required before modeling reader/decrypt readiness.");
        }

        var ready = previewOnly && cryptoPreview.Status == "PRODUCTION_ARTIFACT_CRYPTO_PREVIEW_SUCCEEDED";
        return new CliWebSessionProductionArtifactReaderPreviewResult(
            Schema: "learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1",
            Status: ready ? "PRODUCTION_ARTIFACT_READER_PREVIEW_READY" : "BLOCKED_OR_FAILED",
            PreviewOnly: previewOnly,
            SessionPath: WebSessionPath(),
            CryptoPreview: cryptoPreview,
            RequiredArtifactSchema: "learnbot.local-agent.web-session-artifact.v1",
            AcceptedEncryptionProvider: "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE",
            RequiredFields: [
                "schema",
                "serverUrl",
                "encryptedAccessToken",
                "encryptedRefreshToken",
                "expiresAt",
                "refreshExpiresAt",
                "createdAt",
                "encryption"
            ],
            FileReadEnabled: false,
            FileReadAttempted: false,
            JsonParseEnabled: false,
            SchemaValidationEnabled: false,
            ProductionDecryptionPrimitiveVerified: ready,
            ProductionDecryptionEnabled: false,
            AccessTokenLoaded: false,
            RefreshTokenLoaded: false,
            StoredSessionLoaded: false,
            StoredSessionUsableForServerPlanFetch: false,
            ServerPlanFetchFromStoredSessionEnabled: false,
            TokenRefreshEnabled: false,
            PlaintextTokenSerializationAllowed: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            FollowUpCommand: "learnbot session server-plan-readiness",
            Blockers: blockers,
            Reason: "This preview models production artifact read/decrypt readiness without reading web-session.json, parsing stored JSON, decrypting stored token fields, loading credentials, refreshing tokens, or enabling stored-session server-plan fetch.");
    }

    private CliWebSessionStoredSessionAuthReadinessReport BuildCliWebSessionStoredSessionAuthReadinessReport()
    {
        var readerPreview = BuildCliWebSessionProductionArtifactReaderPreviewResult(["--preview-only"]);
        var blockers = new List<string>
        {
            "real browser-approved claim result storage is not implemented.",
            "guarded production artifact file read/decrypt execution is disabled.",
            "stored-session token refresh is disabled.",
            "stored-session authenticated server-plan fetch is disabled."
        };
        if (readerPreview.Status != "PRODUCTION_ARTIFACT_READER_PREVIEW_READY")
        {
            blockers.Add("production artifact reader/decrypt primitive proof is not ready on this platform.");
        }

        return new CliWebSessionStoredSessionAuthReadinessReport(
            Schema: "learnbot.local-agent.web-session-stored-session-auth-readiness.v1",
            Status: "STORED_SESSION_AUTH_DISABLED_PREVIEW",
            SessionPath: WebSessionPath(),
            ReaderPreview: readerPreview,
            RequiresBrowserClaimResult: true,
            RequiresProductionArtifactRead: true,
            RequiresAccessToken: true,
            RequiresRefreshToken: true,
            RequiresExpiresAt: true,
            RequiresRefreshExpiresAt: true,
            ExpiryValidationEnabled: false,
            RefreshEligibilityCheckEnabled: false,
            TokenRefreshEnabled: false,
            AccessTokenLoaded: false,
            RefreshTokenLoaded: false,
            StoredSessionLoaded: false,
            StoredSessionUsableForServerPlanFetch: false,
            ServerPlanFetchFromStoredSessionEnabled: false,
            EnvironmentTokenFallbackAllowed: true,
            FileReadAttempted: false,
            JsonParseAttempted: false,
            DecryptionAttempted: false,
            NetworkRefreshAttempted: false,
            RequestCreated: false,
            MutationAllowed: false,
            LocalAgentTokenUsed: false,
            TokenSecretPrinted: false,
            FollowUpCommand: "learnbot session server-plan-readiness",
            Blockers: blockers,
            Reason: "This readiness preview fixes the token expiry and refresh preconditions for future stored-session auth while refusing to read stored token files, refresh tokens, or authenticate server-plan fetches from stored session state.");
    }

    private static CliWebSessionProductionArtifactAtomicWritePlan BuildProductionArtifactAtomicWritePlan(bool writeRequested)
    {
        var path = WebSessionPath();
        return new CliWebSessionProductionArtifactAtomicWritePlan(
            Schema: "learnbot.local-agent.web-session-production-artifact-atomic-write-plan.v1",
            SessionPath: path,
            TempPathPattern: path + ".tmp-<nonce>",
            ParentDirectoryCreationRequired: true,
            AtomicReplaceRequired: true,
            WriteRequested: writeRequested,
            WriteEnabled: false,
            WriteRefused: writeRequested,
            LocalSessionArtifactWritten: false,
            ArtifactReadAfterWriteEnabled: false,
            StoredSessionLoadingEnabled: false,
            PlaintextTokenSerializationAllowed: false,
            TokenSecretPrinted: false,
            LocalAgentTokenUsed: false,
            RefusalReason: writeRequested
                ? "production web-session artifact writing is disabled; this preview only stages the atomic write plan."
                : "production web-session artifact writing stays disabled until a separate guarded write switch is implemented.");
    }

    private static IReadOnlyDictionary<string, object?> BuildProductionEncryptedWebSessionArtifactShapePreview(string serverUrl) =>
        new Dictionary<string, object?>
        {
            ["schema"] = "learnbot.local-agent.web-session-artifact.v1",
            ["serverUrl"] = serverUrl,
            ["encryptedAccessToken"] = "<dpapi-current-user-protected-access-token>",
            ["encryptedRefreshToken"] = "<dpapi-current-user-protected-refresh-token>",
            ["expiresAt"] = "<expires-at-from-approved-claim-result>",
            ["refreshExpiresAt"] = "<refresh-expires-at-from-approved-claim-result>",
            ["createdAt"] = "<created-at>",
            ["encryption"] = new Dictionary<string, object?>
            {
                ["required"] = true,
                ["provider"] = "WINDOWS_DPAPI_CURRENT_USER_OR_OS_SECRET_STORE",
                ["scope"] = "CURRENT_USER",
                ["plaintextTokenSerializationAllowed"] = false,
                ["keyPersistedInArtifact"] = false
            }
        };
}

internal static class WindowsDpapiProvider
{
    public static string ProtectUtf8ForCurrentUser(string value)
    {
        return Convert.ToBase64String(ProtectForCurrentUser(Encoding.UTF8.GetBytes(value)));
    }

    public static string UnprotectUtf8ForCurrentUser(string value)
    {
        return Encoding.UTF8.GetString(UnprotectForCurrentUser(Convert.FromBase64String(value)));
    }

    public static byte[] ProtectForCurrentUser(byte[] plaintext)
    {
        return CryptProtect(plaintext, protect: true);
    }

    public static byte[] UnprotectForCurrentUser(byte[] ciphertext)
    {
        return CryptProtect(ciphertext, protect: false);
    }

    private static byte[] CryptProtect(byte[] input, bool protect)
    {
        var inputBlob = default(DataBlob);
        var outputBlob = default(DataBlob);
        try
        {
            inputBlob.cbData = input.Length;
            inputBlob.pbData = Marshal.AllocHGlobal(input.Length);
            Marshal.Copy(input, 0, inputBlob.pbData, input.Length);

            var ok = protect
                ? CryptProtectData(ref inputBlob, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref outputBlob)
                : CryptUnprotectData(ref inputBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, ref outputBlob);
            if (!ok)
            {
                throw new InvalidOperationException($"DPAPI {(protect ? "protect" : "unprotect")} failed with Win32 error {Marshal.GetLastWin32Error()}.");
            }

            var output = new byte[outputBlob.cbData];
            Marshal.Copy(outputBlob.pbData, output, 0, output.Length);
            return output;
        }
        finally
        {
            if (inputBlob.pbData != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(inputBlob.pbData);
            }
            if (outputBlob.pbData != IntPtr.Zero)
            {
                LocalFree(outputBlob.pbData);
            }
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DataBlob pDataIn,
        string? szDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DataBlob pDataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DataBlob pDataIn,
        IntPtr ppszDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DataBlob pDataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr hMem);
}

internal sealed record CliWebSessionSecretProviderProbeResult(
    string Schema,
    string Status,
    string Provider,
    bool WindowsDpapiCandidate,
    bool ProbeAttempted,
    bool ProbeInputContainsTokenSecret,
    bool ProtectSucceeded,
    bool UnprotectSucceeded,
    bool RoundTripSucceeded,
    bool ProductionEncryptionEnabled,
    bool ProductionDecryptionEnabled,
    bool ProductionStoredSessionLoadingEnabled,
    bool PlaintextTokenSerializationAllowed,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    bool StoredSessionLoaded,
    IReadOnlyList<string> Blockers,
    string? Error,
    string Reason);

internal sealed record CliWebSessionProductionArtifactCryptoPreviewResult(
    string Schema,
    string Status,
    string Provider,
    bool PreviewOnly,
    bool WindowsDpapiCandidate,
    bool CryptoAttempted,
    string ArtifactSchema,
    bool EncryptionRequired,
    bool EncryptedAccessTokenPresent,
    bool EncryptedRefreshTokenPresent,
    bool DecryptionVerified,
    string? AccessTokenFingerprint,
    string? RefreshTokenFingerprint,
    bool PlaintextTokenSerializationAllowed,
    bool PlaintextTokenSerializationDetected,
    bool ArtifactWriteEnabled,
    bool LocalSessionArtifactWritten,
    bool ArtifactReadEnabled,
    bool StoredSessionLoaded,
    bool ProductionStoredSessionLoadingEnabled,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    IReadOnlyList<string> Blockers,
    string? Error,
    string Reason);

internal sealed record CliWebSessionProductionArtifactWriterPreviewResult(
    string Schema,
    string Status,
    bool PreviewOnly,
    string SessionPath,
    CliWebSessionArtifactWriterPreflightResult Preflight,
    CliWebSessionProductionArtifactCryptoPreviewResult CryptoPreview,
    bool ArtifactBodyPreviewPrepared,
    IReadOnlyDictionary<string, object?>? ArtifactBodyPreview,
    string? ArtifactBodyPreviewSha256,
    CliWebSessionProductionArtifactAtomicWritePlan AtomicWritePlan,
    IReadOnlyList<string> BodyFieldNames,
    string EncryptionProvider,
    bool PlaintextTokenSerializationAllowed,
    bool PlaintextTokenSerializationDetected,
    bool ArtifactWriteEnabled,
    bool LocalSessionArtifactWritten,
    bool ArtifactReadEnabled,
    bool StoredSessionLoaded,
    bool ProductionStoredSessionLoadingEnabled,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionProductionArtifactReaderPreviewResult(
    string Schema,
    string Status,
    bool PreviewOnly,
    string SessionPath,
    CliWebSessionProductionArtifactCryptoPreviewResult CryptoPreview,
    string RequiredArtifactSchema,
    string AcceptedEncryptionProvider,
    IReadOnlyList<string> RequiredFields,
    bool FileReadEnabled,
    bool FileReadAttempted,
    bool JsonParseEnabled,
    bool SchemaValidationEnabled,
    bool ProductionDecryptionPrimitiveVerified,
    bool ProductionDecryptionEnabled,
    bool AccessTokenLoaded,
    bool RefreshTokenLoaded,
    bool StoredSessionLoaded,
    bool StoredSessionUsableForServerPlanFetch,
    bool ServerPlanFetchFromStoredSessionEnabled,
    bool TokenRefreshEnabled,
    bool PlaintextTokenSerializationAllowed,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    string FollowUpCommand,
    IReadOnlyList<string> Blockers,
    string Reason);

internal sealed record CliWebSessionProductionArtifactAtomicWritePlan(
    string Schema,
    string SessionPath,
    string TempPathPattern,
    bool ParentDirectoryCreationRequired,
    bool AtomicReplaceRequired,
    bool WriteRequested,
    bool WriteEnabled,
    bool WriteRefused,
    bool LocalSessionArtifactWritten,
    bool ArtifactReadAfterWriteEnabled,
    bool StoredSessionLoadingEnabled,
    bool PlaintextTokenSerializationAllowed,
    bool TokenSecretPrinted,
    bool LocalAgentTokenUsed,
    string RefusalReason);

internal sealed record CliWebSessionStoredSessionAuthReadinessReport(
    string Schema,
    string Status,
    string SessionPath,
    CliWebSessionProductionArtifactReaderPreviewResult ReaderPreview,
    bool RequiresBrowserClaimResult,
    bool RequiresProductionArtifactRead,
    bool RequiresAccessToken,
    bool RequiresRefreshToken,
    bool RequiresExpiresAt,
    bool RequiresRefreshExpiresAt,
    bool ExpiryValidationEnabled,
    bool RefreshEligibilityCheckEnabled,
    bool TokenRefreshEnabled,
    bool AccessTokenLoaded,
    bool RefreshTokenLoaded,
    bool StoredSessionLoaded,
    bool StoredSessionUsableForServerPlanFetch,
    bool ServerPlanFetchFromStoredSessionEnabled,
    bool EnvironmentTokenFallbackAllowed,
    bool FileReadAttempted,
    bool JsonParseAttempted,
    bool DecryptionAttempted,
    bool NetworkRefreshAttempted,
    bool RequestCreated,
    bool MutationAllowed,
    bool LocalAgentTokenUsed,
    bool TokenSecretPrinted,
    string FollowUpCommand,
    IReadOnlyList<string> Blockers,
    string Reason);
