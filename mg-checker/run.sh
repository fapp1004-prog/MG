#!/usr/bin/env bash
# MG 실행 (macOS / Linux)
set -e
cd "$(dirname "$0")"
command -v javac >/dev/null || { echo "[MG] javac가 없습니다. JDK 17 이상을 설치하세요."; exit 1; }
if [ ! -f mg.properties ]; then
  cp mg.properties.example mg.properties
  echo "[MG] mg.properties를 만들었습니다. api.key= 뒤에 키를 넣고 다시 실행하세요. (키 없이 보려면 provider=mock)"
  exit 0
fi
rm -rf out && mkdir out
find src -name '*.java' > out/sources.txt
javac --release 17 -encoding UTF-8 -d out @out/sources.txt
echo "[MG] 브라우저에서 http://127.0.0.1:8090 을 여세요."
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dmg.home="$(pwd)" -cp out mg.Main
