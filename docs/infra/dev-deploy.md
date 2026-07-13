# 개발 서버 배포 문서

> 이 문서는 개발 서버 배포 구조와 운영 기준을 정리한다.
> 비밀번호, 토큰, access key, private key, 공개 IP, AWS 계정 ID, DB 계정, DB 비밀번호, 내부 AWS 리소스 endpoint 값은 문서에 작성하지 않는다.

---

## 1. 개요

개발 환경은 GitHub Actions를 통해 EC2에 자동 배포한다.

배포 흐름은 다음과 같다.

1. `develop` 브랜치에 push 또는 merge한다.
2. GitHub Actions가 Spring Boot 애플리케이션을 빌드한다.
3. GitHub Actions가 Docker 이미지를 빌드하고 Docker Hub에 push한다.
4. GitHub Actions가 SSH로 개발 EC2에 접속한다.
5. GitHub Actions가 `docker/docker-compose.dev.yml`을 EC2 배포 디렉터리에 업로드한다.
6. EC2가 최신 Docker 이미지를 pull하고 애플리케이션 컨테이너를 재시작한다.
7. GitHub Actions가 `/actuator/health`로 배포 상태를 확인한다.

---

## 2. 배포 전 확인 사항

개발 서버 배포 전 다음 항목을 확인한다.

- EC2에 Docker, Docker Compose, Nginx가 설치되어 있다.
- 배포 계정(`DEV_EC2_USER`, 기본값 `ubuntu`)이 sudo 없이 Docker와 Docker Compose를 실행할 수 있다.
- EC2에 `/home/ubuntu/hashi-dev/.env.dev`가 존재한다.
- GitHub Secrets에 배포에 필요한 값이 등록되어 있다.
- Docker Hub에 애플리케이션 이미지를 push할 수 있다.
- EC2에서 RDS MySQL에 접속할 수 있다.
- EC2에서 ElastiCache Redis에 접속할 수 있다.
- EC2 IAM Role로 S3 object upload/download/delete가 가능하다.
- Nginx가 애플리케이션 컨테이너의 `127.0.0.1:8080`으로 proxy한다.
- 개발 API 도메인이 EC2로 연결되어 있다.
- 개발 API 도메인에 HTTPS 인증서가 적용되어 있다.

---

## 3. AWS 리소스

개발 환경은 다음 AWS 리소스를 사용한다.

| 리소스 | 목적 | 비고 |
| --- | --- | --- |
| EC2 | Docker, Docker Compose, Nginx, 애플리케이션 컨테이너 실행 | 런타임 환경변수는 EC2 내부에서 관리한다. |
| RDS MySQL | 개발 DB | 애플리케이션 서버 보안 그룹에서만 접근 가능해야 한다. |
| ElastiCache Redis | refresh token 및 cache 저장소 | 애플리케이션은 primary Redis endpoint를 사용한다. |
| S3 | 업로드 파일 저장 | 애플리케이션은 S3 object key를 저장한다. |
| CloudFront | 업로드 파일 HTTPS 조회 경로 | CloudFront가 private S3 bucket에 접근한다. |
| IAM Role | EC2의 AWS 리소스 접근 권한 부여 | 정적 AWS access key 대신 EC2 IAM Role을 사용한다. |

실제 AWS 리소스 endpoint나 인증 정보는 repository에 커밋하지 않는다.

---

## 4. GitHub Secrets

GitHub Actions에는 배포에 필요한 값만 저장한다.

| Secret | 목적 |
| --- | --- |
| `DOCKER_USERNAME` | Docker Hub 이미지 push/pull에 사용할 사용자명 |
| `DOCKER_TOKEN` | GitHub Actions 및 EC2 Docker login에 사용할 Docker Hub token |
| `DEV_EC2_HOST` | 개발 EC2 SSH host |
| `DEV_EC2_USER` | 개발 EC2 SSH user |
| `DEV_EC2_SSH_KEY` | GitHub Actions가 EC2에 접속할 때 사용할 private key |

배포 전략이 바뀌기 전까지 애플리케이션 런타임 secret은 GitHub Actions에 중복 저장하지 않는다.

---

## 5. EC2 런타임 환경변수

애플리케이션은 다음 파일에서 런타임 환경변수를 읽는다.

```text
/home/ubuntu/hashi-dev/.env.dev
```

이 파일은 EC2 내부에만 존재해야 하며, 권한은 제한한다.

```bash
chmod 600 /home/ubuntu/hashi-dev/.env.dev
```

필수 key는 다음과 같다.

```text
DOCKER_USERNAME

DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD

REDIS_HOST
REDIS_PORT

JWT_SECRET

KAKAO_CLIENT_ID
KAKAO_CLIENT_SECRET
KAKAO_REDIRECT_URI

AWS_REGION
AWS_S3_BUCKET
CLOUDFRONT_DOMAIN

CORS_ALLOWED_ORIGINS
CORS_ALLOWED_ORIGIN_PATTERNS
```

`CORS_ALLOWED_ORIGINS`에는 고정된 프론트엔드 Origin을 콤마로 구분해 입력한다.
Vercel Preview처럼 배포마다 호스트가 달라지는 경우에만 `CORS_ALLOWED_ORIGIN_PATTERNS`에
Spring 와일드카드 패턴을 입력한다.

```text
CORS_ALLOWED_ORIGIN_PATTERNS=https://hashi-client-*-gyeongbinmins-projects.vercel.app
```

크리덴셜을 포함한 요청을 허용하므로 `https://*.vercel.app`처럼 다른 프로젝트까지 포함하는
넓은 패턴은 사용하지 않는다.

다음 key는 `.env.dev`에 넣지 않는다.

```text
AWS_ACCESS_KEY
AWS_SECRET_KEY
```

애플리케이션 서버의 AWS 접근은 EC2 IAM Role을 사용한다.

---

## 6. Docker Compose

개발 서버는 다음 compose 파일을 사용한다.

```text
docker/docker-compose.dev.yml
```

compose 파일은 다음 경로의 환경변수 파일을 읽는다.

```yaml
env_file:
  - ../.env.dev
```

애플리케이션 컨테이너는 localhost에만 바인딩한다.

```yaml
ports:
  - "127.0.0.1:8080:8080"
```

외부 HTTP 요청은 애플리케이션 컨테이너로 직접 접근하지 않고 Nginx를 통해 전달한다.

---

## 7. 도메인 및 HTTPS

개발 API 서버는 별도 API 서브도메인을 사용한다.

```text
https://dev-api.hashi.kr
```

도메인 관리는 다음 원칙을 따른다.

- `hashi.kr`, `www.hashi.kr` 등 프론트 메인 도메인은 백엔드 배포 문서에서 관리하지 않는다.
- 전체 도메인의 네임서버를 Route 53으로 이전하지 않는다.
- API 서버에 필요한 서브도메인만 도메인 구매처의 DNS에서 관리한다.
- 개발 API 서브도메인은 개발 EC2의 Elastic IP를 바라보는 A 레코드로 연결한다.
- 운영 API 서브도메인은 운영 서버가 준비된 뒤 별도로 연결한다.

HTTPS는 EC2의 Nginx와 Certbot/Let's Encrypt를 사용해 적용한다.

- HTTP 요청은 HTTPS로 리다이렉트한다.
- 인증서는 Certbot의 systemd timer로 자동 갱신한다.
- 인증서 파일 경로와 private key는 문서나 repository에 기록하지 않는다.

HTTPS 적용 후 외부 확인은 다음 경로를 기준으로 한다.

```bash
curl -I https://dev-api.hashi.kr/actuator/health
```

애플리케이션 컨테이너가 아직 실행 중이 아니면 HTTPS 연결은 성공하더라도 Nginx에서 `502 Bad Gateway`가 응답될 수 있다.
이 경우 TLS/DNS 문제가 아니라 애플리케이션 upstream 문제로 보고 컨테이너 상태를 확인한다.

---

## 8. Nginx

Nginx는 외부 HTTP 요청을 받고 애플리케이션 컨테이너로 proxy한다.

proxy 대상은 다음과 같다.

```text
http://127.0.0.1:8080
```

Nginx 설정을 변경한 뒤에는 문법 검사 후 Nginx만 reload한다.

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Nginx systemd unit file을 변경한 경우에만 systemd 설정을 다시 읽고 Nginx를 reload한다.

```bash
sudo systemctl daemon-reload
sudo nginx -t
sudo systemctl reload nginx
```

---

## 9. 보안 그룹

개발 환경 보안 그룹은 다음 원칙을 따른다.

- HTTP는 HTTPS 리다이렉트와 인증서 갱신 검증을 위해 허용한다.
- HTTPS는 외부 애플리케이션 접근을 위해 허용한다.
- SSH는 현재 GitHub Actions 배포를 위해 사용한다.
- RDS MySQL은 애플리케이션 서버 보안 그룹에서만 접근 가능해야 한다.
- ElastiCache Redis는 애플리케이션 서버 보안 그룹에서만 접근 가능해야 한다.
- RDS와 Redis는 public IPv4 범위에 노출하지 않는다.

SSH를 전체 IPv4 범위에 여는 설정은 개발 배포를 위한 임시 설정으로만 사용한다.
추후 다음 방식 중 하나로 변경하는 것을 권장한다.

- AWS Systems Manager Session Manager
- GitHub Actions runner IP allowlist 자동화
- VPC 내부 self-hosted runner
- 접근 범위가 제한된 bastion host

---

## 10. S3 및 CloudFront

S3 bucket은 private 상태를 유지한다.

업로드 파일의 public read 경로는 CloudFront를 사용한다.

조회 흐름은 다음과 같다.

```text
Client -> CloudFront -> private S3 bucket
```

CloudFront는 Origin Access Control(OAC) 등 private origin access 방식을 사용해 S3에 접근한다.

업로드는 애플리케이션이 presigned URL을 발급하고, 클라이언트가 S3에 직접 업로드한다.

애플리케이션은 전체 presigned URL이 아니라 S3 object key만 저장한다.

---

## 11. 배포 확인

배포 후 애플리케이션 health endpoint를 확인한다.

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

실행 중인 컨테이너를 확인한다.

```bash
docker ps
```

애플리케이션 로그를 확인한다.

```bash
docker logs --tail=200 hashi-dev-app
```

외부에서는 Nginx를 통해 HTTP 요청이 전달되는지 확인한다.

```bash
curl -I https://dev-api.hashi.kr/actuator/health
```

---

## 12. 운영 메모

- `.env.dev`는 커밋하지 않는다.
- AWS access key는 커밋하지 않는다.
- 애플리케이션 런타임을 위해 장기 AWS access key를 EC2에 저장하지 않는다.
- 현재 개발 환경에서는 런타임 secret을 EC2 내부 `.env.dev`에서 관리한다.
- 프론트 메인 도메인과 API 서브도메인 관리는 책임 범위를 분리한다.
- 운영 환경에서는 AWS Systems Manager Parameter Store 또는 AWS Secrets Manager 사용을 우선 고려한다.
- SSM 또는 Secrets Manager 기반으로 배포 전략이 바뀌면 이 문서와 `docker-compose.dev.yml`을 함께 수정한다.
