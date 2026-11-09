# KEC Autonomous Hostel Utility & Mess Fee Engine
## Distributed Multi-Tier OCI Deployment & Architecture Verification Runbook

---

### 1. Architectural Topology Overview

The **KEC Autonomous Hostel Utility & Mess Fee Engine** is engineered as a distributed, multi-tier enterprise system designed to run across multiple Oracle Cloud Infrastructure (OCI) Virtual Machines (Compute Instances):

```
+-----------------------------------------------------------+
|               STUDENT 1: APPLICATION HOST                 |
|               (OCI VM Instance 1: Compute)                |
|                                                           |
|  - Multi-threaded Billing Engine (ExecutorService)        |
|  - String Sanitization & Room ID Regex Validation         |
|  - Phase 3 Meter Reading Batch Ingestion (File I/O)       |
|  - ASCII Fee Statement Invoices (./bills/ Directory)      |
|  - JDBC Driver & Connection Pool (DBConnection.java)      |
+-----------------------------------------------------------+
                             |
                             | TCP Port 3306 (MySQL)
                             | Over OCI VCN / Internet Gateway
                             v
+-----------------------------------------------------------+
|               STUDENT 2: DATABASE HOST                    |
|               (OCI VM Instance 2: Compute)                |
|                                                           |
|  - MySQL 8.0 / PostgreSQL Daemon (0.0.0.0 listener)       |
|  - OCI Virtual Cloud Network (VCN) Ingress Rules          |
|  - OS Firewall (iptables / ufw) Allowing Port 3306        |
|  - Schema & Seed Data (kec_hostel_schema.sql)             |
|  - Relational Tables: residents, mess_logs,               |
|    utility_readings, monthly_bills                        |
+-----------------------------------------------------------+
```

---

### 2. Student 2 Runbook: Database Host Configuration

Student 2 is responsible for provisioning the database server, configuring network listener addresses, managing users and privileges, applying firewall permissions, and importing the SQL schema.

#### Step 2.1: Configure MySQL Listener to Bind to `0.0.0.0`
By default, MySQL binds only to `127.0.0.1` (localhost), which rejects all inbound external connections from Student 1.

1. Open the MySQL daemon configuration file:
   ```bash
   sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf
   # (On CentOS/RHEL/Oracle Linux, the path is /etc/my.cnf or /etc/my.cnf.d/mysql-server.cnf)
   ```
2. Locate the `bind-address` directive and update it:
   ```ini
   # Change from:
   # bind-address = 127.0.0.1
   # To:
   bind-address            = 0.0.0.0
   mysqlx-bind-address     = 0.0.0.0
   ```
3. Restart the MySQL daemon:
   ```bash
   sudo systemctl restart mysql
   # Or on Oracle Linux:
   sudo systemctl restart mysqld
   ```

#### Step 2.2: Create Remote Database User & Grant Privileges
Root or local users cannot connect remotely by default. Create a dedicated user with wildcard host `%`:

```bash
sudo mysql -u root -p
```
Inside the MySQL shell, execute:
```sql
-- Create the remote administrative user for KEC Engine
CREATE USER 'kec_admin'@'%' IDENTIFIED WITH mysql_native_password BY 'KecHostel@2026';

-- Grant full schema permissions
GRANT ALL PRIVILEGES ON kec_hostel_db.* TO 'kec_admin'@'%' WITH GRANT OPTION;

-- Reload privileges table
FLUSH PRIVILEGES;

-- Verify user creation
SELECT user, host, plugin FROM mysql.user WHERE user = 'kec_admin';
EXIT;
```

#### Step 2.3: Ingest Schema and Seed Data
Transfer `kec_hostel_schema.sql` to the DB VM and run:
```bash
mysql -u kec_admin -p -D kec_hostel_db < kec_hostel_schema.sql
# Enter password: KecHostel@2026
```

#### Step 2.4: Linux OS-Level Firewall Configuration (Crucial on OCI)
OCI Ubuntu and Oracle Linux images enforce aggressive OS-level firewall rules by default.

##### For Ubuntu Linux (`ufw` / `iptables`):
```bash
# Allow inbound TCP on port 3306
sudo ufw allow 3306/tcp
sudo ufw reload

# If ufw is inactive and iptables is directly managed:
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 3306 -j ACCEPT
sudo netfilter-persistent save
```

##### For Oracle Linux / RHEL (`firewalld`):
```bash
sudo firewall-cmd --zone=public --add-port=3306/tcp --permanent
sudo firewall-cmd --reload
sudo firewall-cmd --list-ports
```

---

### 3. OCI Console Security List Ingress Rule Configuration

Configuring the OS firewall is not enough; the **OCI Virtual Cloud Network (VCN)** packet filter will drop incoming packets unless an **Ingress Rule** is added.

#### Exact OCI Console Navigation Steps:
1. Log in to the [Oracle Cloud Infrastructure Console](https://cloud.oracle.com/).
2. Open the Navigation Menu (top-left burger icon) $\rightarrow$ **Networking** $\rightarrow$ **Virtual Cloud Networks**.
3. Select your Compartment and click the target **VCN** attached to Student 2's VM.
4. Under **Resources** (left sidebar), click **Security Lists**.
5. Click **Default Security List for `<your-vcn-name>`** (or the Security List attached to your subnet).
6. Click **Add Ingress Rules** and enter:
   - **Stateless:** `No` (Unchecked)
   - **Source Type:** `CIDR`
   - **Source CIDR:** `0.0.0.0/0` (or restrict to Student 1's public IP address e.g. `140.238.xxx.xxx/32`)
   - **IP Protocol:** `TCP`
   - **Source Port Range:** `All`
   - **Destination Port Range:** `3306` (or `5432` if using PostgreSQL)
   - **Description:** `KEC Hostel Engine - MySQL Student 1 Ingress Access`
7. Click **Add Ingress Rules**.

---

### 4. Student 1 Runbook: Application Host Setup & Execution

Student 1 runs the multi-threaded Java application and connects to Student 2's remote VM.

#### Step 4.1: Configure Connection Parameters
The Java application dynamically reads environment variables or JVM system properties.

##### Option A: Using Environment Variables (Recommended)
On Linux/macOS:
```bash
export KEC_DB_HOST="<STUDENT_2_OCI_PUBLIC_IP>"
export KEC_DB_PORT="3306"
export KEC_DB_NAME="kec_hostel_db"
export KEC_DB_USER="kec_admin"
export KEC_DB_PASSWORD="KecHostel@2026"
```
On Windows (PowerShell):
```powershell
$env:KEC_DB_HOST = "<STUDENT_2_OCI_PUBLIC_IP>"
$env:KEC_DB_PORT = "3306"
$env:KEC_DB_NAME = "kec_hostel_db"
$env:KEC_DB_USER = "kec_admin"
$env:KEC_DB_PASSWORD = "KecHostel@2026"
```

##### Option B: Using JVM System Properties
Pass flags directly during `java` startup:
```bash
java -Dkec.db.host="<STUDENT_2_OCI_PUBLIC_IP>" -cp ".:mysql-connector-j-8.4.0.jar" MainApp
```

#### Step 4.2: Compile and Run the Application
```bash
# Compile all source files
javac -cp ".:mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java

# On Windows:
# javac -cp ".;mysql-connector-j-8.4.0.jar" DBConnection.java HostelDAO.java BillingWorker.java FileExporter.java MainApp.java

# Execute interactive suite
java -cp ".:mysql-connector-j-8.4.0.jar" MainApp

# Or run complete automated pipeline non-interactively
java -cp ".:mysql-connector-j-8.4.0.jar" MainApp --auto
```

---

### 5. Linux Terminal Verification & Diagnostic Commands

Use these industry-standard diagnostic tools to verify connectivity at each layer of the OSI model:

#### 1. On Student 2 (Database Host): Verify Local Listening State
Ensure the MySQL daemon is actively bound to `0.0.0.0:3306` and not `127.0.0.1:3306`:
```bash
# netstat check
sudo netstat -tlpn | grep 3306
# Expected output:
# tcp   0   0 0.0.0.0:3306   0.0.0.0:*   LISTEN   1234/mysqld

# ss check (modern replacement for netstat)
sudo ss -tulpn | grep 3306
# Expected output:
# LISTEN   0   70   0.0.0.0:3306   0.0.0.0:*   users:(("mysqld",pid=1234,fd=31))
```

#### 2. On Student 1 (Application Host): Remote Port Reachability Checks

##### A. ICMP Layer (Ping):
```bash
ping -c 4 <STUDENT_2_OCI_PUBLIC_IP>
```
*(Note: OCI VCN security lists block ICMP echo requests by default unless an ICMP Ingress rule is enabled).*

##### B. Port Scanning with Nmap:
```bash
nmap -p 3306 <STUDENT_2_OCI_PUBLIC_IP>
# Expected output:
# PORT     STATE SERVICE
# 3306/tcp open  mysql
```
*If `STATE` is `filtered`, OCI Security List or OS firewall is dropping packets. If `closed`, MySQL service is down or bound only to localhost.*

##### C. Quick TCP Socket Check with Netcat (`nc`):
```bash
nc -zv -w 5 <STUDENT_2_OCI_PUBLIC_IP> 3306
# Expected output:
# Connection to <STUDENT_2_OCI_PUBLIC_IP> 3306 port [tcp/mysql] succeeded!
```

##### D. Direct MySQL CLI Client Handshake:
```bash
mysql -h <STUDENT_2_OCI_PUBLIC_IP> -P 3306 -u kec_admin -p -D kec_hostel_db
# Enter password: KecHostel@2026
```
Once connected, verify database integrity:
```sql
SELECT count(*) AS total_residents FROM residents;
SELECT block, count(*) AS residents_per_block FROM residents GROUP BY block;
```

---

### 6. Troubleshooting & Diagnostics Matrix

| Symptom / Error Message | Root Cause | Solution |
| :--- | :--- | :--- |
| **`Connection refused (errno 111)`** | MySQL daemon is not running OR `bind-address` is set to `127.0.0.1`. | Verify `systemctl status mysql`. Set `bind-address = 0.0.0.0` in `/etc/mysql/mysql.conf.d/mysqld.cnf` and restart MySQL. |
| **`Connection timed out`** | OCI VCN Ingress Rule missing OR host OS firewall (`ufw`/`iptables`) blocking port 3306. | 1. Add TCP port 3306 Ingress rule in OCI Console.<br>2. Run `sudo iptables -I INPUT 6 -p tcp --dport 3306 -j ACCEPT` on DB host. |
| **`Access denied for user 'kec_admin'@'<IP>'`** | User account was created with `localhost` instead of `%` host wildcard, or password mismatch. | Run `DROP USER 'kec_admin'@'%';` and re-create with `CREATE USER 'kec_admin'@'%' IDENTIFIED BY 'KecHostel@2026';`. |
| **`Public Key Retrieval is not allowed`** | MySQL 8.0 `caching_sha2_password` default plugin prevents plain transmission. | The JDBC URL in `DBConnection.java` has `allowPublicKeyRetrieval=true&useSSL=false` enabled. Alternatively, recreate user with `mysql_native_password`. |
| **`Host is blocked because of many connection errors`** | Too many unauthenticated requests hit MySQL. | Run `mysqladmin -u root -p flush-hosts` on the Database host. |

---

### 7. Resilient Offline Testing Mode
If Student 1 is running tests locally without an active internet connection or before Student 2 has booted the OCI VM, `DBConnection.java` and `HostelDAO.java` automatically detect unreachable DB sockets and activate **In-Memory Fallback Persistence**. 
This guarantees that:
- Room ID Regex validation (`^[A-Z]{1}-\d{3}$`) functions 100%.
- String sanitization (`.trim()`, title-casing) operates normally.
- Multi-threaded block billing (`ExecutorService`) executes across blocks A, B, and C.
- ASCII statements are generated directly into `./bills/` directory without fatal runtime crashes.

