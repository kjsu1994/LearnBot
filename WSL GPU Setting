# WSL Docker GPU 설정 가이드

목표:
- 새 Windows PC에서 WSL2 Ubuntu가 NVIDIA GPU를 인식하게 만든다.
- WSL 내부 Docker Engine에서 GPU 컨테이너를 실행할 수 있게 만든다.
- LearnBot을 GPU compose 모드로 실행한다.

주의:
- 아래 명령은 Windows PowerShell과 WSL Ubuntu 터미널을 구분해서 실행한다.
- NVIDIA GPU가 없는 PC에서는 GPU 모드가 동작하지 않는다.
- Docker Desktop을 쓰는 방식과 WSL 내부 Docker Engine을 쓰는 방식이 다르다. 이 문서는 WSL 내부 Docker Engine 기준이다.

============================================================
1. Windows에 NVIDIA 드라이버 설치
============================================================

1) NVIDIA 공식 드라이버를 Windows에 설치한다.
   - GeForce/RTX 계열은 NVIDIA Game Ready Driver 또는 Studio Driver 설치
   - 설치 후 PC 재부팅 권장

2) Windows PowerShell에서 GPU 인식 확인:

```powershell
nvidia-smi
```

정상 기준:
- GPU 이름, Driver Version, CUDA Version이 표시된다.

실패 시:
- Windows 드라이버 설치가 안 되었거나 잘못 설치된 상태다.
- WSL 설정으로 넘어가기 전에 Windows에서 `nvidia-smi`가 먼저 성공해야 한다.

============================================================
2. WSL2 설치 및 업데이트
============================================================

Windows PowerShell을 관리자 권한으로 열고 실행:

```powershell
wsl --install
wsl --update
wsl --shutdown
```

Ubuntu를 실행한 뒤 WSL 터미널에서 확인:

```bash
nvidia-smi
```

정상 기준:
- WSL 안에서도 GPU 이름과 Driver Version이 표시된다.

실패 시:
- Windows NVIDIA 드라이버가 WSL GPU를 지원하지 않거나 WSL이 오래된 상태다.
- 다시 아래를 실행 후 Ubuntu를 재시작한다.

```powershell
wsl --update
wsl --shutdown
```

============================================================
3. WSL Ubuntu 안에 Docker Engine 설치 확인
============================================================

WSL Ubuntu 터미널에서 확인:

```bash
docker version
docker info
```

정상 기준:
- Docker Server 정보가 나온다.
- `Cannot connect to the Docker daemon` 오류가 없어야 한다.

Docker가 없다면 Docker Engine을 설치한다.
Ubuntu 24.04 기준 예시:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gpg

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

. /etc/os-release

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  ${VERSION_CODENAME} stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable docker
sudo systemctl start docker
```

현재 사용자를 docker 그룹에 추가하려면:

```bash
sudo usermod -aG docker $USER
```

그 후 WSL 재시작:

```powershell
wsl --shutdown
```

다시 Ubuntu를 열고 확인:

```bash
docker version
```

============================================================
4. NVIDIA Container Toolkit 설치
============================================================

WSL Ubuntu 터미널에서 실행:

```bash
sudo apt-get update
sudo apt-get install -y curl gpg ca-certificates

sudo install -m 0755 -d /etc/apt/keyrings

curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
  | sudo gpg --dearmor -o /etc/apt/keyrings/nvidia-container-toolkit.gpg

curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  | sed 's#deb https://#deb [signed-by=/etc/apt/keyrings/nvidia-container-toolkit.gpg] https://#g' \
  | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list > /dev/null

sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
```

설치 확인:

```bash
command -v nvidia-ctk
command -v nvidia-container-cli
```

정상 기준:
- `/usr/bin/nvidia-ctk`
- `/usr/bin/nvidia-container-cli`
  같은 경로가 출력된다.

============================================================
5. Docker에 NVIDIA runtime 및 CDI 설정
============================================================

WSL Ubuntu 터미널에서 실행:

```bash
sudo nvidia-ctk runtime configure --runtime=docker

sudo mkdir -p /etc/cdi
sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml

sudo systemctl restart docker
```

만약 `systemctl`이 동작하지 않으면 대신 실행:

```bash
sudo service docker restart
```

설정 확인:

```bash
docker info | grep -Ei 'runtimes|nvidia|cdi'
ls -l /etc/cdi/nvidia.yaml
```

정상 기준:
- Docker runtime 또는 CDI 관련 정보가 보인다.
- `/etc/cdi/nvidia.yaml` 파일이 존재한다.

============================================================
6. Docker 컨테이너에서 GPU 테스트
============================================================

WSL Ubuntu 터미널에서 실행:

```bash
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

정상 기준:
- 컨테이너 안에서 `nvidia-smi` 결과가 출력된다.
- GPU 이름이 표시된다.

실패 예시:

```text
failed to discover GPU vendor from CDI: no known GPU vendor found
```

해결:
- NVIDIA Container Toolkit 설치 여부 확인
- `/etc/cdi/nvidia.yaml` 생성 여부 확인
- Docker 재시작 여부 확인

```bash
sudo nvidia-ctk runtime configure --runtime=docker
sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
sudo systemctl restart docker
```

============================================================
7. LearnBot GPU compose 실행
============================================================

WSL Ubuntu 터미널에서 LearnBot 디렉터리로 이동:

```bash
cd ~/LearnBot
```

GPU compose 실행:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

상태 확인:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml ps
```

Ollama GPU 인식 로그 확인:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml logs --tail=120 ollama
```

정상 기준:
- 로그에 `library=CUDA`가 보인다.
- GPU 이름이 보인다.
- 예: `NVIDIA GeForce RTX ...`

============================================================
8. 자주 발생하는 문제
============================================================

문제 1: `failed to discover GPU vendor from CDI: no known GPU vendor found`

원인:
- Docker가 GPU runtime/CDI를 모르는 상태다.

조치:

```bash
sudo apt-get install -y nvidia-container-toolkit
sudo nvidia-ctk runtime configure --runtime=docker
sudo mkdir -p /etc/cdi
sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
sudo systemctl restart docker
```

문제 2: NVIDIA 저장소 404 오류

오류 예시:

```text
The repository 'https://nvidia.github.io/libnvidia-container/stable/ubuntu22.04 Release' does not have a Release file.
```

원인:
- 오래되었거나 잘못된 NVIDIA APT repo가 등록되어 있다.

조치:

```bash
sudo rm -f /etc/apt/sources.list.d/nvidia-container-toolkit.list
sudo rm -f /usr/share/keyrings/nvidia-container-toolkit.gpg
sudo rm -f /etc/apt/keyrings/nvidia-container-toolkit.gpg
```

그 다음 4번 NVIDIA Container Toolkit 설치부터 다시 진행한다.

문제 3: WSL 안에서는 `nvidia-smi`가 안 되는데 Windows에서는 된다

조치:

Windows PowerShell 관리자 권한:

```powershell
wsl --update
wsl --shutdown
```

그 후 Ubuntu를 다시 열고:

```bash
nvidia-smi
```

문제 4: `Cannot connect to the Docker daemon`

조치:

```bash
sudo systemctl start docker
```

또는:

```bash
sudo service docker start
```

문제 5: `permission denied while trying to connect to the Docker daemon socket`

조치:

```bash
sudo usermod -aG docker $USER
```

그 후 Windows PowerShell에서:

```powershell
wsl --shutdown
```

Ubuntu를 다시 열고 Docker 명령을 재시도한다.

============================================================
9. 최종 성공 체크리스트
============================================================

아래 4개가 모두 성공하면 GPU 모드 준비 완료다.

1) Windows PowerShell:

```powershell
nvidia-smi
```

2) WSL Ubuntu:

```bash
nvidia-smi
```

3) WSL Ubuntu Docker GPU 테스트:

```bash
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

4) LearnBot GPU compose:

```bash
cd ~/LearnBot
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

Ollama CUDA 확인:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml logs --tail=120 ollama
```

로그에 다음과 비슷한 내용이 있으면 정상:

```text
library=CUDA
name=CUDA0
NVIDIA GeForce RTX ...
```
