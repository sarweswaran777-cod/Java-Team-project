import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;

/**
 * MainApp - Command-line Interactive Interface and Multi-Threaded Billing Orchestrator.
 * 
 * Demonstrates:
 * 1. String processing & Room ID Regex validation (^[A-Z]{1}-\d{3}$).
 * 2. 3-Phase Data Lifecycle:
 *    - Phase 1: Room Allocation & Base Fee Profile
 *    - Phase 2: Mid-Month Special Meals & Guest Coupons
 *    - Phase 3: File I/O batch ingestion of meter_readings.txt
 * 3. Concurrent Multi-threaded batch billing using ExecutorService & BillingWorker.
 * 4. ASCII invoice statement generation in ./bills/ directory.
 * 5. Production summary statistics dashboard.
 */
public class MainApp {

    private static final String DEFAULT_READINGS_FILE = "meter_readings.txt";
    private static final String DEFAULT_BILLING_MONTH = "2026-11";
    private static final HostelDAO dao = new HostelDAO();

    public static void main(String[] args) {
        // Configure standard out & err for UTF-8 to support native Rupee symbol (₹)
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {}

        printBanner();
        DBConnection.printConfiguration();

        Scanner scanner = new Scanner(System.in);
        boolean exit = false;

        // If args contain "--auto" or if run non-interactively, run the complete pipeline
        if (args.length > 0 && "--auto".equalsIgnoreCase(args[0])) {
            runCompletePipeline();
            return;
        }

        while (!exit) {
            printMainMenu();
            System.out.print("Select an option [1-9]: ");
            String choice = scanner.nextLine().trim();
            System.out.println();

            switch (choice) {
                case "1":
                    runCompletePipeline();
                    break;
                case "2":
                    testRegexAndStringSanitization(scanner);
                    break;
                case "3":
                    handlePhase1NewAllocation(scanner);
                    break;
                case "4":
                    handlePhase2SpecialMeals(scanner);
                    break;
                case "5":
                    handlePhase3FileParsing(scanner);
                    break;
                case "6":
                    executeMultiThreadedBilling();
                    break;
                case "7":
                    displayMonthlyBillsSummary();
                    break;
                case "8":
                    testDbConnectivity();
                    break;
                case "9":
                    exit = true;
                    System.out.println("Exiting KEC Autonomous Hostel Utility & Mess Fee Engine. Goodbye!");
                    break;
                default:
                    System.out.println("[!] Invalid option. Please select a number between 1 and 9.");
            }
            System.out.println();
        }
    }

    private static void printBanner() {
        System.out.println("================================================================================");
        System.out.println("         KONGU ENGINEERING COLLEGE (AUTONOMOUS) - PERUNDURAI, ERODE             ");
        System.out.println("             HOSTEL UTILITY & MESS FEE COMPUTATION ENGINE (v2.6)               ");
        System.out.println("                  Distributed Architecture - OCI VM Deployment                  ");
        System.out.println("================================================================================");
    }

    private static void printMainMenu() {
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("                             MAIN OPERATIONS MENU                               ");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  1. Run Complete End-to-End Pipeline (Phase 1 -> 2 -> 3 File Parse -> Billing)");
        System.out.println("  2. Test Room ID Regex Validation & Name Sanitization Showcase");
        System.out.println("  3. Phase 1: Allocate New Room / Resident (Interactive Input)");
        System.out.println("  4. Phase 2: Record Special Meals & Guest Coupon Accumulation");
        System.out.println("  5. Phase 3: Ingest Meter Readings & Attendance from File (meter_readings.txt)");
        System.out.println("  6. Execute Multi-Threaded Batch Billing (ExecutorService across Blocks)");
        System.out.println("  7. Display Billing Summary Dashboard & View Generated Invoices");
        System.out.println("  8. Test Remote OCI Database Connectivity (Student 2 Host)");
        System.out.println("  9. Exit Application");
        System.out.println("--------------------------------------------------------------------------------");
    }

    /**
     * Executes the complete end-to-end billing workflow automatically.
     */
    public static void runCompletePipeline() {
        System.out.println(">>> STEP 1: VERIFYING PHASE 1 RESIDENTS DATA...");
        List<HostelDAO.Resident> residents = dao.getAllResidents();
        System.out.printf("    Found %d registered residents across blocks %s.%n", 
            residents.size(), dao.getAllBlocks());

        System.out.println("\n>>> STEP 2: INGESTING PHASE 3 METER READINGS & ATTENDANCE FROM FILE...");
        parseAndIngestMeterReadings(DEFAULT_READINGS_FILE, DEFAULT_BILLING_MONTH);

        System.out.println("\n>>> STEP 3: EXECUTING CONCURRENT MULTI-THREADED BILLING...");
        executeMultiThreadedBilling();

        System.out.println("\n>>> STEP 4: GENERATING COMPREHENSIVE CONSOLE AUDIT SUMMARY...");
        displayMonthlyBillsSummary();
    }

    /**
     * Demonstrates strict Room ID Regex validation and Student Name Sanitization.
     */
    private static void testRegexAndStringSanitization(Scanner scanner) {
        System.out.println("================================================================================");
        System.out.println("       ROOM ID REGEX VALIDATION & NAME SANITIZATION TEST BENCH                 ");
        System.out.println("       Regex Pattern: ^[A-Z]{1}-\\d{3}$ (e.g. A-101, B-205)                     ");
        System.out.println("================================================================================");

        // Predefined benchmark test cases
        String[] sampleRooms = {
            "A-101", "B-205", "C-012", "D-999",     // Valid
            "AA-101", "a-101", "A-12", "A-1002",    // Invalid
            "1-101", "Room-101", "B-20A", "", null  // Invalid
        };

        System.out.println("Automated Test Suite for Room ID Format:");
        for (String test : sampleRooms) {
            boolean valid = test != null && HostelDAO.ROOM_ID_PATTERN.matcher(test.trim()).matches();
            System.out.printf("  Room ID: %-12s -> %s%n", 
                (test == null ? "null" : "\"" + test + "\""), 
                (valid ? "[VALID MATCH]" : "[REJECTED / INVALID FORMAT]"));
        }

        System.out.println("\nAutomated Test Suite for Student Name Sanitization (.trim() + Capitalization):");
        String[] sampleNames = {
            "   sarvesh   kumar   ",
            "karthick raja b",
            "  ANTONY   DOSS  M  "
        };
        for (String raw : sampleNames) {
            String cleaned = HostelDAO.Resident.sanitizeName(raw);
            System.out.printf("  Raw: \"%-25s\" -> Sanitized: \"%s\"%n", raw, cleaned);
        }

        System.out.print("\nWould you like to test a custom Room ID? (Enter ID or press Enter to skip): ");
        String custom = scanner.nextLine().trim();
        if (!custom.isEmpty()) {
            Matcher m = HostelDAO.ROOM_ID_PATTERN.matcher(custom);
            if (m.matches()) {
                System.out.printf("[SUCCESS] '%s' is a VALID Room ID in Block %s.%n", custom, custom.substring(0, 1));
            } else {
                System.out.printf("[FAILURE] '%s' is INVALID. Must match '^[A-Z]{1}-\\d{3}$' (e.g. A-101).%n", custom);
            }
        }
    }

    /**
     * Phase 1: Allocate a new resident room with interactive validation.
     */
    private static void handlePhase1NewAllocation(Scanner scanner) {
        System.out.println("================================================================================");
        System.out.println("       PHASE 1: RESIDENT ROOM ALLOCATION & BASE FEE REGISTRATION               ");
        System.out.println("================================================================================");

        try {
            System.out.print("Enter Student Roll ID (e.g. 22CS150): ");
            String rollId = scanner.nextLine().trim();
            if (rollId.isEmpty()) rollId = "22CS" + (100 + new Random().nextInt(800));

            System.out.print("Enter Student Full Name: ");
            String rawName = scanner.nextLine();
            String sanitizedName = HostelDAO.Resident.sanitizeName(rawName);
            if (sanitizedName.isEmpty()) sanitizedName = "Student Candidate";

            String roomId;
            while (true) {
                System.out.print("Enter Room ID (Must match '^[A-Z]{1}-\\d{3}$' e.g. A-301): ");
                roomId = scanner.nextLine().trim();
                if (HostelDAO.ROOM_ID_PATTERN.matcher(roomId).matches()) {
                    break;
                }
                System.out.println("[!] Invalid format. Must be 1 capital letter, a hyphen, and 3 digits.");
            }

            System.out.println("Select Accommodation Category:");
            System.out.println("  1. VEG MESS        (Mess: 50%, Diet: 30%, Elec: 20%, Base Cap: 50 units)");
            System.out.println("  2. NON-VEG MESS    (Mess: 45%, Diet: 35%, Elec: 20%, Base Cap: 50 units)");
            System.out.println("  3. SPECIAL AC ROOM (Mess: 35%, Diet: 25%, Elec: 40%, Base Cap: 100 units, Rs 8/extra)");
            System.out.print("Choice [1-3] (Default: 1): ");
            String catChoice = scanner.nextLine().trim();
            HostelDAO.AccommodationCategory cat = HostelDAO.AccommodationCategory.VEG_MESS;
            double defaultFee = 5500.00;
            if ("2".equals(catChoice)) {
                cat = HostelDAO.AccommodationCategory.NON_VEG_MESS;
                defaultFee = 6500.00;
            } else if ("3".equals(catChoice)) {
                cat = HostelDAO.AccommodationCategory.SPECIAL_AC_ROOM;
                defaultFee = 9500.00;
            }

            System.out.printf("Enter Base Monthly Fee (Default: %s): ", FileExporter.formatCurrency(defaultFee));
            String feeInput = scanner.nextLine().trim();
            double baseFee = feeInput.isEmpty() ? defaultFee : Double.parseDouble(feeInput);

            String block = roomId.substring(0, 1);
            HostelDAO.Resident resident = new HostelDAO.Resident(
                0, rollId, sanitizedName, block, roomId, cat, baseFee
            );

            boolean ok = dao.insertResident(resident);
            if (ok) {
                System.out.printf("[SUCCESS] Resident registered successfully! Assigned ID: %d%n", resident.getResidentId());
                System.out.println("          " + resident);
            } else {
                System.out.println("[ERROR] Failed to insert resident. Room or Student ID may already exist.");
            }

        } catch (Exception e) {
            System.out.println("[ERROR] Allocation failed: " + e.getMessage());
        }
    }

    /**
     * Phase 2: Record special meal charges and guest coupon accumulation.
     */
    private static void handlePhase2SpecialMeals(Scanner scanner) {
        System.out.println("================================================================================");
        System.out.println("       PHASE 2: SPECIAL DIET PURCHASES & GUEST COUPON ACCUMULATION             ");
        System.out.println("================================================================================");

        System.out.print("Enter Room ID to log charges (e.g. A-101): ");
        String roomId = scanner.nextLine().trim();
        HostelDAO.Resident r = dao.getResidentByRoomId(roomId);
        if (r == null) {
            System.out.printf("[!] No resident found for Room ID '%s'.%n", roomId);
            return;
        }

        System.out.printf("Resident: %s (%s) | Category: %s%n", r.getName(), r.getStudentId(), r.getCategory().getDisplayName());

        System.out.print("Enter Billing Month (YYYY-MM, Default: 2026-11): ");
        String month = scanner.nextLine().trim();
        if (month.isEmpty()) month = DEFAULT_BILLING_MONTH;

        HostelDAO.MessLog existing = dao.getMessLog(r.getResidentId(), month);

        System.out.printf("Enter Special Meals Charge (Current: %s): ", FileExporter.formatCurrency(existing.getSpecialMealsCharge()));
        String spInput = scanner.nextLine().trim();
        double spCharge = spInput.isEmpty() ? existing.getSpecialMealsCharge() : Double.parseDouble(spInput);

        System.out.printf("Enter Guest Coupons Charge (Current: %s): ", FileExporter.formatCurrency(existing.getGuestCouponsCharge()));
        String gcInput = scanner.nextLine().trim();
        double gcCharge = gcInput.isEmpty() ? existing.getGuestCouponsCharge() : Double.parseDouble(gcInput);

        existing.setSpecialMealsCharge(spCharge);
        existing.setGuestCouponsCharge(gcCharge);

        boolean ok = dao.saveOrUpdateMessLog(existing);
        if (ok) {
            System.out.printf("[SUCCESS] Phase 2 Mess Charges updated for %s! Total Special Meals: %s%n",
                r.getRoomId(), FileExporter.formatCurrency(existing.getTotalSpecialMeals()));
        } else {
            System.out.println("[ERROR] Could not save mess charges.");
        }
    }

    /**
     * Phase 3: Parses external text file meter_readings.txt (Format: RoomID,UnitsConsumed,AttendanceDays).
     */
    private static void handlePhase3FileParsing(Scanner scanner) {
        System.out.print("Enter meter readings file path (Default: meter_readings.txt): ");
        String path = scanner.nextLine().trim();
        if (path.isEmpty()) path = DEFAULT_READINGS_FILE;

        System.out.print("Enter Billing Month (Default: 2026-11): ");
        String month = scanner.nextLine().trim();
        if (month.isEmpty()) month = DEFAULT_BILLING_MONTH;

        parseAndIngestMeterReadings(path, month);
    }

    public static void parseAndIngestMeterReadings(String filePath, String billingMonth) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.printf("[ERROR] Meter readings file '%s' not found! Please check file path.%n", filePath);
            return;
        }

        System.out.printf(">>> Parsing external meter file: %s for Cycle: %s%n", file.getAbsolutePath(), billingMonth);

        int totalLines = 0;
        int successfulRecords = 0;
        int skippedLines = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                String trimmed = line.trim();

                // Skip comments and blank lines
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    skippedLines++;
                    continue;
                }

                // Expected format: RoomID,UnitsConsumed,AttendanceDays
                String[] tokens = trimmed.split(",");
                if (tokens.length < 3) {
                    System.out.printf("    [WARN Line %d] Incomplete data: '%s' (Expected 3 comma-separated fields)%n", totalLines, trimmed);
                    continue;
                }

                String rawRoom = tokens[0].trim();
                String rawUnits = tokens[1].trim();
                String rawDays = tokens[2].trim();

                // 1. Strict Regex Validation on Room ID
                if (!HostelDAO.ROOM_ID_PATTERN.matcher(rawRoom).matches()) {
                    System.out.printf("    [WARN Line %d] Room ID '%s' violates pattern '^[A-Z]{1}-\\d{3}$'. Skipping.%n", totalLines, rawRoom);
                    continue;
                }

                // 2. Parse numbers
                double units;
                int attendanceDays;
                try {
                    units = Double.parseDouble(rawUnits);
                    attendanceDays = Integer.parseInt(rawDays);
                    if (units < 0 || attendanceDays < 0 || attendanceDays > 31) {
                        System.out.printf("    [WARN Line %d] Numerical out of range (Units: %.1f, Days: %d). Skipping.%n", totalLines, units, attendanceDays);
                        continue;
                    }
                } catch (NumberFormatException nfe) {
                    System.out.printf("    [WARN Line %d] Invalid number format in '%s'. Skipping.%n", totalLines, trimmed);
                    continue;
                }

                // 3. Match resident
                HostelDAO.Resident resident = dao.getResidentByRoomId(rawRoom);
                if (resident == null) {
                    System.out.printf("    [WARN Line %d] No registered resident found for Room ID '%s'. Skipping.%n", totalLines, rawRoom);
                    continue;
                }

                // 4. Update utility readings
                HostelDAO.UtilityReading util = new HostelDAO.UtilityReading(
                    resident.getResidentId(), rawRoom, billingMonth, units, LocalDate.of(2026, 11, 28)
                );
                dao.saveOrUpdateUtilityReading(util);

                // 5. Update mess attendance
                HostelDAO.MessLog mess = dao.getMessLog(resident.getResidentId(), billingMonth);
                mess.setMessDaysAttended(attendanceDays);
                dao.saveOrUpdateMessLog(mess);

                successfulRecords++;
            }

            System.out.printf("[SUCCESS] Ingestion Complete: %d valid records updated, %d comments/blanks skipped.%n",
                successfulRecords, skippedLines);

        } catch (IOException e) {
            System.out.printf("[ERROR] Failed to read meter file: %s%n", e.getMessage());
        }
    }

    /**
     * Executes multi-threaded billing across blocks using Java ExecutorService.
     */
    public static void executeMultiThreadedBilling() {
        Set<String> blocks = dao.getAllBlocks();
        if (blocks.isEmpty()) {
            System.out.println("[!] No hostel blocks or residents found to compute.");
            return;
        }

        System.out.println("================================================================================");
        System.out.printf(" EXECUTING CONCURRENT MULTI-THREADED BILLING FOR %d BLOCKS (%s)%n", 
            blocks.size(), blocks);
        System.out.println("================================================================================");

        int poolSize = Math.max(2, Math.min(blocks.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        List<Callable<BillingWorker.WorkerResult>> tasks = new ArrayList<>();
        for (String block : blocks) {
            List<HostelDAO.Resident> blockResidents = dao.getResidentsByBlock(block);
            if (!blockResidents.isEmpty()) {
                tasks.add(new BillingWorker("Block " + block, blockResidents, DEFAULT_BILLING_MONTH, dao));
            }
        }

        long overallStart = System.currentTimeMillis();
        List<BillingWorker.WorkerResult> results = new ArrayList<>();

        try {
            List<Future<BillingWorker.WorkerResult>> futures = executor.invokeAll(tasks);
            for (Future<BillingWorker.WorkerResult> f : futures) {
                results.add(f.get());
            }
        } catch (InterruptedException | ExecutionException e) {
            System.out.println("[ERROR] Multi-threaded processing interrupted: " + e.getMessage());
        } finally {
            executor.shutdown();
        }

        long totalDuration = System.currentTimeMillis() - overallStart;

        // Print Worker Thread Execution Breakdown
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("                       CONCURRENT WORKER EXECUTION LOGS                         ");
        System.out.println("--------------------------------------------------------------------------------");
        for (BillingWorker.WorkerResult res : results) {
            System.out.println("  " + res);
        }
        System.out.printf("%n[COMPLETED] Multi-threaded Batch Execution finished in %d ms across %d threads.%n",
            totalDuration, poolSize);
    }

    /**
     * Displays a clean console table of generated bills and audit statistics.
     */
    public static void displayMonthlyBillsSummary() {
        List<HostelDAO.MonthlyBill> bills = dao.getAllBillsForMonth(DEFAULT_BILLING_MONTH);
        if (bills.isEmpty()) {
            System.out.println("[!] No bills calculated yet for " + DEFAULT_BILLING_MONTH + ". Run billing first.");
            return;
        }

        System.out.println("========================================================================================================================");
        System.out.printf("                      KEC HOSTEL MONTHLY FEE STATEMENT REPORT - %s                                         %n", DEFAULT_BILLING_MONTH);
        System.out.println("========================================================================================================================");
        System.out.printf("%-7s | %-16s | %-15s | %-11s | %-10s | %-10s | %-9s | %-11s | %-10s | %-11s%n",
            "Room", "Student Name", "Category", "Base Mess", "MR Rebate", "Spl Meals", "Units", "Elec Cost", "Standing", "Final Total");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        double totalRevenue = 0.0;
        double totalRebates = 0.0;
        double totalPenalties = 0.0;
        double totalElectricity = 0.0;
        int clearedCount = 0;
        int dueCount = 0;
        int defaulterCount = 0;

        for (HostelDAO.MonthlyBill b : bills) {
            HostelDAO.Resident r = b.getResident();
            totalRevenue += b.getTotalBill();
            totalRebates += b.getMessRebate();
            totalPenalties += b.getPenaltyAmount();
            totalElectricity += b.getElectricityUnits();

            if (b.getPaymentStanding() == HostelDAO.PaymentStanding.CLEARED) clearedCount++;
            else if (b.getPaymentStanding() == HostelDAO.PaymentStanding.DUE) dueCount++;
            else defaulterCount++;

            System.out.printf("%-7s | %-16s | %-15s | %11s | %10s | %10s | %9.1f | %11s | %-10s | %11s%n",
                r.getRoomId(),
                truncate(r.getName(), 16),
                truncate(r.getCategory().getDisplayName(), 15),
                FileExporter.formatCurrency(b.getBaseMessFee()),
                b.getMessRebate() > 0 ? ("-" + FileExporter.formatCurrency(b.getMessRebate())) : FileExporter.formatCurrency(0.0),
                FileExporter.formatCurrency(b.getSpecialMealsTotal()),
                b.getElectricityUnits(),
                FileExporter.formatCurrency(b.getElectricityCost()),
                b.getPaymentStanding(),
                FileExporter.formatCurrency(b.getTotalBill())
            );
        }

        System.out.println("========================================================================================================================");
        System.out.println("                                      EXECUTIVE SUMMARY & KPIS                                                          ");
        System.out.println("========================================================================================================================");
        System.out.printf("  Total Invoices Generated : %-10d | Total Electricity Consumed: %.1f Units%n", bills.size(), totalElectricity);
        System.out.printf("  Total Gross Fee Assessed : %-10s | Average Units Per Resident: %.1f Units%n", 
            FileExporter.formatCurrency(totalRevenue), (totalElectricity / bills.size()));
        System.out.printf("  Total MR Rebates Granted : %-10s | Total Late Fines Assessed : %s%n", 
            FileExporter.formatCurrency(totalRebates), FileExporter.formatCurrency(totalPenalties));
        System.out.printf("  Payment Standings        : %d CLEARED  |  %d DUE  |  %d DEFAULTERS (10%% fine applied)%n", 
            clearedCount, dueCount, defaulterCount);
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("  Individual text invoices exported to directory: %s%n", 
            new File(FileExporter.BILLS_DIRECTORY).getAbsolutePath());
        System.out.println("========================================================================================================================");
    }

    private static void testDbConnectivity() {
        System.out.println("Testing remote database connection...");
        boolean connected = DBConnection.testConnection();
        if (connected) {
            System.out.printf("[SUCCESS] Connected to OCI Host %s:%d/%s! Remote tables accessible.%n",
                DBConnection.getHost(), DBConnection.getPort(), DBConnection.getDbName());
        } else {
            System.out.printf("[NOTICE] Remote DB at %s:%d is not currently reachable.%n",
                DBConnection.getHost(), DBConnection.getPort());
            System.out.println("         High-fidelity in-memory persistence is active to permit full local testing.");
            System.out.println("         Refer to 'Architecture_Verification_Doc.md' for Student 2 setup steps.");
        }
    }

    private static String truncate(String val, int maxLen) {
        if (val == null) return "";
        if (val.length() <= maxLen) return val;
        return val.substring(0, maxLen - 1) + ".";
    }
}

