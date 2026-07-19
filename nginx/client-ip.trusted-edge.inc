# Default profile: the listener stays loopback-only and may receive requests
# only from a trusted edge configured as described in the deployment runbook.
map $http_x_learnbot_client_ip $learnbot_non_cf_client_ip {
    "" $remote_addr;
    default $http_x_learnbot_client_ip;
}
map $http_cf_connecting_ip $learnbot_client_ip {
    "" $learnbot_non_cf_client_ip;
    default $http_cf_connecting_ip;
}
map $http_x_forwarded_proto $learnbot_forwarded_proto {
    default $scheme;
    "https" https;
}
map $host $learnbot_host_allowed {
    default 1;
}
