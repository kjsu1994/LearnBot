# Local Agent Flow Seed

The approved Local Agent execution order is fixed for the release-candidate quality fixture.

The server first prepares an approved `patch.apply` request for the user's registered workspace.

After the patch observation returns, the Local Agent may run an allowlisted `command.runAllowed` check.

The flow then records a `git.status` observation so the server can see the local workspace state.

If the flow must be reverted, the only approved restoration tool is `rollback.restore`.

The Local Agent must reject arbitrary shell commands and server-local mutation must not be the default path.
