# Maven Central 배포

FrameKit SDK 10개 모듈을 `com.naurylab:<모듈>:<버전>`으로 Maven Central에 올리는 방법입니다. 저장소의 `app`(Showcase)은 배포하지 않습니다.

빌드 설정은 이미 들어 있습니다. 아래 **처음 한 번 준비**만 끝내면, 이후 배포는 [배포하기](#배포하기)의 명령 두 줄입니다.

## 처음 한 번 준비

### 1. Central Portal 가입

https://central.sonatype.com 에 가입합니다. GitHub 또는 Google 계정으로 로그인할 수 있습니다.

### 2. `com.naurylab` 네임스페이스 등록과 도메인 인증

1. 포털 오른쪽 위 계정 메뉴 → **View Namespaces** → **Register New Namespace**에서 `com.naurylab`을 입력합니다.
2. **Verification Key**(무작위 문자열)가 나옵니다.
3. `naurylab.com`을 관리하는 곳(가비아, Cloudflare, AWS Route 53 등)의 DNS 설정에서 TXT 레코드를 추가합니다.
   - 호스트(이름): `@` (도메인 루트)
   - 값: 받은 Verification Key
4. 반영되면(보통 몇 분, 길면 몇 시간) 포털에서 **Verify Namespace**를 누릅니다. 상태가 **Verified**가 되면 끝입니다.
5. 인증이 끝난 뒤 TXT 레코드는 지워도 됩니다.

반영 여부는 터미널에서 확인할 수 있습니다: `dig TXT naurylab.com +short`

### 3. 서명 키(GPG) 만들기

Maven Central은 모든 배포 파일에 PGP 서명을 요구합니다.

```bash
brew install gnupg
gpg --full-generate-key          # 종류: RSA and RSA, 크기: 4096, 만료: 원하는 기간, 이름·이메일 입력, 비밀번호 설정
gpg --list-secret-keys --keyid-format=long   # sec rsa4096/<키ID> 줄의 키ID(16자리)를 확인
gpg --keyserver keyserver.ubuntu.com --send-keys <키ID>   # 공개 키를 키 서버에 올림(Central이 서명을 확인할 때 씀)
```

비밀 키는 백업해 두세요. 잃어버리면 같은 키로 다시 서명할 수 없습니다: `gpg --export-secret-keys --armor <키ID> > framekit-signing.asc` (안전한 곳에 보관, 저장소에 올리지 말 것)

### 4. 배포용 토큰 만들기

포털 계정 메뉴 → **View Account** → **Generate User Token**. 사용자명과 비밀번호 한 쌍이 나옵니다(계정 비밀번호가 아니라 이 토큰을 씁니다).

### 5. 내 컴퓨터에 자격 증명 저장

`~/.gradle/gradle.properties`(사용자 홈, **저장소 밖**)에 넣습니다. 이 파일은 git에 올라가지 않습니다.

```properties
# Central Portal 사용자 토큰
centralPortalUsername=<토큰 사용자명>
centralPortalPassword=<토큰 비밀번호>

# 설치한 gpg로 서명할 때
signing.gnupg.keyName=<키ID>
signing.gnupg.passphrase=<키 비밀번호>
```

gpg를 쓰지 않고 키 파일 내용으로 서명하려면(CI 등) 위 두 `signing.gnupg.*` 대신 다음을 씁니다.

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...(framekit-signing.asc 내용, 줄바꿈은 \n)...
signingInMemoryKeyPassword=<키 비밀번호>
```

CI에서는 같은 이름에 `ORG_GRADLE_PROJECT_`를 붙인 환경 변수(예: `ORG_GRADLE_PROJECT_signingInMemoryKey`)로 넣으면 됩니다.

## 배포하기

1. `gradle.properties`의 `FRAMEKIT_VERSION`을 이번 버전으로 바꿉니다(아래 [버전 규칙](#버전-규칙)).
2. 검증과 배포:

```bash
./gradlew test lint                                  # 검증
./gradlew cleanCentralStaging publishToCentral --no-configuration-cache
```

- `publishToCentral`은 10개 모듈을 서명해 `build/central-bundle.zip`으로 묶고 Central Portal에 올립니다. 서명되지 않은 파일이 하나라도 있으면 올리지 않고 멈춥니다.
- 기본은 **검증 후 대기**입니다. https://central.sonatype.com/publishing/deployments 에서 검증 결과(Validated)를 확인한 뒤 **Publish**를 누르면 공개됩니다. 문제가 있으면 **Drop**으로 버리고 고쳐서 다시 올립니다.
- 확인 없이 바로 공개하려면 `-PcentralAutoPublish=true`를 붙입니다.
- 공개 후 검색(search.maven.org)에 보이기까지 보통 10~30분 걸립니다.

3. 배포한 커밋에 태그를 달고 올립니다.

```bash
git tag v1.0.0-alpha01
git push origin v1.0.0-alpha01
```

업로드 없이 묶음만 만들어 내용을 보려면 `./gradlew cleanCentralStaging centralBundle --no-configuration-cache`를 씁니다.

## 버전 규칙

[Semantic Versioning](https://semver.org/lang/ko/)을 따릅니다: `주.부.수`.

| 바뀐 것 | 올리는 자리 | 예 |
| --- | --- | --- |
| 호스트 코드를 고쳐야 하는 API 변경 | 주 | 1.4.2 → 2.0.0 |
| 기능 추가(기존 코드는 그대로 동작) | 부 | 1.4.2 → 1.5.0 |
| 버그 수정 | 수 | 1.4.2 → 1.4.3 |

- 정식 전에는 `-alpha01`, `-beta01`, `-rc01`을 붙입니다.
- Maven Central에 한 번 올린 버전은 **지우거나 덮어쓸 수 없습니다.** 잘못 올렸으면 다음 번호로 고쳐서 다시 올립니다.
- 10개 모듈은 항상 같은 버전으로 함께 배포합니다(`FRAMEKIT_VERSION` 하나).
- 바뀐 내용은 [release-notes.md](release-notes.md)에 적습니다.

## 문제 해결

| 증상 | 원인·해결 |
| --- | --- |
| `서명되지 않은 파일이 있습니다` | `~/.gradle/gradle.properties`의 서명 설정이 없거나 틀림. `gpg --list-secret-keys`로 키ID 확인 |
| 업로드 401/403 | 토큰이 틀렸거나 계정 비밀번호를 넣음. 포털에서 User Token을 다시 만들기 |
| 검증 실패: namespace not verified | `com.naurylab` 인증이 아직 안 됨(2단계) |
| 검증 실패: invalid signature | 공개 키를 키 서버에 올리지 않음(3단계 `--send-keys`) |
| 검증 실패: version already exists | 이미 올린 버전. `FRAMEKIT_VERSION`을 올려서 다시 배포 |
