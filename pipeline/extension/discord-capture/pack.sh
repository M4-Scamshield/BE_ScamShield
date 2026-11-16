#!/usr/bin/env bash
# 시연용 배포 묶음. 확장 실행에 필요한 파일만 담는다 (dev/out 녹음·node_modules·테스트는 절대 넣지 않는다).
# 사용: bash pack.sh  → dist/scamshield-discord-extension.zip
set -euo pipefail
cd "$(dirname "$0")"
NAME=scamshield-discord-extension
STAGE=$(mktemp -d)/$NAME
mkdir -p "$STAGE" dist
cp manifest.json popup.html popup.js "$STAGE/"
cp -R src "$STAGE/src"
cat > "$STAGE/README.txt" <<'TXT'
ScamShield Discord 확장 설치 (터미널 필요 없음, Chrome 기준)

1. 이 zip 을 푼다. 푼 폴더는 지우거나 옮기지 않는다 (Chrome 이 이 폴더를 계속 읽는다).
2. Chrome 주소창에 chrome://extensions 입력
3. 오른쪽 위 "개발자 모드" 켜기
4. "압축해제된 확장 프로그램을 로드합니다" → 푼 폴더(scamshield-discord-extension) 선택
5. 웹 Discord(https://discord.com) 탭을 새로고침 (설치 전에 열려 있던 탭은 꼭)
6. 음성 채널에 들어간 뒤, 툴바의 확장 아이콘(퍼즐 모양에서 고정 가능) → "수집 시작"
   - 서버 주소는 https://boostad.site 가 기본값이라 바꾸지 않는다
   - 팝업 표의 "보낸 초"가 올라가면 정상. 사기 의심 대화가 나오면 Discord 화면 오른쪽 위에 경고가 뜬다

알아둘 것
- 웹 Discord(Chrome)에서만 동작한다. Discord 데스크톱 앱에서는 안 된다.
- 기본은 꺼져 있다. "수집 시작"을 누를 때만, 통화 음성이 분석 서버로 간다. 같이 통화하는 사람에게 미리 알린다.
- 한 통화에서 동시에 보내는 화자는 최대 4명.
- 그만둘 때는 팝업의 "끄기" 또는 Discord 탭 닫기.
TXT
rm -f "dist/$NAME.zip"
( cd "$(dirname "$STAGE")" && zip -qr -X "$OLDPWD/dist/$NAME.zip" "$NAME" )
rm -rf "$(dirname "$STAGE")"
echo "dist/$NAME.zip"
