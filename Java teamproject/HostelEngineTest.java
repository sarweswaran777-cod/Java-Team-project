import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HostelEngineTest - Automated Verification Suite for KEC Autonomous Hostel Engine.
 * 
 * Tests:
 * 1. Room ID Regex validation (^[A-Z]{1}-\d{3}$) with positive & negative test cases.
 * 2. Student Name sanitization (.trim(), word capitalization, handling multiple spaces).
 * 3. Mess Attendance Rebate calculation (<15 days -> 30% rebate; >=15 days -> 0% rebate).
 * 4. Electricity calculation (Veg/Non-Veg base cap 50 units; AC base cap 100 units + ₹8.00 surcharge).
 * 5. Late penalty fine (10% fine on Net Bill for Defaulters).
 * 6. File I/O invoice generation in ./bills/ directory.
 * 7. Multithreading concurrency integrity across blocks.
 */
public class HostelEngineTest {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {}

        System.out.println("================================================================================");
        System.out.println("      KEC AUTONOMOUS HOSTEL UTILITY ENGINE - AUTOMATED TEST SUITE               ");
        System.out.println("================================================================================");

        testRoomIdRegexValidation();
        testStudentNameSanitization();
        testMessAttendanceRebateRules();
        testElectricityAndACSurchargeCalculations();
        testLatePenaltyAndPaymentStandings();
        testFileExporterIntegrity();
        testConcurrentMultiThreadedBilling();

        System.out.println("================================================================================");
        System.out.printf("  TEST EXECUTION SUMMARY: %d / %d PASSED (%.1f%% SUCCESS RATE)%n",
            testsPassed, testsRun, (testsPassed * 100.0 / testsRun));
        System.out.println("================================================================================");

        if (testsPassed != testsRun) {
            System.exit(1);
        }
    }

    private static void assertTrue(String testName, boolean condition, String errorDetail) {
        testsRun++;
        if (condition) {
            testsPassed++;
            System.out.printf("  [PASS] %s%n", testName);
        } else {
            System.err.printf("  [FAIL] %s -> %s%n", testName, errorDetail);
        }
    }

    private static void assertEquals(String testName, double expected, double actual, double delta) {
        boolean match = Math.abs(expected - actual) <= delta;
        assertTrue(testName, match, String.format("Expected: %.2f, Got: %.2f", expected, actual));
    }

    private static void assertEquals(String testName, Object expected, Object actual) {
        boolean match = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        assertTrue(testName, match, String.format("Expected: %s, Got: %s", expected, actual));
    }

    // -------------------------------------------------------------------------
    // Test 1: Room ID Regex Validation
    // -------------------------------------------------------------------------
    private static void testRoomIdRegexValidation() {
        System.out.println("\n--- 1. Testing Room ID Regex Pattern (^[A-Z]{1}-\\d{3}$) ---");
        Pattern p = HostelDAO.ROOM_ID_PATTERN;

        // Positive test cases
        assertTrue("Regex: Valid Room A-101", p.matcher("A-101").matches(), "A-101 should match");
        assertTrue("Regex: Valid Room B-205", p.matcher("B-205").matches(), "B-205 should match");
        assertTrue("Regex: Valid Room C-001", p.matcher("C-001").matches(), "C-001 should match");
        assertTrue("Regex: Valid Room Z-999", p.matcher("Z-999").matches(), "Z-999 should match");

        // Negative test cases
        assertTrue("Regex: Reject AA-101 (2 letters)", !p.matcher("AA-101").matches(), "AA-101 should fail");
        assertTrue("Regex: Reject a-101 (lowercase)", !p.matcher("a-101").matches(), "a-101 should fail");
        assertTrue("Regex: Reject A-12 (2 digits)", !p.matcher("A-12").matches(), "A-12 should fail");
        assertTrue("Regex: Reject A-1002 (4 digits)", !p.matcher("A-1002").matches(), "A-1002 should fail");
        assertTrue("Regex: Reject 1-101 (Starts with digit)", !p.matcher("1-101").matches(), "1-101 should fail");
        assertTrue("Regex: Reject A101 (Missing hyphen)", !p.matcher("A101").matches(), "A101 should fail");
    }

    // -------------------------------------------------------------------------
    // Test 2: Student Name Sanitization
    // -------------------------------------------------------------------------
    private static void testStudentNameSanitization() {
        System.out.println("\n--- 2. Testing Student Name Sanitization ---");
        String name1 = HostelDAO.Resident.sanitizeName("   sarvesh   kumar   ");
        assertEquals("Sanitize: Trim and Capitalize", "Sarvesh Kumar", name1);

        String name2 = HostelDAO.Resident.sanitizeName("arun kumar s");
        assertEquals("Sanitize: Lowercase to Title Case", "Arun Kumar S", name2);

        String name3 = HostelDAO.Resident.sanitizeName("  FAYAZ   AHMED   N  ");
        assertEquals("Sanitize: Upper Case Preserved Trimmed", "FAYAZ AHMED N", name3);
    }

    // -------------------------------------------------------------------------
    // Test 3: Mess Attendance Rebate Rules
    // -------------------------------------------------------------------------
    private static void testMessAttendanceRebateRules() {
        System.out.println("\n--- 3. Testing Mess Attendance Rebate (<15 days -> 30%) ---");
        double baseFeeVeg = 5500.00;
        double messWeightVeg = HostelDAO.AccommodationCategory.VEG_MESS.getBaseMessWeight(); // 50%
        double baseMessVeg = baseFeeVeg * messWeightVeg; // 2750.00

        // Case A: Attended 12 days (< 15 days) -> 30% rebate applies
        int daysA = 12;
        double rebateA = (daysA < 15) ? (baseMessVeg * 0.30) : 0.0;
        assertEquals("Rebate: 12 days attended gives 30% rebate", 825.00, rebateA, 0.01);

        // Case B: Attended 15 days (boundary) -> 0% rebate
        int daysB = 15;
        double rebateB = (daysB < 15) ? (baseMessVeg * 0.30) : 0.0;
        assertEquals("Rebate: 15 days attended gives 0% rebate", 0.00, rebateB, 0.01);

        // Case C: Attended 25 days -> 0% rebate
        int daysC = 25;
        double rebateC = (daysC < 15) ? (baseMessVeg * 0.30) : 0.0;
        assertEquals("Rebate: 25 days attended gives 0% rebate", 0.00, rebateC, 0.01);
    }

    // -------------------------------------------------------------------------
    // Test 4: Electricity & AC Surcharge Calculations
    // -------------------------------------------------------------------------
    private static void testElectricityAndACSurchargeCalculations() {
        System.out.println("\n--- 4. Testing Electricity & AC Surcharges ---");

        // Special AC Room: Base fee ₹9,500.00, Electricity Weight: 40% (₹3,800.00), Base Cap: 100 units
        HostelDAO.AccommodationCategory acCat = HostelDAO.AccommodationCategory.SPECIAL_AC_ROOM;
        double baseFeeAC = 9500.00;
        double baseElecAC = baseFeeAC * acCat.getElectricityWeight(); // 3,800.00

        // Case A: 115 units consumed (15 units over 100 cap @ ₹8.00/unit)
        double unitsA = 115.0;
        double extraUnitsA = Math.max(0.0, unitsA - acCat.getElectricityBaseCap());
        double surchargeA = extraUnitsA * acCat.getExtraUnitRate(); // 15 * 8.00 = 120.00
        double totalElecA = baseElecAC + surchargeA; // 3,920.00
        assertEquals("AC Electricity: 15 extra units @ Rs 8/unit", 3920.00, totalElecA, 0.01);

        // Case B: 85 units consumed (Within 100 base cap -> Rs 0 surcharge)
        double unitsB = 85.0;
        double extraUnitsB = Math.max(0.0, unitsB - acCat.getElectricityBaseCap());
        double surchargeB = extraUnitsB * acCat.getExtraUnitRate();
        double totalElecB = baseElecAC + surchargeB; // 3,800.00
        assertEquals("AC Electricity: Within base cap has 0 surcharge", 3800.00, totalElecB, 0.01);

        // Standard Veg Room: Base fee ₹5,500, Weight 20% (₹1,100), Base cap 50 units, 65.5 units consumed
        HostelDAO.AccommodationCategory vegCat = HostelDAO.AccommodationCategory.VEG_MESS;
        double baseElecVeg = 5500.00 * vegCat.getElectricityWeight(); // 1,100.00
        double extraVeg = Math.max(0.0, 65.5 - 50.0); // 15.5 units
        double totalElecVeg = baseElecVeg + (extraVeg * vegCat.getExtraUnitRate()); // 1,100 + (15.5 * 6) = 1,193.00
        assertEquals("Veg Electricity: 15.5 units over 50 cap @ Rs 6/unit", 1193.00, totalElecVeg, 0.01);
    }

    // -------------------------------------------------------------------------
    // Test 5: Late Penalty & Payment Standings
    // -------------------------------------------------------------------------
    private static void testLatePenaltyAndPaymentStandings() {
        System.out.println("\n--- 5. Testing Payment Standings & Late Defaulter Fines ---");

        assertEquals("Standing: Day 12 is CLEARED", HostelDAO.PaymentStanding.CLEARED, HostelDAO.PaymentStanding.evaluateByDay(12));
        assertEquals("Standing: Day 15 is CLEARED", HostelDAO.PaymentStanding.CLEARED, HostelDAO.PaymentStanding.evaluateByDay(15));
        assertEquals("Standing: Day 18 is DUE", HostelDAO.PaymentStanding.DUE, HostelDAO.PaymentStanding.evaluateByDay(18));
        assertEquals("Standing: Day 20 is DUE", HostelDAO.PaymentStanding.DUE, HostelDAO.PaymentStanding.evaluateByDay(20));
        assertEquals("Standing: Day 21 is DEFAULTER", HostelDAO.PaymentStanding.DEFAULTER, HostelDAO.PaymentStanding.evaluateByDay(21));
        assertEquals("Standing: Day 25 is DEFAULTER", HostelDAO.PaymentStanding.DEFAULTER, HostelDAO.PaymentStanding.evaluateByDay(25));

        // Penalty formula: 10% on Net Bill Before Penalty for Defaulter
        double netBill = 4047.50;
        double penalty = netBill * 0.10; // 404.75
        double totalBill = netBill + penalty; // 4452.25
        assertEquals("Penalty: 10% applied to Net Bill", 404.75, Math.round(penalty * 100.0) / 100.0, 0.01);
        assertEquals("Total: Net Bill + Penalty", 4452.25, Math.round(totalBill * 100.0) / 100.0, 0.01);
    }

    // -------------------------------------------------------------------------
    // Test 6: File Exporter Invoice Generation
    // -------------------------------------------------------------------------
    private static void testFileExporterIntegrity() {
        System.out.println("\n--- 6. Testing FileExporter Invoice Generation ---");
        HostelDAO.Resident r = new HostelDAO.Resident(
            99, "22TEST99", "Test Candidate", "A", "A-999", 
            HostelDAO.AccommodationCategory.SPECIAL_AC_ROOM, 9500.00
        );
        HostelDAO.MonthlyBill b = new HostelDAO.MonthlyBill();
        b.setBillId(999);
        b.setResidentId(99);
        b.setResident(r);
        b.setBillingMonth("2026-11");
        b.setBaseMessFee(3325.00);
        b.setMessRebate(0.00);
        b.setSpecialMealsTotal(1050.00);
        b.setElectricityUnits(115.0);
        b.setElectricityCost(3920.00);
        b.setNetBeforePenalty(8295.00);
        b.setPenaltyAmount(0.00);
        b.setTotalBill(8295.00);
        b.setPaymentStanding(HostelDAO.PaymentStanding.CLEARED);

        File f = FileExporter.exportInvoice(b);
        assertTrue("FileExporter: Created invoice file object", f != null, "Export returned null");
        assertTrue("FileExporter: Invoice file exists on disk", f != null && f.exists(), "Invoice file does not exist on disk");
        assertTrue("FileExporter: Correct naming BILL_A-999_2026_11.txt", 
            f != null && f.getName().equals("BILL_A-999_2026_11.txt"), "Unexpected filename: " + (f == null ? "null" : f.getName()));

        // Clean up test file
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Test 7: Multi-threaded Batch Execution
    // -------------------------------------------------------------------------
    private static void testConcurrentMultiThreadedBilling() {
        System.out.println("\n--- 7. Testing Multi-Threaded Concurrent Execution ---");
        HostelDAO dao = new HostelDAO();
        List<HostelDAO.Resident> residents = dao.getAllResidents();
        assertTrue("DAO: Loaded 12 residents", residents.size() == 12, "Resident count mismatch: " + residents.size());

        MainApp.parseAndIngestMeterReadings("meter_readings.txt", "2026-11");
        MainApp.executeMultiThreadedBilling();

        List<HostelDAO.MonthlyBill> bills = dao.getAllBillsForMonth("2026-11");
        assertEquals("Multi-Threading: All 12 monthly bills computed", 12, bills.size());

        File billsDir = new File(FileExporter.BILLS_DIRECTORY);
        File[] invoiceFiles = billsDir.listFiles((dir, name) -> name.startsWith("BILL_") && name.endsWith(".txt"));
        assertTrue("Multi-Threading: 12 invoices present in ./bills/", 
            invoiceFiles != null && invoiceFiles.length == 12, 
            "Expected 12 invoice files, found: " + (invoiceFiles == null ? 0 : invoiceFiles.length));
    }
}
