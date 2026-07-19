# Generated for one server by Initialize-LocalAgentLanHttp.ps1.
# Direct enterprise LAN profile: never trust client-supplied edge headers.
map $remote_addr $learnbot_client_ip {
    default $remote_addr;
}
map $scheme $learnbot_forwarded_proto {
    default $scheme;
}
map $host $learnbot_host_allowed {
    default 0;
    "{{SERVER_LAN_IP}}" 1;
}
