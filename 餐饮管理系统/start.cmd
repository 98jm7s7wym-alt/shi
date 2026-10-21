@echo off
setlocal
cd /d "%~dp0"

powershell -NoProfile -Command "try { $client = New-Object Net.Sockets.TcpClient; $client.Connect('127.0.0.1', 8080); $client.Close(); exit 0 } catch { exit 1 }"
if not errorlevel 1 (
  echo The system is already running at http://localhost:8080
  start "" "http://localhost:8080"
  exit /b 0
)

set "JAR=target\food-material-system-1.0.0.jar"
if not exist "%JAR%" (
  echo Building the application for the first time...
  set "MAVEN_HOME=%~dp0apache-maven-3.9.16"
  if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Maven was not found. Install Maven or keep apache-maven-3.9.16 in this directory.
    pause
    exit /b 1
  )
  set "MAVEN_OPTS=-Duser.home=%USERPROFILE%"
  call "%MAVEN_HOME%\bin\mvn.cmd" package -DskipTests
  if errorlevel 1 (
    echo Build failed.
    pause
    exit /b 1
  )
)

echo Starting the system. The browser will open shortly.
start "" powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 12; Start-Process 'http://localhost:8080'"
java -jar "%JAR%" --spring.profiles.active=local

if errorlevel 1 (
  echo The application stopped with an error.
  pause
)
