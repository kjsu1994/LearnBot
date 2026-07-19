# LearnBot Local Agent

This is the first MVP skeleton for the per-user Local Agent.

## 사내망 HTTP 배포 및 사용자 설치

현재 우선 운영 방식은 **부서마다 LearnBot 전체(RAG 및 Local Agent 서버 기능)를 Compose로 실행하는 회사 내부망 HTTP pilot**이다. 서버 주소는 제품이나 서명된 MSIX에 고정되어 있지 않다. MSIX는 중앙 release runner에서 한 번 빌드·서명해 모든 부서 서버에서 재사용하고, 각 서버 PC에서는 자신의 실제 RFC1918 사설 IPv4 주소(`10/8`, `172.16/12`, `192.168/16`)와 포트가 들어간 `.appinstaller` 및 `release.json`만 생성한다.

전체 흐름은 다음과 같다.

| 주체 | 할 일 |
| --- | --- |
| 서버 운영자 | 서버별 LAN 주소 초기화, Compose 실행, 서명된 범용 MSIX와 서버별 pilot 메타데이터 게시 |
| 사내 IT | 사용자 PC에 사내 코드 서명 인증서의 신뢰 체인과 필요한 앱 설치 정책 배포 |
| 일반 사용자 | LearnBot 화면에서 설치 파일 다운로드, 앱 실행, PC 연결 승인, 작업 폴더 선택 |

일반 사용자는 서버 PC에 직접 접근할 필요가 없다. 다만 화면의 다운로드 기능만 구현되어 있다고 바로 파일을 받을 수 있는 것은 아니다. 서버의 `artifacts/local-agent`에 서명된 `release.json`, `.appinstaller`, 버전별 `.msix`가 게시되고 Nginx가 이를 제공해야 화면의 다운로드 버튼이 활성화된다.

### 중요한 배포 원칙

- `http://192.168.1.72:8083` 같은 주소는 예시일 뿐 고정값이 아니다. 초기화 스크립트가 실행된 서버의 실제 주소를 기준으로 `$Deployment.publicBaseUrl`을 만든다.
- HTTP 배포는 `pilot` 채널에서만 허용된다. HTTP 패키지를 `stable`로 게시하거나 승격하는 작업은 차단된다.
- 서명된 `.msix`에는 운영 서버 IP나 포트를 넣지 않는다. 사용자가 누른 `이 PC 연결` 링크가 현재 브라우저 origin을 Agent에 전달하며, Agent는 승인 후 그 origin을 `%USERPROFILE%\.learnbot\agent.json`에 저장한다. 링크에는 토큰이나 자격 증명을 넣지 않는다.
- `.appinstaller`와 channel `release.json`은 서버별 파일이다. 서버 IP나 포트가 바뀌면 초기화와 `Set-LocalAgentServerRelease.ps1`을 다시 실행하면 되며, 동일 MSIX를 다시 빌드하거나 서명할 필요가 없다.
- 부서별 서버에서 새 self-signed 인증서를 만들지 않는다. 모든 부서는 중앙에서 검증한 동일 MSIX와 그 MSIX 서명자와 정확히 일치하는 공개 CER를 사용한다. 서버 PC에는 private key가 필요하지 않다.
- 서버 IP는 가능하면 고정 IP 또는 DHCP reservation으로 유지한다. 이미 연결된 PC가 있는 서버의 주소를 바꾸면 해당 PC는 새 화면에서 다시 연결해야 한다.
- 사용자 PC 자신의 IP는 패키지에 저장되거나 제한 조건으로 사용되지 않는다. Windows 11 x64 PC가 회사망에서 해당 서버 IP와 포트에 접근할 수 있으면 된다.
- HTTP 통신은 암호화되지 않는다. 서버 방화벽에서 실제 회사 사용자망 CIDR만 허용하고 인터넷, 게스트 Wi-Fi 및 불특정 네트워크에는 포트를 공개하지 않는다.
- 사용자 PC에 배포하는 사내 인증서는 현재 단계에서 **MSIX 코드 서명 신뢰용**이다. HTTP 통신을 암호화하는 TLS 인증서가 아니며, HTTPS 전환 시에는 별도의 서버 TLS 인증서가 필요하다.

자세한 운영, 업데이트, 롤백 및 향후 HTTPS 전환 절차는 [`../docs/local-agent-deployment-runbook.md`](../docs/local-agent-deployment-runbook.md)를 참고한다.

## 서버 운영자용 설치 파일 게시 절차

아래 명령은 저장소 루트에서 실행한다.

### 가장 간단한 부서 서버 시작

새 부서 서버에는 이 소스와 함께 중앙에서 검증한 다음 두 artifact를 복사한다. 이 파일들은 Git에서 제외되므로 `git clone`만으로는 포함되지 않는다.

- `artifacts/local-agent/releases/<version>/`: 서명된 범용 MSIX와 불변 `release.json`
- `artifacts/local-agent/trust/*.cer`: 위 MSIX 서명자와 지문이 같은 공개 인증서

Docker Desktop이 실행 중이면 다음 한 명령이 기본 gateway가 있는 실제 회사 LAN 인터페이스를 선택하고, 그 PC의 주소로 release 포인터를 만든 뒤 전체 Compose를 시작한다. Docker/WSL 가상 NIC는 기본 gateway가 없는 경우 자동 선택 대상에서 제외된다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\local-agent\Start-LearnBotDepartmentServer.ps1 `
  -Port 8083
```

VPN 등으로 기본 gateway가 여러 개면 회사 LAN IP만 명시한다. 이 값은 코드나 MSIX에 저장되는 하드코딩 값이 아니라 해당 서버의 실행 시 설정이다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\local-agent\Start-LearnBotDepartmentServer.ps1 `
  -ServerLanIp "<현재 부서 서버에 실제로 할당된 사설 IPv4>" `
  -Port 8083
```

Compose를 시작하지 않고 주소·release·인증서 일치만 검사하려면 `-ConfigureOnly`를 추가한다. 성공 결과의 `userPortalUrl`이 해당 부서 사용자에게 안내할 주소다.

### 1. 사전 준비

서버 PC에는 다음 항목이 필요하다.

- Docker와 Docker Compose v2.24.4 이상
- 사용자 PC가 접근할 고정 또는 DHCP 예약 사설 IPv4 주소
- 선택한 포트로 들어오는 연결을 회사 사용자망 CIDR에만 허용하는 방화벽 규칙

패키지를 빌드하고 서명하는 Windows PC에는 다음 항목이 필요하다. 서버 PC와 같은 컴퓨터여도 되고 별도의 release runner여도 된다.

- .NET 10 SDK
- Windows SDK의 x64 `makeappx.exe`와 `signtool.exe`
- private key와 Code Signing EKU(`1.3.6.1.5.5.7.3.3`)가 있는 사내 코드 서명 인증서
- 서명 인증서의 Subject와 정확히 같은 MSIX `Publisher` 값
- 서명 결과가 최종적으로 서버의 `artifacts/local-agent`에 배치되는 파일 공유 또는 배포 경로

사내 IT는 설치 전에 사용자 PC의 Local Machine 인증서 저장소에 사내 root/intermediate 신뢰 체인과 조직 정책상 필요한 publisher trust를 GPO 또는 MDM으로 배포해야 한다. 코드 서명 private key는 release runner 밖으로 내보내지 않는다.

### 2. 서버별 주소와 Compose 설정 생성

먼저 게시할 4자리 버전을 정한다. 같은 버전은 다시 게시하지 않는다.

```powershell
$Version = "0.2.0.0"
$Minimum = "0.1.0.0"

# 활성 상태인 사설 IPv4가 하나면 자동으로 선택한다.
$Deployment = .\scripts\local-agent\Initialize-LocalAgentLanHttp.ps1 `
  -LatestVersion $Version `
  -MinimumVersion $Minimum | ConvertFrom-Json

$Origin = $Deployment.publicBaseUrl
$Origin
```

사설 IPv4가 여러 개인 서버에서는 회사 LAN에 연결된 실제 주소를 명시한다. 기본 포트는 `8083`이며, 다른 포트가 필요하면 `-Port`로 지정한다.

```powershell
$Deployment = .\scripts\local-agent\Initialize-LocalAgentLanHttp.ps1 `
  -ServerLanIp "<이 서버 PC에 실제로 할당된 사내망 IPv4>" `
  -Port 8083 `
  -LatestVersion $Version `
  -MinimumVersion $Minimum | ConvertFrom-Json

$Origin = $Deployment.publicBaseUrl
```

초기화 스크립트는 Git에 커밋되지 않는 다음 서버별 파일을 만든다.

- `.env.local-agent-lan-http`: bind 주소, 포트, 공개 origin 및 버전
- `deploy/local-agent-lan-http.generated.inc`: 해당 서버 IP만 허용하는 Nginx Host 정책

스크립트가 선택한 IP는 현재 서버 PC에 실제로 할당된 주소인지 검사한다. 여러 NIC 중 임의의 주소를 추측하지 않으므로 오류가 나면 올바른 `-ServerLanIp`를 지정한다.

### 3. 서버 실행

먼저 병합된 Compose 설정을 확인한 뒤 서버를 실행한다.

```powershell
docker compose --env-file .env.local-agent-lan-http `
  -f docker-compose.yml `
  -f docker-compose.local-agent-release.yml `
  -f docker-compose.local-agent-lan-http.yml `
  config

docker compose --env-file .env.local-agent-lan-http `
  -f docker-compose.yml `
  -f docker-compose.local-agent-release.yml `
  -f docker-compose.local-agent-lan-http.yml `
  up -d --build
```

`config` 결과에서 Nginx가 `$Origin`의 IP와 포트에 bind되는지 확인한다. 포트는 방화벽에서 회사 사용자망에만 개방한다.

### 4. 사내 코드 서명 인증서로 pilot 게시

`$Publisher`에는 서명 인증서의 전체 Subject를 정확히 입력하고, `$Thumbprint`에는 해당 인증서의 SHA-1 thumbprint를 입력한다. 다음 값은 자리표시자이므로 실제 조직 값으로 바꿔야 한다.

사내 CA 인증서가 아직 준비되지 않은 최초 pilot에서는 다음 스크립트로 release runner 사용자 저장소에 비내보내기 self-signed 코드 서명 키를 만들 수 있다. 공개 CER만 `artifacts/local-agent/trust/LearnBotLocalAgentPilot.cer`로 내보내며, 이 CER를 설치 전에 GPO/MDM으로 모든 pilot PC의 `Local Computer\Trusted People`에 배포한다. private key는 복사하지 않는다.

```powershell
$Signing = powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\local-agent\release\Initialize-LocalAgentPilotSigningCertificate.ps1 |
  ConvertFrom-Json

$Publisher = $Signing.subject
$Thumbprint = $Signing.thumbprint
```

```powershell
# 사내 CA 인증서를 이미 발급받았다면 위 pilot 생성 단계 대신 다음 두 값을 지정한다.
# $Publisher = "CN=<코드 서명 인증서의 정확한 Subject>"
# $Thumbprint = "<코드 서명 인증서 SHA-1 thumbprint>"
$SdkBin = "C:\Program Files (x86)\Windows Kits\10\bin\<설치된 SDK 버전>\x64"

.\scripts\local-agent\release\Publish-LocalAgentRelease.ps1 `
  -Version $Version `
  -MinimumSupportedVersion $Minimum `
  -PublicBaseUrl $Origin `
  -Publisher $Publisher `
  -Channel pilot `
  -ArtifactsRoot .\artifacts\local-agent `
  -WindowsSdkBin $SdkBin `
  -CertificateThumbprint $Thumbprint `
  -EnterpriseManagedTrust `
  -AllowInsecurePrivateNetwork `
  -PortableServerPackage
```

`dotnet`이 PATH에 없으면 `-DotNetPath "<dotnet.exe 경로>"`를 추가한다. 기본 timestamp 서버에 접근할 수 없는 폐쇄망이면 조직의 RFC 3161 timestamp 주소를 `-TimestampUrl`로 지정해야 한다.

빌드와 서명이 끝나면 artifact 구조, 버전, 해시 및 서명을 검사한다.

```powershell
.\scripts\local-agent\release\Test-LocalAgentRelease.ps1 `
  -ArtifactsRoot .\artifacts\local-agent `
  -Channel pilot `
  -ExpectedLatestVersion $Version `
  -ExpectedMinimumSupportedVersion $Minimum `
  -RequireSignature `
  -SignToolPath "$SdkBin\signtool.exe"
```

별도 release runner에서 게시했다면 검증된 `artifacts/local-agent` 내용을 서버가 mount하는 동일한 경로로 전달한다. 서명된 파일이나 `release.json`을 수동으로 수정하지 않는다.

다른 서버 PC에서는 `releases/<version>`의 서명된 MSIX와 release metadata만 복사한 뒤 아래 명령으로 그 서버 주소의 `.appinstaller` 및 channel metadata를 만든다. 이 단계에는 서명 인증서나 private key가 필요하지 않으며 MSIX 해시는 바뀌지 않는다.

```powershell
.\scripts\local-agent\release\Set-LocalAgentServerRelease.ps1 `
  -Version $Version `
  -PublicBaseUrl $Origin `
  -ArtifactsRoot .\artifacts\local-agent `
  -AllowInsecurePrivateNetwork
```

### 5. 다운로드 화면 확인

서버별 `$Origin`을 사용해 metadata와 화면을 확인한다.

```powershell
Invoke-WebRequest -UseBasicParsing "$Origin/downloads/local-agent/pilot/release.json"
Start-Process "$Origin/settings/local-agent"
```

`release.json` 요청이 성공하고 화면에 `Windows 11 x64용 다운로드`가 표시되면 사용자가 화면에서 설치 파일을 받을 수 있다. `설치 파일 준비 중`이 계속 표시되면 pilot artifact가 아직 없거나 Nginx에서 해당 파일을 읽지 못하는 상태다.

## 일반 사용자용 설치 및 연결 방법

사용자는 서버 PC에 접속하거나 명령어를 실행할 필요가 없다.

1. 회사 네트워크에 연결한 상태에서 IT가 안내한 `http://<해당 LearnBot 서버의 LAN IP>:<포트>/settings/local-agent`에 접속하고 LearnBot에 로그인한다.
2. `Windows 11 x64용 다운로드`를 누른다.
3. 화면에 `회사 인증서 사전 배포 필요`가 표시되면 IT가 공개 CER를 사용자 PC의 `Local Computer\TrustedPeople`에 먼저 배포했는지 확인한다. 화면의 공개 인증서 링크는 IT·관리자용이며 일반 사용자가 Current User 저장소에 넣어서는 해결되지 않는다.
4. 다운로드한 `LearnBotLocalAgent.appinstaller` 파일을 열고 Windows App Installer에서 게시자가 회사의 코드 서명 게시자와 일치하는지 확인한 뒤 설치한다. 인증서 경고가 보이면 우회하지 말고 IT에 문의한다.
5. 설치가 끝나면 설치 파일을 받은 LearnBot 화면의 `이 PC 연결`을 누른다. 이 버튼이 현재 서버 주소를 안전 정책에 맞춰 Agent에 전달한다. 시작 메뉴에서만 처음 실행하면 서버 주소가 없으므로 먼저 LearnBot 화면에서 연결해야 한다.
6. 브라우저에 표시된 PC 이름, 요청 코드 및 설치 식별자를 확인하고 `이 PC 승인`을 누른다. 본인이 시작하지 않은 요청은 거부한다.
7. Local Agent 창으로 돌아가 LearnBot이 접근해도 되는 작업 폴더를 선택한다. Agent는 선택한 폴더 밖을 작업 폴더로 사용하지 않는다.
8. LearnBot 화면의 `등록된 PC`에서 연결 상태와 허용 폴더를 확인하고 필요하면 해당 PC를 작업 PC로 선택한다.

일반 사용자의 앱 설치는 별도 .NET이나 관리자 권한이 필요하지 않도록 self-contained MSIX로 배포한다. 단, 현재 enterprise-managed pilot의 코드 서명 인증서는 IT가 미리 PC 전체 저장소에 배포해야 한다. AppLocker, WDAC 또는 sideloading 정책이 MSIX 설치를 막는 조직에서도 IT 정책 변경이 선행되어야 한다.

## 문제 해결

| 증상 | 확인할 항목 |
| --- | --- |
| LearnBot 화면 자체가 열리지 않음 | 안내받은 서버 IP와 포트, 회사망 연결, 서버 Compose 상태, 방화벽 허용 CIDR |
| `설치 파일 준비 중`이 계속 표시됨 | `pilot/release.json`, `.appinstaller`, 버전별 `.msix` 게시 여부와 Nginx artifact mount |
| `0x800B010A`와 함께 설치 버튼이 비활성화됨 | 패키지 손상이 아니라 서명 신뢰 체인 누락이다. 화면에서 받은 공개 CER와 표시된 지문을 대조하고 IT가 `Cert:\LocalMachine\TrustedPeople`에 배포한다. `CurrentUser` 저장소는 App Installer가 사용하지 않는다. |
| App Installer가 인증서를 신뢰하지 않음 | 사내 root/intermediate 및 publisher trust 배포 여부, 공개 CER 지문과 MSIX signer 지문, 패키지 `Publisher`와 인증서 Subject 일치 여부 |
| 설치 후 서버에 연결되지 않음 | 설치 파일을 받은 LearnBot 화면에서 `이 PC 연결`을 눌렀는지, 전달된 서버가 RFC1918 HTTP 주소인지, 방화벽이 해당 포트를 허용하는지 확인 |
| 서버 IP 또는 포트가 변경됨 | 초기화 스크립트와 `Set-LocalAgentServerRelease.ps1`을 다시 실행하고 Compose를 재시작한다. MSIX 재서명은 불필요하며 기존 연결 PC는 새 화면에서 다시 연결한다. |
| 브라우저 승인 화면이 자동으로 열리지 않음 | Local Agent의 `브라우저 다시 열기` 또는 `코드 복사` 기능 사용 |
| 설치가 조직 정책으로 차단됨 | App Installer, MSIX sideloading, AppLocker 및 WDAC 정책을 사내 IT가 확인 |

## 개발 및 내부 CLI 참고

It is intentionally safe by default:

- It pairs with the central LearnBot server using a token generated from the web UI.
- It stores local config in `%USERPROFILE%\.learnbot\agent.json`.
- It sends heartbeat with approved local workspace summaries.
- It polls the durable server-side tool queue.
- It writes `agent.log` and `agent-state.json` next to the config file.
- It handles `agent.status`, `agent.doctor`, `workspace.list`, bounded approved-workspace `workspace.tree`, bounded approved-workspace `workspace.search`, path-contained `file.read`, read-only `git.status`, bounded read-only `git.diff`, approved typed `command.runAllowed` test/build requests, dry-run-only `patch.apply` preflight requests, approved bounded `patch.apply` mutation for existing or newly created safe workspace files, and approved `rollback.restore` requests for Local Agent managed snapshots.
- It advertises the same handled tool set in heartbeat capabilities, including `patch.apply`, `command.runAllowed`, and `rollback.restore`, so server readiness can reason about the connected Local Agent's actual execution surface.
- It rejects path traversal, workspace escape, binary file reads, arbitrary command execution, unknown command ids, deletion patches, oversized patch sets, unapproved command/patch/rollback requests, patch mutation without a managed snapshot manifest, and rollback requests that do not reference a managed snapshot manifest.
- Dry-run `patch.apply` responses include hash/context observations plus managed snapshot and rollback observations. After preflight passes, the agent copies target files into `%USERPROFILE%\.learnbot\snapshots\<manifestId>\files\`, writes a manifest, returns `snapshotCreated=true`, and still keeps `mutationApplied=false`.
- `dotnet run --project local-agent -- self-test patch-dry-run-contract` pins that end-to-end dry-run contract: snapshot creation can pass, but the tool result remains `REJECTED`/`UNSAFE_TOOL`, leaves the workspace file unchanged, reports `mutationApplied=false`, and requires separate rollback approval.
- `dotnet run --project local-agent -- self-test workspace-tree-contract` pins the read-only project exploration boundary: `workspace.tree` can enumerate files under an approved workspace with max-entry/max-depth caps, skips large generated directories such as `.git`, `node_modules`, `bin`, `obj`, and `target`, and rejects path escape.
- `dotnet run --project local-agent -- self-test workspace-search-contract` pins the read-only candidate search boundary: `workspace.search` searches text inside approved workspaces with match/file/bytes caps, skips generated directories and binary/oversized files, supports case-insensitive matches, and rejects path escape.
- `dotnet run --project local-agent -- self-test read-only-candidate-selection-contract` pins the audit-only candidate-selection report built from `workspace.tree` plus `workspace.search`: selected files are ranked for the next `file.read` step, mutation remains disabled, and tree-only fallback is explicit when search evidence is unavailable.
- `dotnet run --project local-agent -- self-test multi-file-read-report-contract` pins the audit-only multi-file read report built from candidate selection plus one or more `file.read` responses: selected files are compared with read files, missing selections are explicit, truncation is visible, and mutation remains disabled.
- `dotnet run --project local-agent -- self-test patch-test-retry-decision-contract` pins the audit-only patch/test failure analysis report: successful allowlisted commands require no retry, failed test commands recommend replanning from captured stdout/stderr, unsafe or unapproved commands are blocked, and mutation remains disabled until a new approved patch is produced.
- `dotnet run --project local-agent -- self-test revised-patch-proposal-plan-contract` pins the failed-log-to-proposal boundary: failed `command.runAllowed` stdout/stderr plus known target files can produce a bounded revised-patch proposal plan, but local model generation, mutation, publication, and partial reindex stay disabled until dry-run and user approval.
- `dotnet run --project local-agent -- self-test local-model-revised-patch-request-contract` pins the local-model proposal request boundary: failed command evidence and read target-file snippets are shaped into a capped model input/output contract while the model call, patch queueing, mutation, publication, and partial reindex remain disabled.
- `dotnet run --project local-agent -- self-test local-model-revised-patch-output-contract` pins the local-model proposal output boundary: only a capped unified diff touching the planned target files can advance to `patch.apply` dry-run input, while empty, oversized, or out-of-target diffs are blocked and mutation remains disabled.
- `dotnet run --project local-agent -- self-test validated-revised-patch-dry-run-handoff-contract` pins the disabled dry-run handoff from validated model output to `patch.apply`: the dry-run input is shaped and visible, but durable queueing, claimability, mutation, and approval bypass remain disabled.
- `dotnet run --project local-agent -- self-test patch-test-second-attempt-contract` pins the bounded second-attempt contract: failed test output can produce a revised patch proposal, a validated dry-run handoff can be consumed and matched against the `patch.apply` dry-run response, the flow stops at `APPROVAL_REQUIRED`, and no mutation is executed before a new approval.
- `dotnet run --project local-agent -- self-test revised-patch-approval-request-contract` pins the approval request report for a revised dry-run: target files, diff evidence, snapshot/rollback observations, stale-index disclosure, and next mutation preconditions are explicit, and the next mutation is allowed only after `approvalState=APPROVED`.
- `dotnet run --project local-agent -- self-test revised-patch-approval-gate-contract` pins the server-facing approval-id gate for a revised mutation candidate: a persisted approval request id is required, missing or mismatched ids are blocked, and a matching approved id is required before a second mutation can be queued.
- `dotnet run --project local-agent -- self-test approved-server-queue-second-attempt-contract` pins the same second-attempt boundary through the durable polling queue: an approved first patch can run, an allowlisted test failure is returned, a revised `patch.apply` dry-run is queued and rejected as mutation-disabled after preflight passes, and rollback restores the original file.
- `dotnet run --project local-agent -- self-test approved-execution-flow-contract` pins a narrow Codex-style closed loop in one temporary workspace: read-only `workspace.tree`/`workspace.search`/candidate-selection/multiple `file.read`/multi-file-read/`git.status` observations run before mutation, advertised capabilities include project exploration, candidate search, patch/test-command/rollback tools, approved `patch.apply` mutates a bounded patch set, approved `command.runAllowed` runs through the typed allowlist, post-patch `git.status` observes the changed workspace, approved `rollback.restore` restores the managed snapshot, and the generated report carries retry-decision, revised-proposal-plan, local-model proposal request/output validation, disabled dry-run handoff, second-attempt, revised-approval-request, final-report, and RAG freshness sections that require partial reindex or stale-index disclosure.
- Patch hunk application and a temp-file rewrite sequence have Local Agent self-test coverage for the future write path, but they are not wired to public patch mutation or release gates yet.
- Approved `patch.apply` mutation currently applies up to five files, requires `mutationAllowed=true`, refuses `dryRunOnly=true`, requires a Local Agent managed snapshot manifest, rechecks the target hash or creation precondition immediately before writing, and uses the guarded temp-file rewrite sequence.
- `rollback.restore` can restore only from `%USERPROFILE%\.learnbot\snapshots\<manifestId>\manifest.json` after the request is already approved. It revalidates manifest schema/id, approved workspace id/root, managed snapshot paths, and workspace-contained target paths before restoring existing files or deleting files that were created by the approved patch.
- `command.runAllowed` requires `approvalState=APPROVED`, an approved workspace, and a typed allowlisted `commandId`. The first allowlist includes `dotnet.build`, `dotnet.test`, `npm.run.build`, `npm.test`, `maven.test`, and `maven.backend.test`; `dotnet.version` is retained as a local diagnostic/self-test command. Timeouts and captured output are capped, and arbitrary shell strings are not accepted.
- The first real snapshot creation boundary is specified in `../docs/local-agent-snapshot-implementation-plan.md`; tests remain disabled after snapshot creation.

Example:

```powershell
dotnet run --project local-agent -- pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> --transport polling
dotnet run --project local-agent -- workspace add .
dotnet run --project local-agent -- status
dotnet run --project local-agent -- doctor
dotnet run --project local-agent -- m8 status
dotnet run --project local-agent -- m8 doctor
dotnet run --project local-agent -- login --login-id <login-id>
dotnet run --project local-agent -- session status
dotnet run --project local-agent -- session plan
dotnet run --project local-agent -- session create-plan
dotnet run --project local-agent -- session claim-plan --device-code <device-code>
dotnet run --project local-agent -- session claim-result-plan --claim-status <status>
dotnet run --project local-agent -- session artifact-writer-preflight --approved --access-token-present --refresh-token-present --expires-at 2026-07-03T12:00:00Z --refresh-expires-at 2026-07-04T12:00:00Z
dotnet run --project local-agent -- session artifact-writer-test-write --test-only --approved --access-token-present --refresh-token-present --expires-at 2026-07-03T12:00:00Z --refresh-expires-at 2026-07-04T12:00:00Z
dotnet run --project local-agent -- session artifact-reader-test-validate --test-only
dotnet run --project local-agent -- session server-plan-readiness
dotnet run --project local-agent -- agent status
dotnet run --project local-agent -- agent token
dotnet run --project local-agent -- agent logs --tail 80
dotnet run --project local-agent -- file tree --workspace-id <workspace-id>
dotnet run --project local-agent -- file search --workspace-id <workspace-id> --query App
dotnet run --project local-agent -- file read --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- git status --workspace-id <workspace-id>
dotnet run --project local-agent -- git diff --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id>
dotnet run --project local-agent -- review --goal "review current changes" --workspace . --repository-id <repository-id>
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --observe-read-only
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --observe-read-only --read-selected
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --server-plan
dotnet run --project local-agent -- agent start --once --transport auto
```

Transport modes are scaffolded as `polling`, `websocket`, and `auto`. `websocket` and `auto` try a bounded WebSocket hello/heartbeat first, process pushed read-only `tool.request` messages during the receive window, send `tool.response`, and then keep polling available for durable tool queue fallback. `agent status` includes the configured transport, active transport, consecutive WebSocket failures, and next retry time so fallback behavior is visible.

Internal foreground helper from the repository root:

```powershell
.\scripts\local-agent.ps1 -Action setup -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action setup-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action setup-run-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action browser-pairing-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action pair-from-web-token-plan -Server http://localhost:8083 -WorkspacePath . -PairingAgentId <agent-id> -PairingToken <pairing-token> -Transport polling
.\scripts\local-agent.ps1 -Action pair-from-web-token -Server http://localhost:8083 -WorkspacePath . -PairingAgentId <agent-id> -PairingToken <pairing-token> -Transport polling
.\scripts\local-agent.ps1 -Action status
.\scripts\local-agent.ps1 -Action token
.\scripts\local-agent.ps1 -Action logs -Tail 80
.\scripts\local-agent.ps1 -Action start
.\scripts\local-agent.ps1 -Action background-start
.\scripts\local-agent.ps1 -Action background-stop
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction status
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction logs -Tail 80
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction background-start
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction background-stop
.\scripts\local-agent.ps1 -Action lifecycle-status
.\scripts\local-agent.ps1 -Action service-plan
.\scripts\local-agent.ps1 -Action service-command-plan -ServiceAction install
.\scripts\local-agent.ps1 -Action service-command -ServiceAction install
.\scripts\local-agent.ps1 -Action m8-status
.\scripts\local-agent.ps1 -Action m8-doctor
.\scripts\local-agent.ps1 -Action m8-lifecycle-run -Transport auto
```

`setup-run-plan` returns `learnbot.local-agent.setup-run-plan.v1`, a preview-only guided setup execution boundary. It reuses `setup-plan`, keeps login/pairing/workspace commands disabled, and reports missing inputs before any network calls or local config writes are enabled. The current `setup` helper checks this readiness boundary before prompting for the password or calling the server.

`browser-pairing-plan` returns `learnbot.local-agent.browser-pairing-plan.v1`, a preview-only setup path where the browser owns login and pairing-token creation. It avoids CLI password collection and shows the follow-up `learnbot pair` and workspace registration commands without printing token secrets.

`pair-from-web-token-plan` returns `learnbot.local-agent.pair-from-web-token-plan.v1`, a preview-only contract for pasted web pairing inputs. It validates the agent id, pairing-token presence, and workspace path without collecting a password, printing token secrets, writing local config, registering a workspace, or making network calls.

`pair-from-web-token` runs the guarded browser-token setup path and returns `learnbot.local-agent.pair-from-web-token-result.v1`. It builds the same plan, returns a structured `BLOCKED` result when the plan is not ready, then runs `learnbot pair`, `learnbot workspace add`, and `learnbot agent status` only after readiness passes. The result reports per-step success/failure without collecting a CLI password or printing the pasted token. The underlying `learnbot pair` command still sends its initial heartbeat to the configured server, so the server must be reachable and the token must be valid for the command to complete.

`learnbot pair` validates the initial heartbeat before saving local config. If the server is unreachable or the token is rejected during that first heartbeat, a new config file is not created and an existing config is preserved.

`lifecycle-status` returns `learnbot.local-agent.lifecycle-status.v1`, a machine-readable internal-pilot view of config, run state, process liveness, log presence, recommended lifecycle commands, and service readiness. It does not print pairing token secrets.

`lifecycle-command` returns `learnbot.local-agent.lifecycle-command-result.v1` for `status`, `logs`, `doctor`, `background-start`, or `background-stop`. It is an automation-friendly wrapper around the existing helper commands and reports success/failure, captured output, and safety flags without invoking Windows Service commands.

`service-plan` returns `learnbot.local-agent.service-plan.v1`, a Windows Service readiness plan. It checks for the installed executable, paired config, approved workspace, and existing service state, then prints planned service commands without executing them.

`service-command-plan` returns `learnbot.local-agent.service-command-plan.v1` for `install`, `start`, `stop`, or `uninstall`. It remains preview-only and reports blockers without touching the OS.

`service-command` returns `learnbot.local-agent.service-command-result.v1` and can run `install`, `start`, `stop`, or `uninstall` through typed Windows Service commands. It blocks unless PowerShell is running as administrator, the executable is installed, pairing config exists, and at least one workspace is approved. It never prints pairing-token secrets.

`learnbot m8 status` returns `learnbot.local-agent.m8-productization-status.v1`, a machine-readable productization readiness view for the Codex-like local experience. It consolidates guided setup, background lifecycle, doctor/log UX, Windows Service readiness, Codex-like command availability, signed-installer readiness, and auto-update readiness while keeping MSI signing and auto-update disabled until those product paths are explicitly implemented. The report includes `nextCommands` so unpaired, paired-stopped, and paired-running states show the next safe commands without printing pairing-token secrets.

`learnbot m8 doctor` returns `learnbot.local-agent.m8-doctor.v1`, a read-only M8 diagnosis wrapper around productization status. It groups setup, lifecycle, runtime, logs, service preview, and distribution readiness into sections, repeats the safe next-command sequence, and keeps token printing, service execution, installer signing, and auto-update disabled.

`m8-lifecycle-run` returns `learnbot.local-agent.m8-lifecycle-run-result.v1`, a guarded internal-pilot lifecycle helper. It blocks when the agent is unpaired or has no approved workspace, skips `background-start` when the agent is already running, otherwise starts the background helper and then captures status and logs. It does not execute Windows Service commands, installer signing, auto-update, or token printing.

`learnbot login` returns `learnbot.local-agent.web-login-plan.v1`, a disabled web-login preview. It shows the intended `POST /api/auth/login` handoff, the server-side `POST /api/auth/cli-device-session/plan` bridge, and the next `learnbot session create-plan` discovery step without collecting a password, making a network call, persisting cookies, storing a web session, using the Local Agent pairing token, or printing token secrets. This keeps CLI web-user authentication separate from Local Agent pairing while the device-code or cookie session bridge is still being designed.

The backend `POST /api/auth/cli-device-session/plan` endpoint returns `learnbot.server.auth.cli-device-session-plan.v1`. It is public read-only so an unpaired CLI can discover the future browser/device-code path, but it still does not issue a device code, claim token, access token, refresh token, cookie, or stored session.

The backend `POST /api/auth/cli-device-session/create/plan` endpoint returns `learnbot.server.auth.cli-device-session-create-plan.v1`. It previews the future device-code creation response shape, including verification path, user-code format, expiry, and polling interval, while still not creating a device code, user code, token, cookie, or stored session.

`learnbot session status` returns `learnbot.local-agent.web-session-status.v1`, a read-only view of the future CLI web-session boundary. It reports whether `LEARNBOT_WEB_TOKEN` is present using only a fingerprint, whether that token can be used for `--server-plan`, the future local `web-session.json` path, a nested `learnbot.local-agent.web-session-artifact-validation.v1` validator preview, a nested `learnbot.local-agent.web-session-secret-provider-plan.v1` production secret-store preview, and the server-side `POST /api/auth/cli-device-session/claim/plan` claim/storage preview endpoint. The artifact validator also carries `learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1`, pointing to the required non-writing `artifact-production-crypto-preview --preview-only` proof before production stored-session loading can be enabled. It does not read or print token secrets, does not poll a claim, does not write a stored web session file, and requires encrypted local session artifact storage before that path can be enabled.

The backend `POST /api/auth/cli-device-session/claim/plan` endpoint returns `learnbot.server.auth.cli-device-session-claim-plan.v1`. It is also public read-only so the CLI can discover the future polling and local session artifact contract, but it does not poll, claim, issue tokens, write local files, accept Local Agent tokens, or persist cookies.

The backend `POST /api/auth/cli-device-session/claim-result/plan` endpoint returns `learnbot.server.auth.cli-device-session-claim-result-plan.v1`. It is public read-only and fixes the future browser-approved claim-result artifact-writer preflight: required claim-result fields, encrypted artifact fields, atomic write requirement, and plaintext-token serialization refusal. It does not accept tokens, write files, persist cookies, refresh tokens, or use the Local Agent pairing token.

`learnbot session plan`, `learnbot session create-plan`, `learnbot session claim-plan`, and `learnbot session claim-result-plan` return `learnbot.local-agent.web-session-plan-fetch-result.v1`. They try the public backend plan endpoints when reachable, never attach the Local Agent pairing token, and fall back to local static disabled plans when the server is offline or `--offline` is passed. The claim-plan fallback includes the future encrypted `web-session.json` artifact body preview using encrypted token placeholders only. The claim-result-plan fallback includes the disabled artifact-writer preflight using encrypted token placeholders only. The fetch result keeps device-code issuance, session claiming, token issuance, cookie persistence, local web-session artifact writes, request creation, and token printing disabled.

`learnbot session artifact-writer-preflight` returns `learnbot.local-agent.web-session-artifact-writer-preflight-result.v1`. It validates a simulated browser-approved claim-result boundary from metadata flags only: approved claim, access-token presence, refresh-token presence, parseable expiry fields, and plaintext-token serialization refusal. It does not accept token values, does not write `web-session.json`, does not probe/decrypt the OS secret store, does not use the Local Agent pairing token, and blocks explicit `--write` requests until the guarded writer is implemented.

`learnbot session artifact-writer-test-write` returns `learnbot.local-agent.web-session-artifact-writer-test-write-result.v1`. It requires explicit `--test-only` plus the same preflight metadata before it writes a local `web-session.json` artifact. The written artifact uses test-only AES-GCM encrypted placeholder token material and atomic replace, never serializes plaintext token material, and never prints secrets. The derived test-only key is not stored in the artifact, so this is a writer/format safety proof, not a usable browser-login session.

`learnbot session artifact-reader-test-validate` returns `learnbot.local-agent.web-session-artifact-reader-test-validate-result.v1`. With explicit `--test-only`, it reads only the test-only artifact provider, parses the artifact schema, verifies the encryption metadata, decrypts the placeholder token material, and returns fingerprints only. Production stored-session loading, DPAPI/OS secret-store decryption, token printing, and Local Agent token use remain disabled.

`learnbot session artifact-production-crypto-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1`. On Windows it uses the DPAPI current-user provider to encrypt and decrypt placeholder access/refresh token material in memory, then returns only fingerprints and artifact metadata. It does not write `web-session.json`, read stored sessions, load credentials, refresh tokens, use the Local Agent pairing token, or print token material.

`learnbot session artifact-production-writer-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-writer-preview-result.v1`. It combines simulated approved claim-result metadata with the production crypto preview proof and prepares the exact future artifact body shape in memory using redacted encrypted-token placeholders. The result also includes `learnbot.local-agent.web-session-production-artifact-atomic-write-plan.v1`, a write-disabled atomic replace plan with the future session path and temp path pattern. It does not output encrypted token blobs, accept real token values, write `web-session.json`, read stored sessions, load credentials, refresh tokens, use the Local Agent pairing token, or print token material.

`learnbot session artifact-production-reader-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1`. It models the future DPAPI-backed reader/decrypt path by checking the crypto proof and required artifact schema/fields, while keeping production file reads, JSON parsing, stored-token decryption, token loading, refresh, stored-session server-plan auth, Local Agent token use, and token printing disabled.

`learnbot session stored-session-auth-readiness` returns `learnbot.local-agent.web-session-stored-session-auth-readiness.v1`. It fixes the future stored-session auth preconditions for Codex-like `fix`/`review --server-plan`: browser-approved claim result, production artifact read/decrypt, access token, refresh token, expiry, refresh expiry, refresh eligibility, and server-plan auth. It still does not read `web-session.json`, parse JSON, decrypt stored tokens, load credentials, refresh tokens, create requests, mutate code, use the Local Agent pairing token, or print token material. `learnbot session status` and `learnbot session server-plan-readiness` embed this readiness report so users can see why environment-token fallback works while stored-session auth remains disabled.

`learnbot session secret-provider-plan` returns `learnbot.local-agent.web-session-secret-provider-plan.v1`. It pins the disabled production secret-store boundary for future Windows DPAPI/current-user or OS secret-store encryption. Automatic provider probing, production encryption/decryption, stored-session loading, plaintext token serialization, token printing, and Local Agent token use all remain disabled; the test-only AES-GCM provider is explicitly not accepted for production.

`learnbot session secret-provider-probe` returns `learnbot.local-agent.web-session-secret-provider-probe-result.v1`. On Windows it runs a DPAPI current-user protect/unprotect round trip with a non-secret sentinel only, proving the local primitive is callable without reading, writing, encrypting, decrypting, loading, or printing web-session token material. Production artifact encryption/decryption and stored-session loading remain disabled after the probe.

`learnbot session server-plan-readiness` returns `learnbot.local-agent.web-session-server-plan-readiness.v1`. It is a read-only bridge between CLI web-session state and Codex-like `fix`/`review --server-plan`: with `LEARNBOT_WEB_TOKEN` present it reports environment-token fallback readiness by fingerprint only; without it, it reports that stored web-session artifact loading is still disabled. It does not read stored token secrets, write a session artifact, use the Local Agent pairing token, create a server request, or enable mutation.

The nested `learnbot.local-agent.web-session-artifact-validation.v1` validator preview fixes the future encrypted `web-session.json` contract. It requires schema `learnbot.local-agent.web-session-artifact.v1`, encrypted access and refresh token fields, expiry fields, and creation time, but it does not read, parse, decrypt, or load token secrets yet.

`learnbot fix` and `learnbot review` return `learnbot.local-agent.codex-command-preview.v1`, the first Codex-like command preview contract. They validate pairing, an approved workspace, and a goal, then include a disabled `learnbot.local-agent.codex-server-submission-plan.v1` for the intended `POST /api/code-agent/loop/submission-plan` handoff. They also include `learnbot.local-agent.codex-one-cycle-preview.v1`, the user-perceived one-cycle contract: goal input, workspace discovery, file discovery, file reads, planning, patch dry-run, approval, apply/test, failure-log retry, final report, and RAG freshness update. That one-cycle preview embeds `learnbot.local-agent.codex-file-discovery-read-plan.v1`, a dry-run-only plan with candidate tools (`file.tree`, `file.search`, `file.read`, `git.status`), bounded path/query hints, planned discovery/read steps, and explicit no-read/no-request/no-mutation flags. The plan also exposes disabled `learnbot.local-agent.codex-read-only-request-envelope-preview.v1` envelopes for the actual Local Agent tool names `workspace.tree`, `workspace.search`, and `git.status`; they are non-claimable and keep request creation, enqueue, execution, file-content reads, mutation, approval, and token printing disabled. With `--observe-read-only`, the CLI returns `learnbot.local-agent.codex-read-only-observation.v1` and executes only `workspace.tree`, `workspace.search`, and `git.status` against a paired approved workspace; search snippets are redacted, then `learnbot.local-agent.codex-read-only-candidate-selection.v1` ranks matched paths into bounded `file.read` candidates while keeping `fileReadExecutionEnabled=false`, `fileContentRead=false`, `requestCreationEnabled=false`, and `mutationAllowed=false`. Adding `--read-selected` explicitly executes only those selected candidates through bounded `file.read`, returns `learnbot.local-agent.codex-selected-file-read.v1`, prepares `learnbot.local-agent.codex-patch-intent-preview.v1` with target files and dry-run intent metadata, exposes `learnbot.local-agent.codex-patch-proposal-preview.v1` as a placeholder proposal boundary, carries disabled `learnbot.local-agent.codex-diff-source-input-preview.v1` for future `local-model`, `server-planner`, `inline`, or `file` diff sources, carries disabled `learnbot.local-agent.codex-planner-diff-output-preview.v1` for future planner output envelopes, carries `learnbot.local-agent.codex-generated-diff-acceptance-preview.v1`, carries `learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1`, adds `learnbot.local-agent.codex-diff-source-validation-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1`, and carries `learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1`. The diff-source input boundary accepts metadata such as `--diff-source`, `--diff-file`, or `--diff-text` presence but does not read files, accept inline diff bodies, or run planners. When `local-model` or `server-planner` is requested, the planner-output preview fixes the future output envelope shape with target files and unified diff requirement while keeping planner execution and normal diff generation disabled. A bounded in-memory generated diff can be accepted only with `--accept-generated-diff-preview --generated-diff ...`, only from the local-model/server-planner envelope path, and never from a diff file; if accepted, the handoff forwards it to validation preview and reports whether it parses and touches selected target files only. The validation boundary only prepares future `patch.apply` dry-run input when the accepted diff parses and touches selected target files only; the dry-run request envelope preview then fixes the future `patch.apply` request shape with `dryRunOnly=true`, `allowMutation=false`, `USER_LOCAL_AGENT`, and approval required before snapshot-writing dry-run, while request creation, enqueue, claim, snapshot creation, execution, mutation, tests, final-report publication, and partial reindex remain disabled. With `--run-nonwriting-preflight-preview`, a paired approved workspace can run only the existing non-writing context preflight from the accepted generated diff; it reads target files and validates hunks, but still creates no requests, snapshots, file writes, mutation, tests, final-report publication, or partial reindex. When that preflight passes, the approval handoff preview can reach `APPROVAL_HANDOFF_PREPARED` and carries repository id, workspace id, target files, request envelope status, and preflight status for the future snapshot-writing dry-run approval gate, while approval request creation, enqueue, claim, snapshot creation, execution, mutation, tests, final-report publication, and partial reindex remain disabled. Default CLI output still reports no diff source and keeps `diffGenerated=false`, `patchDryRunExecutionEnabled=false`, `requestCreationEnabled=false`, `mutationAllowed=false`, tests, final-report publication, and partial reindex disabled. The read-only discovery/read/plan stages can become ready when pairing, workspace, goal, and repository id are present, while patch/test/final-report/partial-reindex stages stay disabled until authenticated server handoff, approval, and release gates are real. The plan shows repository id, optional space id, instruction, max steps, follow-up runner endpoints, and blockers while keeping network calls, submission, request creation, mutation, test execution, rollback, final publication, partial reindex, and token printing disabled.

Adding `--server-plan` wraps the preview in `learnbot.local-agent.codex-server-plan-fetch-result.v1` and embeds `learnbot.local-agent.web-session-server-plan-readiness.v1`, the same `learnbot.local-agent.codex-one-cycle-preview.v1`, and `learnbot.local-agent.codex-read-only-server-bridge.v1`. With a usable web token it now submits `POST /api/code-agent/loop/runs`, then runs a guarded auto loop by default: polling run status, calling `advance`, processing queued Local Agent work inline, waiting for browser approval, and after approval calling readiness/fresh-observation/release/release-for-execution before polling approved execution work. The auto-loop result is reported as `learnbot.local-agent.codex-server-auto-loop-result.v1`. Use `--no-auto-loop` to keep the old single server request behavior, `--no-apply` to stop after approval request creation, `--poll-timeout <seconds>` for the whole loop, and `--approval-timeout <seconds>` for browser approval waiting. Mutation is still impossible before browser approval and server release gates; if those gates are disabled the CLI reports the release blocker instead of applying a patch.

The server-side `POST /api/code-agent/loop/submission-plan` endpoint returns `learnbot.server.code-agent.loop-submission-plan.v1` after normal web authentication and repository-space authorization, but it still does not create a loop, conversation, approval request, Local Agent request, mutation, publication, or reindex job.

Live smoke from the repository root:

```powershell
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath . -ToolName file.read -Path README.md
$env:LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED = "true"
.\scripts\up.ps1 -Build
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath . -ToolName file.read -Path README.md -Transport websocket
```

The WebSocket smoke requires the backend WebSocket endpoint to be enabled and fails if the request is completed only through polling fallback. The default stack keeps WebSocket disabled, so the non-WebSocket smoke remains the fallback check.

Internal executable publish helper:

```powershell
.\scripts\local-agent-install.ps1 -Action install
.\scripts\local-agent-install.ps1 -Action install -AddToUserPath
.\scripts\local-agent-install.ps1 -Action status
learnbot status
learnbot doctor
learnbot agent status
```

After publishing to the default install directory, `scripts/local-agent.ps1` uses the installed executable automatically. The install helper returns `learnbot.local-agent.install-status.v1`, including the install directory, executable path, PATH visibility, recommended `status`/`doctor`/`start` commands, internal-pilot limitations, and the installed executable's `learnbot status` output when available.
