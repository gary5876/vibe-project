#!/usr/bin/env bash
# ============================================
# 🚀 CAPSTONE BACKEND DEPLOY SCRIPT (No Logs)
# ============================================

# 1. 스크립트 실행 위치를 프로젝트 루트로 고정
cd "$(dirname "$0")" || exit 1

# 2. JAR 빌드 (테스트 제외)
./gradlew clean bootJar -x test || {
  echo "[ERROR] Build failed. Stop deploying."
  exit 1
}

# 3. 이전 프로세스 종료 (현재 jar 기준으로만)
PID=$(pgrep -f 'java -jar.*build/libs' || true)
if [ -n "$PID" ]; then
  echo "[INFO] Stopping existing process (PID=$PID)"
  kill -9 "$PID"
fi

# 4. 새 JAR 파일 찾기
JAR_FILE=$(find build/libs -type f -name "*.jar" | head -n 1)
if [ -z "$JAR_FILE" ]; then
  echo "[ERROR] No JAR file found in build/libs."
  exit 1
fi

# 5. 백그라운드로 실행 (로그 완전 차단)
nohup java -jar -Dspring.profiles.active=prod "$JAR_FILE" >/dev/null 2>&1 &

# 6. 실행 확인
sleep 2
NEW_PID=$(pgrep -f "$JAR_FILE" || true)
if [ -n "$NEW_PID" ]; then
  echo "[SUCCESS] Application started successfully (PID=$NEW_PID)"
else
  echo "[ERROR] Failed to start application."
fi
