using System.Net;

internal static class ServerOriginPolicy
{
    internal static bool TryValidateServerOrigin(
        string? value,
        string configuredOrigin,
        bool allowInsecurePrivateNetwork,
        out Uri uri,
        out string? error)
    {
        error = null;
        if (!TryParseOrigin(value, out uri))
        {
            error = "A valid absolute LearnBot server origin is required (without a path, query, or fragment).";
            return false;
        }

        if (uri.Scheme == Uri.UriSchemeHttps || uri.IsLoopback)
        {
            return true;
        }

        if (!allowInsecurePrivateNetwork)
        {
            error = "Local Agent enrollment requires HTTPS. HTTP is allowed only for localhost development unless an enterprise private-network package explicitly enables it.";
            return false;
        }

        if (!IsRfc1918Ipv4Literal(uri.Host))
        {
            error = "Enterprise HTTP mode accepts only an RFC1918 IPv4 address (10/8, 172.16/12, or 192.168/16); host names and public addresses are blocked.";
            return false;
        }

        if (TryParseOrigin(configuredOrigin, out var configured)
            && !configured.Host.EndsWith(".invalid", StringComparison.OrdinalIgnoreCase)
            && !SameOrigin(uri, configured))
        {
            error = "Enterprise HTTP mode accepts only the configured LearnBot server origin.";
            return false;
        }

        return true;
    }

    internal static bool TryResolveSameOriginUri(
        string? value,
        string serverOrigin,
        string configuredOrigin,
        bool allowInsecurePrivateNetwork,
        out Uri uri)
    {
        uri = null!;
        if (string.IsNullOrWhiteSpace(value)
            || !TryValidateServerOrigin(
                serverOrigin,
                configuredOrigin,
                allowInsecurePrivateNetwork,
                out var server,
                out _)
            || !Uri.TryCreate(value, UriKind.RelativeOrAbsolute, out var candidate))
        {
            return false;
        }

        var absolute = candidate.IsAbsoluteUri ? candidate : new Uri(server, candidate);
        if ((absolute.Scheme != Uri.UriSchemeHttps && absolute.Scheme != Uri.UriSchemeHttp)
            || !string.IsNullOrEmpty(absolute.UserInfo)
            || !SameOrigin(absolute, server))
        {
            return false;
        }

        uri = absolute;
        return true;
    }

    internal static bool IsRfc1918Ipv4Literal(string host)
    {
        if (!IPAddress.TryParse(host, out var address))
        {
            return false;
        }

        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && (
            bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
            || (bytes[0] == 192 && bytes[1] == 168));
    }

    private static bool TryParseOrigin(string? value, out Uri uri)
    {
        return Uri.TryCreate(value, UriKind.Absolute, out uri!)
            && (uri.Scheme == Uri.UriSchemeHttps || uri.Scheme == Uri.UriSchemeHttp)
            && !string.IsNullOrWhiteSpace(uri.Host)
            && string.IsNullOrEmpty(uri.UserInfo)
            && uri.AbsolutePath == "/"
            && string.IsNullOrEmpty(uri.Query)
            && string.IsNullOrEmpty(uri.Fragment);
    }

    private static bool SameOrigin(Uri left, Uri right) =>
        string.Equals(left.Scheme, right.Scheme, StringComparison.OrdinalIgnoreCase)
        && string.Equals(left.IdnHost, right.IdnHost, StringComparison.OrdinalIgnoreCase)
        && left.Port == right.Port;
}
