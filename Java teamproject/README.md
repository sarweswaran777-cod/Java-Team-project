# KEC Autonomous Hostel Utility & Mess Fee Engine

> **Distributed Architecture Engineering Solution for Oracle Cloud Infrastructure (OCI)**  
> **Course:** Java Enterprise Architecture & Distributed Systems (Team Project)  
> **Institution:** Kongu Engineering College (Autonomous), Perundurai, Erode  

---

## 1. Project Overview & Multi-Tier Architecture

The **KEC Autonomous Hostel Utility & Mess Fee Engine** is an end-to-end, multi-threaded billing and utility accounting system. It is specifically designed to run on a distributed, two-tier architecture across two separate cloud hosts (Oracle Cloud Infrastructure Compute instances):

- **Student 1 (App Host):**
  - Runs the core Java 11+ application.
  - Implements multi-threaded batch billing through `ExecutorService` and `BillingWorker`.
  - Performs string sanitization and Room ID Regex validation (`^[A-Z]{1}-\d{3}$`).
  - Ingests Phase 3 meter readings from external file `meter_readings.txt`.
  - Exports itemized ASCII fee statements into `./bills/` directory.
- **Student 2 (DB Host):**
  - Runs a dedicated MySQL 8.0 / PostgreSQL database server.
  - Configured with `bind-address = 0.0.0.0` for remote external listener access.
  - Protected by OCI Security List rules and host-level firewall (`iptables` / `ufw`).
  - Holds tables for residents, mess logs, utility readings, and monthly bills.

```
                    DISTRIBUTED ARCHITECTURE TOPOLOGY
                    
  [ Student 1: App Host (OCI VM 1) ]             [ Student 2: DB Host (OCI VM 2) ]
  +--------------------------------+             +-------------------------------+
  | - Java 11+ Multi-Thread Engine |             | - MySQL 8.0 Daemon            |
  | - ExecutorService Thread Pool  |             | - Bind: 0.0.0.0:3306          |
  | - File I/O (meter_readings.txt)|   TCP:3306  | - OCI Security List Ingress   |
  | - ASCII Invoices (./bills/)    | ----------->| - Schema: kec_hostel_db       |
  | - DBConnection (ConnectionPool)|   JDBC      | - ACID Transactions (InnoDB)  |
  +--------------------------------+             +-------------------------------+
```

---

## 2. Directory Structure

```text
c:\Java teamproject\
├── Architecture_Verification_Doc.md   # Deployment runbook for Student 1 & 2 on OCI
├── BillingWorker.java                 # Multi-threaded Callable worker per hostel block
├── DBConnection.java                  # JDBC utility with dynamic env/system fallback
├── FileExporter.java                  # ASCII invoice generator with standard currency (₹)
├── HostelDAO.java                     # Data Access Object, CRUD, and ACID transaction models
├── HostelEngineTest.java              # Automated regression test suite (33/33 tests pass)
├── MainApp.java                       # Interactive CLI & automated billing orchestrator
├── build_and_run.bat                  # One-click build and execution script for Windows
├── build_and_run.sh                   # One-click build and execution script for Linux/macOS
├── kec_hostel_schema.sql              # Database DDL script with seed data across blocks
├── meter_readings.txt                 # Phase 3 sample meter and mess attendance data
├── mysql-connector-j-8.4.0.jar       # MySQL official JDBC driver
└── bills/                             # Output directory containing generated invoice files
    ├── BILL_A-101_2026_11.txt
    ├── BILL_A-102_2026_11.txt
    ├── BILL_A-201_2026_11.txt
    └── ... (12 files generated across Blocks A, B, and C)
```

---

## 3. Accommodation Categories & Rate Structures

| Category | Base Mess Weight | Special Diet Weight | Electricity Weight | Base Cap | Excess Unit Surcharge |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **VEG MESS** | 50% | 30% | 20% | 50 Units | ₹6.00 / extra unit |
| **NON-VEG MESS** | 45% | 35% | 20% | 50 Units | ₹6.00 / extra unit |
| **SPECIAL AC ROOM** | 35% | 25% | 40% | 100 Units | **₹8.00 / extra unit** |

---

## 4. Business Rules & Mathematical Formulas

1. **Mess Attendance Rebate (MR):**
   $$\text{If } \text{mess\_days\_attended} < 15 \implies \text{Rebate} = \text{BaseMess} \times 0.30$$
   $$\text{Else } \text{Rebate} = ₹0.00$$

2. **Electricity Cost:**
   $$\text{BaseElectricity} = \text{BaseFee} \times \text{ElectricityWeight}$$
   $$\text{ExcessUnits} = \max(0, \text{UnitsConsumed} - \text{BaseCap})$$
   $$\text{ElectricityCost} = \text{BaseElectricity} + (\text{ExcessUnits} \times \text{ExtraUnitRate})$$

3. **Sub-Total (Net Bill Before Penalty):**
   $$\text{NetBillBeforePenalty} = (\text{BaseMess} - \text{Rebate}) + \text{SpecialMeals} + \text{ElectricityCost}$$

4. **Payment Standing & Late Fine Penalty:**
   - **`CLEARED`** (Paid on or before 15th): $\text{Penalty} = ₹0.00$
   - **`DUE`** (Unpaid between 16th and 20th): $\text{Penalty} = ₹0.00$
   - **`DEFAULTER`** (Unpaid after 20th): $\text{Penalty} = \text{NetBillBeforePenalty} \times 0.10$

5. **Final Total Bill:**
   $$\text{TotalBill} = \text{NetBillBeforePenalty} + \text{Penalty}$$

---

## 5. Quick Start & Execution Guide

### Option A: Using One-Click Automation Scripts

- **On Windows:**
  Double-click [`build_and_run.bat`](file:///c:/Java%20teamproject/build_and_run.bat) or execute from PowerShell:
  ```powershell
  .\build_and_run.bat
  ```

- **On Linux / OCI Host:**
  ```bash
  chmod +x build_and_run.sh
  ./build_and_run.sh
  ```

### Option B: Compiling & Running Manually

1. **Compile all Java source files:**
   ```bash
   javac -cp ".;mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java HostelEngineTest.java
   ```

2. **Run the Complete Automated Billing Pipeline:**
   ```bash
   java -cp ".;mysql-connector-j-8.4.0.jar" MainApp --auto
   ```

3. **Run the Interactive Menu System:**
   ```bash
   java -cp ".;mysql-connector-j-8.4.0.jar" MainApp
   ```

4. **Run the 33-point Automated Test Suite:**
   ```bash
   java -cp ".;mysql-connector-j-8.4.0.jar" HostelEngineTest
   ```

5. **Test Standalone Database Connectivity:**
   ```bash
   java -cp ".;mysql-connector-j-8.4.0.jar" DBConnection
   ```

---

## 6. Remote Database Configuration for Student 2 (OCI)

To connect Student 1's application host to Student 2's remote database, configure the connection settings using environment variables:

```powershell
# Windows PowerShell:
$env:KEC_DB_HOST = "<STUDENT_2_OCI_PUBLIC_IP>"
$env:KEC_DB_PORT = "3306"
$env:KEC_DB_NAME = "kec_hostel_db"
$env:KEC_DB_USER = "kec_admin"
$env:KEC_DB_PASSWORD = "KecHostel@2026"
```

```bash
# Linux/macOS Bash:
export KEC_DB_HOST="<STUDENT_2_OCI_PUBLIC_IP>"
export KEC_DB_PORT="3306"
export KEC_DB_NAME="kec_hostel_db"
export KEC_DB_USER="kec_admin"
export KEC_DB_PASSWORD="KecHostel@2026"
```

Refer to [`Architecture_Verification_Doc.md`](file:///c:/Java%20teamproject/Architecture_Verification_Doc.md) for full step-by-step instructions on setting up MySQL user permissions, OCI Security List Ingress rules, and host firewall configurations.

