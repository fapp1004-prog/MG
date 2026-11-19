#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
rm -rf out-test && mkdir out-test
find src test -name '*.java' > out-test/sources.txt
javac --release 17 -encoding UTF-8 -d out-test @out-test/sources.txt
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dmg.home="$(pwd)" -cp out-test mg.TestMain
