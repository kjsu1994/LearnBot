internal static class ServerActivation
{
    private const string Scheme = "learnbot-local-agent";

    internal static bool TryReadServer(
        string? value,
        string configuredOrigin,
        bool allowInsecurePrivateNetwork,
        out string serverOrigin,
        out string? error)
    {
        serverOrigin = "";
        error = null;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var activation)
            || !string.Equals(activation.Scheme, Scheme, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(activation.Host, "connect", StringComparison.OrdinalIgnoreCase))
        {
            error = "The Local Agent activation link is invalid.";
            return false;
        }

        var encodedServer = ReadQueryValue(activation.Query, "server");
        if (string.IsNullOrWhiteSpace(encodedServer))
        {
            error = "The Local Agent activation link does not contain a LearnBot server origin.";
            return false;
        }

        var candidate = Uri.UnescapeDataString(encodedServer);
        if (!ServerOriginPolicy.TryValidateServerOrigin(
                candidate,
                configuredOrigin,
                allowInsecurePrivateNetwork,
                out var server,
                out error))
        {
            return false;
        }

        serverOrigin = server.GetLeftPart(UriPartial.Authority);
        return true;
    }

    private static string? ReadQueryValue(string query, string name)
    {
        foreach (var item in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = item.IndexOf('=');
            var key = separator < 0 ? item : item[..separator];
            if (!string.Equals(Uri.UnescapeDataString(key), name, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            return separator < 0 ? "" : item[(separator + 1)..];
        }
        return null;
    }
}
