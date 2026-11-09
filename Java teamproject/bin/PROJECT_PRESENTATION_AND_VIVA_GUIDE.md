# KEC Autonomous Hostel Utility & Mess Fee Engine
## Comprehensive Oral Defense, Project Presentation & Viva Guide

---

### 1. Project Synopsis & Architectural Roles

| Role | Student | Responsibilities | Technologies |
| :--- | :--- | :--- | :--- |
| **Student 1 (App Host)** | Application Engineer | Multi-threaded billing engine (`ExecutorService`), Room ID Regex validation, String sanitization, Batch file I/O, ASCII invoice generation. | Java 11+, Concurrency API, NIO.2, JDBC API |
| **Student 2 (DB Host)** | Database Architect | OCI VM instance provisioning, MySQL 8.0 / PostgreSQL daemon setup, `0.0.0.0` binding, OCI VCN Ingress rules, Schema DDL, ACID transactions. | MySQL 8.0 / PostgreSQL, Linux (Ubuntu/Oracle Linux), OCI VCN, iptables |

---

### 2. High-Frequency Technical Viva Questions & Senior Engineer Answers

#### Q1: Why did you choose a multi-threaded architecture (`ExecutorService`) instead of sequential processing?
**Answer:**
> In an institution like Kongu Engineering College with thousands of hostel residents across multiple blocks (Block A, Block B, Block C, etc.), sequential billing calculation creates an $O(N)$ execution bottleneck and poor CPU core utilization. By grouping residents by hostel blocks and assigning each block to a dedicated `BillingWorker` thread managed by a fixed thread pool (`Executors.newFixedThreadPool(poolSize)`), processing occurs concurrently across all available CPU cores. This reduces total batch calculation time from linear to near-constant bounded by the largest block size ($\sim O(N/K)$ where $K$ is thread count), with benchmark throughput under 80 ms for batch billing across multiple blocks.

#### Q2: How is data consistency and ACID transactional integrity guaranteed in `HostelDAO.java`?
**Answer:**
> Billing persistence requires strict atomicity. In [`HostelDAO.java`](file:///c:/Java%20teamproject/HostelDAO.java), auto-commit mode is disabled before starting the update:
> ```java
> conn.setAutoCommit(false); // Begin ACID Transaction
> ```
> If any calculation error, database connection glitch, or constraint violation occurs during the execution of `PreparedStatement`, `conn.rollback()` is invoked in the catch block to revert all intermediate writes. Only when all updates succeed is `conn.commit()` executed. Finally, the connection is reset to `setAutoCommit(true)` and closed in a `finally` block to prevent connection leakages in the connection pool.

#### Q3: Why is configuring `bind-address = 0.0.0.0` in `mysqld.cnf` alone not enough for remote OCI VM connectivity?
**Answer:**
> In cloud architectures like Oracle Cloud Infrastructure (OCI), network traffic must traverse three distinct defensive layers before reaching the database daemon:
> 1. **Daemon Listener Layer:** `bind-address = 0.0.0.0` tells MySQL to listen on all network interfaces instead of loopback `127.0.0.1`.
> 2. **OS Host Firewall Layer:** Linux kernel packet filtering (`ufw` on Ubuntu or `iptables`/`firewalld` on Oracle Linux) will reject inbound packets on TCP port 3306 unless explicitly permitted (`iptables -I INPUT ... -p tcp --dport 3306 -j ACCEPT`).
> 3. **OCI Cloud Virtual Network Layer:** The Virtual Cloud Network (VCN) subnet enforces a stateful security list. Inbound TCP traffic destined for port 3306 is dropped at the hypervisor level unless an explicit Ingress Security Rule is configured for the source CIDR.

#### Q4: How is Room ID format strictly enforced across the application?
**Answer:**
> In accordance with the technical specifications, Room ID validation employs the regular expression:
> `^[A-Z]{1}-\d{3}$`
> - `^[A-Z]{1}`: Exactly one uppercase alphabetical block character (A, B, C, etc.).
> - `-`: Literal hyphen delimiter.
> - `\d{3}$`: Exactly three numerical digits representing the room number (e.g., 101, 205, 302).
> 
> This validation occurs at two layers for defense-in-depth:
> 1. In Java: `HostelDAO.ROOM_ID_PATTERN.matcher(roomId).matches()` throws `IllegalArgumentException` on invalid formats.
> 2. In SQL: `CONSTRAINT chk_room_format CHECK (room_id REGEXP '^[A-Z]{1}-[0-9]{3}$')` enforces table-level integrity.

#### Q5: Explain the business formulas for Rebate, Electricity Surcharge, and Late Penalty.
**Answer:**
> 1. **Mess Attendance Rebate (MR):**
>    $$\text{If } \text{mess\_days\_attended} < 15 \implies \text{Rebate} = \text{BaseMess} \times 0.30$$
> 2. **Electricity Cost:**
>    - Standard Veg/Non-Veg: Base Cap 50 units. Excess units billed at standard rate ₹6.00/unit.
>    - Special AC Room: Base Cap 100 units. Units above 100 incurred at ₹8.00/extra unit.
>    $$\text{ElectricityCost} = (\text{BaseFee} \times \text{ElectricityWeight}) + (\max(0, \text{Units} - \text{BaseCap}) \times \text{ExtraRate})$$
> 3. **Late Defaulter Penalty:**
>    - Paid $\le 15^{\text{th}}$: `CLEARED` (Fine = ₹0.00).
>    - Unpaid $16^{\text{th}} - 20^{\text{th}}$: `DUE` (Fine = ₹0.00).
>    - Unpaid after $20^{\text{th}}$: `DEFAULTER` (Fine = $\text{NetBillBeforePenalty} \times 0.10$).
>    $$\text{TotalBill} = (\text{BaseMess} - \text{Rebate}) + \text{SpecialMeals} + \text{ElectricityCost} + \text{Penalty}$$

---

### 3. Step-by-Step Viva Live Demonstration Runbook

When presenting to evaluators, execute these steps in order:

```bash
# Step 1: Show project compilation with zero warnings
javac -Xlint:all -cp ".;mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java HostelEngineTest.java

# Step 2: Run the 33-point Automated Regression Test Suite
java -cp ".;mysql-connector-j-8.4.0.jar" HostelEngineTest

# Step 3: Run Database Connectivity Diagnostics
java -cp ".;mysql-connector-j-8.4.0.jar" DBConnection

# Step 4: Run the Complete End-to-End Batch Billing Engine
java -cp ".;mysql-connector-j-8.4.0.jar" MainApp --auto

# Step 5: Inspect generated ASCII Invoices in ./bills/
dir bills\
type bills\BILL_A-201_2026_11.txt
```

