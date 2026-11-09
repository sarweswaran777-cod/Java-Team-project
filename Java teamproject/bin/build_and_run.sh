#!/usr/bin/env bash
# ============================================================================
# KEC Autonomous Hostel Utility & Mess Fee Engine - Build and Run Script (Linux/OCI)
# ============================================================================

set -e

echo "[KEC HOSTEL] Cleaning old class files..."
mkdir -p bin
rm -f bin/*.class *.class

echo "[KEC HOSTEL] Compiling Java source files into bin directory..."
javac -d bin -cp ".:mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java HostelEngineTest.java

echo "[KEC HOSTEL] Compilation successful!"
echo ""
echo "Choose an option:"
echo "  1. Run Interactive Application Menu"
echo "  2. Run Complete Automated Billing Pipeline (--auto)"
echo "  3. Run Automated Test Verification Suite (HostelEngineTest)"
echo "  4. Run BillingWorker Block A Standalone Test"
read -r -p "Enter choice [1-4] (Default: 1): " opt

case "$opt" in
    2)
        java -cp "bin:mysql-connector-j-8.4.0.jar" MainApp --auto
        ;;
    3)
        java -cp "bin:mysql-connector-j-8.4.0.jar" HostelEngineTest
        ;;
    4)
        java -cp "bin:mysql-connector-j-8.4.0.jar" BillingWorker
        ;;
    *)
        java -cp "bin:mysql-connector-j-8.4.0.jar" MainApp
        ;;
esac
