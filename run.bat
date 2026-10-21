@echo off
setlocal
cd /d "%~dp0"
call "%~dp0mvnw.cmd" -q javafx:run
if errorlevel 1 (
    echo.
    echo The app could not start. Check the Maven or Java error above.
    pause
)
endlocal
