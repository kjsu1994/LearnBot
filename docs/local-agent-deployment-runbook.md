# LearnBot Local Agent 운영 배포 런북

이 문서는 부서마다 독립된 LearnBot Compose 서버를 두고, 서버에 직접 접근할 수 없는 Windows 사용자가 해당 부서 화면에서 Local Agent를 설치·연결하도록 운영 환경을 배포하는 절차다. 앱 설치 자체에는 별도 .NET이 필요하지 않지만, enterprise-managed pilot 서명 인증서의 PC 전체 신뢰 배포에는 사내 IT 관리 권한이 필요하다. 아래 명령은 저장소 루트에서 실행한다.

## 배포 계약

Local Agent의 기본 운영 배포는 하나의 HTTPS origin을 사용한다. 예를 들어 `https://learnbot.example`을 사용한다면 UI, API, WebSocket, 설치 파일이 모두 이 origin에서 제공되어야 한다. `PublicBaseUrl`에는 경로나 쿼리 없이 origin만 넣는다. 아래에 별도로 명시한 사내망 HTTP pilot만 이 규칙의 한시적 예외다.

| 용도 | 공개 경로 | 변경 정책 |
| --- | --- | --- |
| LearnBot UI/API | `/`, `/api/**` | 애플리케이션 배포에 따름 |
| WebSocket | `/api/local-agents/ws` | HTTPS에서는 `wss://`로 승격되어야 함 |
| pilot App Installer | `/downloads/local-agent/pilot/LearnBotLocalAgent.appinstaller` | channel 포인터, 캐시 금지 |
| stable App Installer | `/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller` | channel 포인터, 캐시 금지 |
| channel 메타데이터 | `/downloads/local-agent/{pilot|stable}/release.json` | channel 포인터, 캐시 금지 |
| 서명된 MSIX | `/downloads/local-agent/releases/{version}/LearnBotLocalAgent_{version}_x64.msix` | 버전별 불변 파일, 장기 캐시 |
| 공개 코드 서명 인증서 | `/downloads/local-agent/trust/LearnBotLocalAgentSigning_{thumbprint}.cer` | MSIX signer별 불변 공개 파일 |

설치 파일 경로는 로그인 리다이렉트, 별도 object-storage origin 또는 일회성 URL로 보내면 안 된다. Windows App Installer가 익명 `HEAD`/`GET`으로 접근할 수 있어야 하며, 공개 artifact에는 자격 증명이나 pairing token을 넣지 않는다.

## 현재 선택: 범용 MSIX + 서버별 사내망 HTTP pilot

각 서버 PC는 자신의 RFC1918 LAN IP와 port(기본 `8083`)로 origin을 생성한다. 예를 들어 어떤 서버가 `192.168.1.72`를 사용하면 그 서버의 origin은 `http://192.168.1.72:8083`이지만, 이 주소는 제품이나 MSIX의 고정값이 아니다. 서명된 portable MSIX는 여러 서버가 그대로 재사용하고, 각 서버는 자신의 `10.x`, `172.16-31.x` 또는 `192.168.x` 주소로 `.appinstaller`, channel `release.json`, listener 및 Host 정책만 생성한다.

- 웹 화면의 `이 PC 연결`은 현재 origin을 protocol activation으로 전달한다. portable package의 HTTP 정책은 RFC1918 IPv4 literal만 허용하고 사내 DNS 이름, 공인 IP 및 외부 HTTP origin은 거부한다. 승인 후 저장된 origin은 enrollment와 update의 same-origin 기준이 된다.
- 웹 화면은 HTTP 사설 IP에서 `stable`이 아니라 `pilot` channel을 자동으로 사용한다.
- HTTP package는 `stable` 게시·승격이 차단된다. HTTPS 전환 시 새 버전으로 다시 빌드하고 검증한다.
- 사용자 PC의 IP는 패키지 정책에 포함되지 않는다. Windows 11 x64 PC가 회사망에서 선택한 서버 origin에 접근할 수 있으면 된다.
- 직접 LAN용 Nginx는 요청의 `X-Real-IP`, `X-Forwarded-For`, `X-LearnBot-Client-IP`, `CF-Connecting-IP`를 신뢰하지 않고 실제 연결 peer로 덮어쓴다.
- HTTP에서는 로그인 cookie, enrollment code, Agent bearer credential 및 작업 데이터가 전송 구간에서 암호화되지 않는다. 서버 방화벽에서 TCP 8083을 실제 회사 사용자 CIDR에만 허용하고, 인터넷·게스트망·VPN 비승인 구간에는 route를 열지 않는다.

각 부서 서버에는 중앙에서 검증한 `releases/<version>`과 그 signer의 공개 CER를 함께 복사한다. 서버마다 인증서를 새로 생성하거나 MSIX를 다시 서명하지 않는다. 권장 시작 명령은 다음과 같다. 기본 gateway가 있는 실제 LAN 주소가 하나면 자동 선택하고 전체 Compose를 시작한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\local-agent\Start-LearnBotDepartmentServer.ps1 `
  -Port 8083
```

VPN 등으로 기본 gateway가 여러 개면 `-ServerLanIp "<실제 회사 LAN IPv4>"`를 추가한다. Compose 시작 없이 검증만 할 때는 `-ConfigureOnly`를 사용한다. 아래는 동일 과정을 수동으로 실행하는 방법이다.

```powershell
$Deployment = .\scripts\local-agent\Initialize-LocalAgentLanHttp.ps1 | ConvertFrom-Json
# 여러 사설 NIC가 있는 서버의 예:
# $Deployment = .\scripts\local-agent\Initialize-LocalAgentLanHttp.ps1 -ServerLanIp "10.20.30.40" | ConvertFrom-Json
$Origin = $Deployment.publicBaseUrl

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

초기화 스크립트는 Git에서 제외되는 `.env.local-agent-lan-http`와 `deploy/local-agent-lan-http.generated.inc`를 만든다. 전자는 listener를 선택한 서버 NIC/port에만 bind하고, 후자는 그 IP의 HTTP Host만 허용한다. 선택한 IP에는 고정 IP 또는 DHCP reservation을 적용한다. IP가 바뀌면 초기화와 `Set-LocalAgentServerRelease.ps1`을 다시 실행하되 MSIX를 재빌드하거나 재서명하지 않는다. 방화벽의 허용 source CIDR은 조직 네트워크 담당자가 확정해야 하며 `0.0.0.0/0`을 허용하지 않는다.

### 사내 인증서로 pilot MSIX 게시

여기서 사용하는 인증서는 HTTPS TLS 인증서가 아니라 **MSIX 코드 서명 인증서**다. 사내 CA는 Code Signing EKU(`1.3.6.1.5.5.7.3.3`)가 포함된 별도 인증서를 release runner에 발급하고 private key는 그 runner 밖으로 내보내지 않는다. 중앙관리 정책은 사용자 PC의 Local Machine 인증서 저장소에 사내 root/intermediate trust chain과 조직 정책상 필요한 publisher trust를 배포한다. `Publisher`는 서명 인증서의 전체 Subject와 정확히 같아야 한다.

```powershell
$Version = "0.2.0.0"                  # 기존 게시 버전은 재사용하지 않는다.
$Minimum = "0.1.0.0"
# 서버 초기화 결과의 publicBaseUrl을 사용한다.
$Origin = $Deployment.publicBaseUrl
$Publisher = "CN=LearnBot Enterprise Code Signing"
$Thumbprint = "INTERNAL_CODE_SIGNING_CERT_SHA1_THUMBPRINT"
$SdkBin = "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64"

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

.\scripts\local-agent\release\Test-LocalAgentRelease.ps1 `
  -ArtifactsRoot .\artifacts\local-agent `
  -Channel pilot `
  -ExpectedLatestVersion $Version `
  -ExpectedMinimumSupportedVersion $Minimum `
  -RequireSignature `
  -SignToolPath "$SdkBin\signtool.exe"
```

사내망에서 외부 timestamp service에 접근할 수 없다면 `-TimestampUrl`에 조직의 RFC 3161 timestamp endpoint를 지정한다. 인증서가 아직 중앙 배포되지 않은 PC를 pilot 대상에 포함하지 않는다.

사용자는 `$($Deployment.publicBaseUrl)/settings/local-agent`에 해당하는 서버별 화면에서 파일을 다운로드하고 `.appinstaller`를 열어 설치한다. 먼저 IT가 공개 CER를 `Local Computer\Trusted People`에 중앙 배포한다. 그 뒤 처음 한 대에서 App Installer에 기대한 publisher가 표시되고 일반 사용자에게 인증서 수동 설치를 요구하지 않는지 확인한 후 pilot 대상을 확대한다. `0x800B010A`가 나오면 MSIX를 다시 만들지 말고 해당 PC의 machine trust 배포와 CER 지문부터 확인한다.

### 같은 서명 패키지를 다른 서버에 게시

새 서버에는 private key나 Windows SDK가 필요 없다. 검증된 `artifacts/local-agent/releases/<version>`과 MSIX signer에 해당하는 `artifacts/local-agent/trust/*.cer`를 복사하고 그 서버에서 초기화를 실행한 다음 서버별 포인터만 만든다. `Set-LocalAgentServerRelease.ps1`은 CER 지문이 MSIX signer와 다르거나 CER가 누락되면 게시를 거부한다.

```powershell
$Deployment = .\scripts\local-agent\Initialize-LocalAgentLanHttp.ps1 `
  -ServerLanIp "<새 서버의 실제 사내망 IPv4>" `
  -Port 8083 `
  -LatestVersion $Version `
  -MinimumVersion $Minimum | ConvertFrom-Json

.\scripts\local-agent\release\Set-LocalAgentServerRelease.ps1 `
  -Version $Version `
  -PublicBaseUrl $Deployment.publicBaseUrl `
  -ArtifactsRoot .\artifacts\local-agent `
  -AllowInsecurePrivateNetwork
```

이 스크립트는 immutable MSIX의 해시, 크기, signer subject와 `portableServerPackage` 표식 및 공개 CER 지문을 확인한 후 `.appinstaller`와 `pilot/release.json`만 atomic하게 갱신한다. 메타데이터의 `embeddedServerOrigin`은 `null`로 유지된다.

### 추후 내부 HTTPS로 전환할 때

1. 해당 서버 LAN IP를 가리키는 고정 내부 DNS 이름을 정하고, 그 이름의 SAN을 가진 **별도 TLS 서버 인증서**를 발급한다. MSIX 코드 서명 인증서의 private key를 TLS에 재사용하지 않는다.
2. 사용자 PC에 TLS root/intermediate chain을 중앙 배포하고 브라우저와 `Invoke-WebRequest`에서 인증서 경고가 없는지 확인한다.
3. TLS edge 뒤에서 pilot을 검증한 후 `-AllowInsecurePrivateNetwork` 없이 새 네 자리 버전을 게시한다. 사내 코드 서명 체계를 유지한다면 `-EnterpriseManagedTrust`는 계속 사용할 수 있다.
4. HTTP origin과 HTTPS origin은 서로 다른 서버로 취급되므로 기존 장치의 재연결·폐기 절차를 pilot PC에서 먼저 검증한다. HTTP listener를 제거하기 전에 연결 전환율을 확인한다.
5. HTTPS package만 stable로 승격하고, 마지막 장치 전환 후 방화벽에서 HTTP 8083을 닫는다.

## 사전 요구 사항

운영 서버에는 Docker와 Compose만 필요하다. 빌드 및 서명은 별도의 신뢰된 Windows release runner에서 수행해도 된다.

Release runner에만 다음을 설치한다.

- .NET 10 SDK (`dotnet`)
- Windows SDK의 x64 `makeappx.exe`와 `signtool.exe`; PATH에 없으면 `-WindowsSdkBin`으로 해당 디렉터리를 전달
- 서명 수단. 외부 배포는 Azure Artifact Signing(이전 이름 Trusted Signing) 또는 공개 신뢰 code-signing 인증서를 사용한다. 중앙관리 사내 배포는 조직 CA의 Code Signing 인증서와 `-EnterpriseManagedTrust`를 사용할 수 있다.
- Artifact Signing을 사용할 때는 해당 runner에 Artifact Signing Client Tools, dlib, metadata JSON 및 Microsoft가 요구하는 runtime 설치

`Publish-LocalAgentRelease.ps1`은 `win-x64`, self-contained, single-file로 main app, Setup, StartupHost를 게시한다. 따라서 운영 서버와 사용자 PC에는 .NET SDK나 .NET runtime이 필요하지 않다. 현재 package manifest의 지원 대상은 Windows 11 x64(`10.0.22000.0` 이상)다.

사용자 PC에는 Windows App Installer가 활성화되어 있어야 한다. 일반 Windows 정책에서는 MSIX가 사용자 단위로 설치되므로 관리자 권한이 필요하지 않지만, 조직의 AppLocker/WDAC/sideloading 정책이 설치를 막는 경우에는 IT 정책 변경이 선행되어야 한다.

## 1. 공개 DNS와 TLS edge 구성

1. `learnbot.example` 같은 고정 DNS 이름을 운영 TLS edge로 연결한다.
2. 공개 신뢰 CA 인증서를 발급하고, SAN이 정확히 이 이름을 포함하는지 확인한다.
3. 외부에는 TCP 443만 노출한다. Compose의 `8083`은 기본적으로 `127.0.0.1`에만 bind되고 backend `8080`도 loopback 전용이다. 인터넷에 직접 공개하지 않는다. 별도 edge host가 꼭 필요한 경우에만 `LEARNBOT_NGINX_BIND_ADDRESS`를 private 주소로 설정하고 firewall에서 그 edge 주소만 허용한다.
4. TLS edge는 `https://learnbot.example/*`를 LearnBot Nginx의 `http://127.0.0.1:8083` 또는 허용된 private 주소로 전달한다. `/api/local-agents/ws`에는 HTTP/1.1 Upgrade/Connection 헤더를 보존한다.
5. edge에서 외부 요청의 `X-Real-IP`와 `X-Forwarded-For`를 그대로 신뢰하지 말고, 연결 peer에서 검증한 값으로 반드시 덮어쓴다. 최소 안전 설정은 다음과 같다.

   ```nginx
   proxy_set_header Host $host;
   proxy_set_header X-LearnBot-Client-IP $remote_addr;
   proxy_set_header X-Real-IP "";
   proxy_set_header X-Forwarded-For "";
   proxy_set_header X-Forwarded-Proto https;
   ```

   `X-LearnBot-Client-IP`는 loopback/private firewall 뒤의 신뢰된 TLS edge만 쓸 수 있다. app Nginx는 이 값으로 backend의 `X-Real-IP`와 `X-Forwarded-For`를 덮어쓰며 외부가 보낸 기존 체인은 폐기한다. Cloudflare Tunnel profile은 Cloudflare가 tunnel에 넣는 `CF-Connecting-IP`를 같은 방식으로 사용한다. `LEARNBOT_NGINX_BIND_ADDRESS=0.0.0.0`로 listener를 공개한 상태에서는 두 헤더를 신뢰하면 안 된다.

6. HTTPS가 전체 origin에서 정상 동작하는 것을 확인한 뒤 TLS edge에 HSTS를 설정한다.

   ```text
   Strict-Transport-Security: max-age=31536000
   ```

   모든 하위 도메인이 HTTPS일 때만 `includeSubDomains`를 추가하고, preload 등록은 복구 영향까지 검토한 후 별도로 결정한다.
7. 인증서 자동 갱신과 proxy reload를 구성하고 만료 30/14/7일 전에 경보가 울리도록 한다. 갱신 후에는 이 문서의 외부 smoke check를 다시 실행한다.

Cloudflare Tunnel을 사용할 경우 `docker-compose.yml`의 `cloudflare` profile을 사용한다. public hostname의 origin이 변하지 않아야 하며, 기본 loopback bind를 유지하고 tunnel container와 localhost만 Nginx에 도달하게 한다. Cloudflare가 전달한 client-IP 헤더는 이 tunnel 경계를 우회한 요청에서 신뢰하면 안 된다.

## 2. artifact volume과 정적 파일 응답 확인

`docker-compose.yml`은 host의 `./artifacts/local-agent`를 다음 위치에 read-only로 mount한다.

```text
./artifacts/local-agent:/usr/share/nginx/client/downloads/local-agent:ro
```

Release runner는 host 쪽 artifact 디렉터리에 쓰고 Nginx만 읽게 한다. 별도 runner라면 `-ArtifactsRoot`에 운영 host와 공유하는 durable filesystem을 지정하되, file lock과 같은 디렉터리 내 atomic replace를 지원해야 한다. immutable MSIX를 먼저 완전히 기록하고 channel 포인터를 마지막에 갱신하는 순서를 깨는 파일 동기화 도구는 사용하지 않는다.

artifact 디렉터리는 image 재빌드와 독립적으로 보존하고 백업한다. 특히 `releases/`의 과거 서명 MSIX는 자동 업데이트와 승인된 복구에 필요하므로 임의 삭제하지 않는다. 서명된 바이너리는 Git에 commit하지 않는다.

`nginx/nginx.conf`에는 다음 MIME과 캐시 정책이 이미 정의되어 있다.

- `.appinstaller`: `application/appinstaller`
- `.msix`: `application/msix`
- `.json`: `application/json`
- `/downloads/local-agent/releases/**`: `public, max-age=31536000, immutable`
- pilot/stable channel 파일: `no-cache`

TLS edge/CDN이 이 `Content-Type`, `Content-Length`, `Cache-Control`, `X-Content-Type-Options: nosniff` 헤더를 제거하거나 HTML 오류 페이지로 바꾸지 않게 한다.

## 3. backend release 설정 배포

모든 버전은 MSIX 규칙에 맞는 네 자리 형식(`major.minor.build.revision`)을 사용한다. 예를 들어 `0.2.0.0`이다. 저장소의 `docker-compose.local-agent-release.yml`을 기본 Compose와 함께 적용한다.

Git에 포함되지 않는 `.env.local-agent-production`에 현재 stable 값과 정책을 저장한다.

```dotenv
LEARNBOT_LOCAL_AGENT_LATEST_VERSION=0.2.0.0
LEARNBOT_LOCAL_AGENT_MINIMUM_VERSION=0.1.0.0
LEARNBOT_LOCAL_AGENT_UPDATE_URI=/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller
```

규칙은 다음과 같다.

- `LATEST_VERSION`은 외부 검증까지 끝난 stable `release.json.version`과 정확히 같아야 한다.
- `MINIMUM_VERSION`은 해당 release의 `minimumSupportedVersion`과 정확히 같고 `LATEST_VERSION`보다 클 수 없다.
- 최소 버전 미만 agent는 작업 dispatch가 차단되므로, 충분한 업데이트 기간과 pilot 확인 없이 minimum을 올리지 않는다.
- update URI는 같은 origin의 stable `.appinstaller` 상대 경로를 유지한다.

최초 시작 또는 전체 갱신은 다음과 같이 실행한다.

```powershell
docker compose --env-file .env.local-agent-production `
  -f docker-compose.yml `
  -f docker-compose.local-agent-release.yml `
  up -d --build
```

배포 전에는 `docker compose ... config` 출력에서 세 버전 환경 변수가 기대값으로 해석되는지 확인한다.

## 4. public-trust production package를 pilot에 게시

외부 Production에서는 self-signed 또는 `-UnsignedTest`를 사용하지 않는다. 사용자가 인증서를 수동 설치할 필요가 없는 public-trust 서명을 사용한다. Artifact Signing public-trust profile을 권장하며, `Publisher`는 서명 인증서의 전체 subject와 정확히 일치해야 한다. 중앙관리 내부 HTTPS Production은 사내 trust chain이 모든 대상 PC에 정책으로 배포됐다는 조건에서 `-EnterpriseManagedTrust`를 사용할 수 있다. Publish script는 PE 파일과 MSIX를 SHA-256/RFC 3161 timestamp로 서명하고 최종 MSIX signer subject를 검사한다.

Artifact Signing 예시:

```powershell
$Version = "0.2.0.0"
$Minimum = "0.1.0.0"
$Origin = "https://learnbot.example"
$Publisher = "CN=Your verified Artifact Signing subject"
$SdkBin = "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64"
$Dlib = "C:\ArtifactSigning\Azure.CodeSigning.Dlib.dll"
$SigningMetadata = "C:\ArtifactSigning\metadata.json"
$Artifacts = ".\artifacts\local-agent"

.\scripts\local-agent\release\Publish-LocalAgentRelease.ps1 `
  -Version $Version `
  -MinimumSupportedVersion $Minimum `
  -PublicBaseUrl $Origin `
  -Publisher $Publisher `
  -Channel pilot `
  -ArtifactsRoot $Artifacts `
  -WindowsSdkBin $SdkBin `
  -ArtifactSigningDlibPath $Dlib `
  -ArtifactSigningMetadataPath $SigningMetadata
```

공개 CA의 certificate-store 인증서를 사용하는 대안은 마지막 두 Artifact Signing 인자 대신 다음 인자를 사용한다.

```powershell
-CertificateThumbprint "PRODUCTION_CERTIFICATE_SHA1_THUMBPRINT" `
  -AssertPublicTrustCertificate
```

이 명시 플래그를 사용해도 script는 Code Signing EKU, private key, 온라인 chain/revocation, self-signed 여부를 검증하며 실패하면 stable 승격 가능한 metadata를 만들지 않는다.

같은 네 자리 버전을 다시 빌드하지 않는다. Publish script는 이미 존재하거나 channel에서 참조 중인 version의 덮어쓰기를 기본 차단한다. 코드나 package 구성이 바뀌면 revision을 포함한 새 버전을 발행한다.

## 5. pilot 검증

먼저 artifact volume 내부 구조, SHA-256, manifest identity, 필수 executable/assets 및 Authenticode를 검사한다.

```powershell
.\scripts\local-agent\release\Test-LocalAgentRelease.ps1 `
  -ArtifactsRoot $Artifacts `
  -Channel pilot `
  -ExpectedLatestVersion $Version `
  -ExpectedMinimumSupportedVersion $Minimum `
  -RequireSignature
```

`signtool.exe`가 PATH에 없으면 `-SignToolPath "$SdkBin\signtool.exe"`를 추가한다. 이어서 아래의 외부 smoke check에서 `$Channel = "pilot"`로 검증하고, 실제 pilot 사용자 PC에서 다음을 확인한다.

1. `https://learnbot.example/downloads/local-agent/pilot/LearnBotLocalAgent.appinstaller`를 직접 다운로드하고 더블클릭한다.
2. Windows App Installer에 검증된 publisher와 기대 버전이 표시된다.
3. 코드 서명 신뢰가 중앙 배포된 PC에서 일반 사용자에게 관리자 권한이나 인증서 수동 설치를 요구하지 않는다.
4. Setup이 브라우저를 열고, 로그인한 사용자가 연결 요청을 승인할 수 있다. 브라우저 자동 실행이 실패하면 Setup에 표시된 URL/코드를 사용한다.
5. LearnBot의 Local Agent 설정 화면에 PC가 연결됨으로 표시되고 heartbeat/WebSocket 및 작업 실행이 정상이다.
6. 재부팅/로그인 후 StartupHost가 agent를 다시 시작한다.

`ms-appinstaller:` URI scheme은 일반 Windows에서 기본 비활성일 수 있으므로 이에 의존하지 않는다. UI는 `.appinstaller` 파일을 직접 다운로드하고 사용자는 파일을 열어 설치한다.

## 6. 검증된 동일 package를 stable로 승격

Stable용 package를 다시 빌드하지 않는다. 다음 스크립트가 signed pilot을 다시 검증하고 immutable MSIX의 SHA-256을 확인한 뒤, stable `.appinstaller`와 `release.json` 포인터만 atomic하게 갱신한다.

```powershell
.\scripts\local-agent\release\Promote-LocalAgentRelease.ps1 `
  -ArtifactsRoot $Artifacts `
  -Version $Version `
  -SignToolPath "$SdkBin\signtool.exe"
```

Stable을 다시 로컬 검증한다.

```powershell
.\scripts\local-agent\release\Test-LocalAgentRelease.ps1 `
  -ArtifactsRoot $Artifacts `
  -Channel stable `
  -ExpectedLatestVersion $Version `
  -ExpectedMinimumSupportedVersion $Minimum `
  -RequireSignature `
  -SignToolPath "$SdkBin\signtool.exe"
```

외부 stable smoke check가 통과한 뒤 `.env.local-agent-production`의 latest/minimum을 같은 값으로 반영한다. 이미 stack이 실행 중이면 backend만 재생성할 수 있다.

```powershell
docker compose --env-file .env.local-agent-production `
  -f docker-compose.yml `
  -f docker-compose.local-agent-release.yml `
  up -d --no-deps --force-recreate backend
```

마지막으로 사용자 한 명이 화면의 stable 다운로드 버튼으로 설치/업데이트하고 연결 상태까지 확인한다.

## 7. 외부 HEAD/GET smoke check

아래 검사는 TLS edge를 실제로 통과하여 MIME, `Content-Length`, same-origin URL, App Installer identity, MSIX 다운로드 및 SHA-256을 확인한다. Pilot 검증 때는 `$Channel = "pilot"`, 승격 뒤에는 `stable`로 각각 실행한다.

```powershell
$Origin = "https://learnbot.example"
$Channel = "stable"
$ExpectedVersion = "0.2.0.0"
$ExpectedMinimum = "0.1.0.0"
$ChannelRoot = "$Origin/downloads/local-agent/$Channel"
$ReleaseUrl = "$ChannelRoot/release.json"
$AppInstallerUrl = "$ChannelRoot/LearnBotLocalAgent.appinstaller"

function Assert-Mime($Response, [string]$Expected, [string]$Label) {
    $actual = ([string]$Response.Headers["Content-Type"]).Split(";")[0].Trim().ToLowerInvariant()
    if ($actual -ne $Expected) { throw "$Label MIME: expected $Expected, got $actual" }
}

function Assert-Length($Response, [string]$Label) {
    $value = [string]$Response.Headers["Content-Length"]
    if ([string]::IsNullOrWhiteSpace($value) -or [long]$value -le 0) {
        throw "$Label has no valid Content-Length"
    }
}

$releaseHead = Invoke-WebRequest -UseBasicParsing -Method Head -Uri $ReleaseUrl
$installerHead = Invoke-WebRequest -UseBasicParsing -Method Head -Uri $AppInstallerUrl
Assert-Mime $releaseHead "application/json" "release.json HEAD"
Assert-Mime $installerHead "application/appinstaller" "appinstaller HEAD"
Assert-Length $releaseHead "release.json HEAD"
Assert-Length $installerHead "appinstaller HEAD"

$releaseGet = Invoke-WebRequest -UseBasicParsing -Uri $ReleaseUrl
Assert-Mime $releaseGet "application/json" "release.json GET"
$release = $releaseGet.Content | ConvertFrom-Json
if ($release.channel -ne $Channel -or $release.version -ne $ExpectedVersion) {
    throw "Unexpected release channel/version"
}
if ($release.minimumSupportedVersion -ne $ExpectedMinimum) {
    throw "Unexpected minimumSupportedVersion"
}
if ($release.appInstallerUrl -ne $AppInstallerUrl) {
    throw "release.json appInstallerUrl is not the expected same-origin URL"
}
$expectedPackagePrefix = "$Origin/downloads/local-agent/releases/$ExpectedVersion/"
if (-not ([string]$release.packageUrl).StartsWith($expectedPackagePrefix, [StringComparison]::Ordinal)) {
    throw "release.json packageUrl is not the expected immutable same-origin URL"
}

$packageHead = Invoke-WebRequest -UseBasicParsing -Method Head -Uri $release.packageUrl
Assert-Mime $packageHead "application/msix" "MSIX HEAD"
Assert-Length $packageHead "MSIX HEAD"

$installerGet = Invoke-WebRequest -UseBasicParsing -Uri $AppInstallerUrl
Assert-Mime $installerGet "application/appinstaller" "appinstaller GET"
[xml]$appInstaller = $installerGet.Content
$root = $appInstaller.DocumentElement
$main = $root.SelectSingleNode("*[local-name()='MainPackage']")
if ($root.GetAttribute("Uri") -ne $release.appInstallerUrl -or
    $root.GetAttribute("Version") -ne $release.version -or
    $main.GetAttribute("Uri") -ne $release.packageUrl -or
    $main.GetAttribute("Version") -ne $release.version -or
    $main.GetAttribute("Publisher") -ne $release.publisher) {
    throw "App Installer fields do not match release.json"
}

$temporaryMsix = Join-Path ([IO.Path]::GetTempPath()) ("learnbot-" + [Guid]::NewGuid().ToString("N") + ".msix")
try {
    Invoke-WebRequest -UseBasicParsing -Uri $release.packageUrl -OutFile $temporaryMsix
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryMsix).Hash.ToLowerInvariant()
    if ($actualHash -ne ([string]$release.sha256).ToLowerInvariant()) {
        throw "Downloaded MSIX SHA-256 does not match release.json"
    }
    if ((Get-Item -LiteralPath $temporaryMsix).Length -ne [long]$release.sizeBytes) {
        throw "Downloaded MSIX size does not match release.json"
    }
} finally {
    Remove-Item -LiteralPath $temporaryMsix -Force -ErrorAction SilentlyContinue
}

Write-Host "External Local Agent smoke checks passed: $Channel $ExpectedVersion"
```

브라우저 개발자 도구나 `curl.exe -I <URL>`로도 30x redirect가 없고 최종 응답이 200인지 확인한다. CDN을 사용하는 경우 origin과 CDN 양쪽에서 같은 hash가 나와야 한다.

## 롤백 주의 사항

버전 경로의 MSIX는 불변이므로 잘못된 바이너리를 같은 version으로 교체하지 않는다. 가장 안전한 복구는 revision을 올린 forward-fix release다.

`Promote-LocalAgentRelease.ps1 -AllowRollback`은 현재 pilot 포인터가 요청한 더 낮은 signed version을 정확히 가리킬 때만 의도적으로 stable downgrade를 허용한다. 과거 MSIX 파일만 남아 있고 pilot 포인터가 다른 버전이면 이 스크립트로 임의 복원하지 말고 별도의 검토된 recovery release를 만든다.

Rollback을 승인할 때는 다음을 함께 확인한다.

- App Installer template의 `ForceUpdateFromAnyVersion` 때문에 이미 새 버전인 PC도 낮은 stable 버전으로 내려갈 수 있다.
- backend `LATEST_VERSION`과 `MINIMUM_VERSION`을 rollback version과 호환되게 조정하지 않으면 agent가 즉시 `UPDATE_REQUIRED`가 되어 작업이 차단된다.
- Local Agent의 로컬 상태 형식, server API 및 이미 실행된 작업은 package rollback으로 되돌아가지 않는다.
- DB migration이나 protocol 비호환이 있으면 rollback하지 않고 forward fix를 배포한다.

승격, external smoke check, backend 버전 변경은 승인된 maintenance window에서 한 묶음으로 수행하고 변경 전후 `release.json`, hash, 환경 변수 값을 감사 로그에 남긴다.

## 사용자에게 보이는 설치 흐름

운영 완료 후 일반 사용자 흐름은 다음과 같다.

1. 사용자가 회사망에서 해당 서버의 LearnBot origin(현재 pilot은 RFC1918 HTTP, 추후 운영은 내부 HTTPS)에 로그인한다.
2. Local Agent 설정 화면에서 **Windows용 다운로드**를 누른다.
3. 다운로드된 `LearnBotLocalAgent.appinstaller`를 열고, 검증된 publisher를 확인한 뒤 **설치**를 누른다.
4. 원래 LearnBot 화면의 **이 PC 연결**을 눌러 현재 server origin을 Agent에 전달하고, Setup이 띄운 승인 화면에서 연결 요청을 승인한다. 서버 PC나 관리자 PowerShell에 접근할 필요가 없다.
5. 여러 PC가 등록된 경우 설정 화면에서 작업에 사용할 PC를 명시적으로 선택한다.

중앙 인증서 정책이 적용된 일반 사용자 흐름에서는 UAC 관리자 승인, 별도 .NET 설치, 인증서 수동 설치가 없어야 한다. `0x800B010A`가 나오면 패키지 손상보다 먼저 `Local Computer\Trusted People`의 signer CER와 지문, root/intermediate chain 및 정책 적용 시점을 확인한다.

## 운영 점검과 장애 조사

- TLS 인증서 만료, stable/pilot `release.json` 5xx/404, MSIX hash 불일치, 잘못된 MIME 및 WebSocket disconnect를 모니터링한다.
- 배포 후 `docker compose ... logs backend nginx`에서 enrollment rate-limit, update-required, WebSocket 오류를 확인한다.
- App Installer 오류는 사용자 PC의 Event Viewer `Applications and Services Logs > Microsoft > Windows > AppxDeployment-Server > Operational`과 Desktop App Installer 진단 로그에서 조사한다.
- 서명 key/profile, Artifact Signing metadata 및 production certificate 접근 권한은 release runner에만 둔다. artifact web root에 복사하지 않는다.
- `artifacts/local-agent/releases` 보존 정책과 백업 복원 절차를 정기적으로 시험한다.

## 공식 참고 자료

- Microsoft Learn: [Sign an MSIX package](https://learn.microsoft.com/en-us/windows/msix/package/signing-package-overview)
- Microsoft Learn: [Set up signing integrations to use Artifact Signing](https://learn.microsoft.com/en-us/azure/artifact-signing/how-to-signing-integrations)
- Microsoft Learn: [App Installer file overview](https://learn.microsoft.com/en-us/windows/msix/app-installer/app-installer-file-overview)
- Microsoft Learn: [Installing Windows apps from a web page and required MIME types](https://learn.microsoft.com/en-us/windows/msix/app-installer/installing-windows10-apps-web)
- Microsoft Learn: [Troubleshoot App Installer issues](https://learn.microsoft.com/en-us/windows/msix/app-installer/troubleshoot-appinstaller-issues)
- Microsoft Learn: [MSIX troubleshooting guide, certificate trust and MIME requirements](https://learn.microsoft.com/en-us/windows/msix/msix-troubleshooting-guide)
- Microsoft Learn: [Handle URI activation in a packaged Windows app](https://learn.microsoft.com/en-us/windows/apps/develop/launch/handle-uri-activation)
