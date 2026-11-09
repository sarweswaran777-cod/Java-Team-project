@echo off
REM ============================================================================
REM KEC Autonomous Hostel Utility & Mess Fee Engine - Build and Run Script (Windows)
REM ============================================================================

echo [KEC HOSTEL] Cleaning old classes...
if not exist bin mkdir bin
del /Q bin\*.class 2>nul
del /Q *.class 2>nul

echo [KEC HOSTEL] Compiling Java source files into bin directory...
javac -d bin -cp ".;mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java HostelEngineTest.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b %ERRORLEVEL%
)

echo [KEC HOSTEL] Compilation successful!
echo.
echo Choose an option:
echo   1. Run Interactive Application Menu
echo   2. Run Complete Automated Billing Pipeline (--auto)
echo   3. Run Automated Test Verification Suite (HostelEngineTest)
echo   4. Run BillingWorker Block A Standalone Test
echo.
set /p opt="Enter choice [1-4] (Default 1): "

if "%opt%"=="2" (
    java -cp "bin;mysql-connector-j-8.4.0.jar" MainApp --auto
) else if "%opt%"=="3" (
    java -cp "bin;mysql-connector-j-8.4.0.jar" HostelEngineTest
) else if "%opt%"=="4" (
    java -cp "bin;mysql-connector-j-8.4.0.jar" BillingWorker
) else (
    java -cp "bin;mysql-connector-j-8.4.0.jar" MainApp
)

pause
