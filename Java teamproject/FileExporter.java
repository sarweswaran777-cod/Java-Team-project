import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FileExporter - Generates professional ASCII invoice text files into ./bills/ directory.
 * 
 * Complies with strict technical requirements:
 * - Currency output formatted to standard format (₹#,##0.00).
 * - Target directory ./bills/ created automatically.
 * - File naming convention: BILL_<RoomID>_<YYYY>_<MM>.txt (e.g., BILL_A-101_2026_11.txt).
 */
public class FileExporter {

    private static final Logger LOGGER = Logger.getLogger(FileExporter.class.getName());
    public static final String BILLS_DIRECTORY = "./bills";

    // Standard Currency Formatter (₹#,##0.00) using unicode symbol \u20B9
    private static final DecimalFormat CURRENCY_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("en-IN"));
        symbols.setCurrencySymbol("\u20B9");
        CURRENCY_FORMAT = new DecimalFormat("\u20B9#,##0.00", symbols);
    }

    /**
     * Formats a double value to ₹#,##0.00 currency string.
     */
    public static String formatCurrency(double amount) {
        synchronized (CURRENCY_FORMAT) {
            return CURRENCY_FORMAT.format(amount);
        }
    }

    /**
     * Exports a single monthly bill to ./bills/BILL_<RoomID>_<YYYY>_<MM>.txt
     *
     * @param bill The calculated MonthlyBill object containing resident details
     * @return File object of the written bill, or null if writing failed
     */
    public static File exportInvoice(HostelDAO.MonthlyBill bill) {
        if (bill == null || bill.getResident() == null) {
            LOGGER.warning("[FileExporter] Cannot export null bill or bill with missing resident.");
            return null;
        }

        HostelDAO.Resident resident = bill.getResident();
        String roomId = resident.getRoomId();
        String safeMonth = bill.getBillingMonth().replace("-", "_");
        String filename = String.format("BILL_%s_%s.txt", roomId, safeMonth);

        File dir = new File(BILLS_DIRECTORY);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                LOGGER.info("[FileExporter] Created directory: " + dir.getAbsolutePath());
            }
        }

        File targetFile = new File(dir, filename);

        try (PrintWriter writer = new PrintWriter(new FileWriter(targetFile, StandardCharsets.UTF_8))) {
            writer.println(buildInvoiceText(bill));
            writer.flush();
            return targetFile;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[FileExporter] Failed to export invoice to " + targetFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Builds the clean ASCII formatted invoice document.
     */
    public static String buildInvoiceText(HostelDAO.MonthlyBill bill) {
        HostelDAO.Resident r = bill.getResident();
        HostelDAO.AccommodationCategory cat = r.getCategory();

        StringBuilder sb = new StringBuilder();
        String divider = "+==============================================================================+";
        String subDiv  = "+------------------------------------------------------------------------------+";

        sb.append(divider).append(System.lineSeparator());
        sb.append("|            KONGU ENGINEERING COLLEGE (AUTONOMOUS) - PERUNDURAI               |").append(System.lineSeparator());
        sb.append("|                     HOSTEL UTILITY & MESS FEE STATEMENT                      |").append(System.lineSeparator());
        sb.append(String.format("|                         BILLING CYCLE: %-10s                            |%n", bill.getBillingMonth()));
        sb.append(divider).append(System.lineSeparator());

        sb.append(String.format("|  Student Name    : %-30s Student ID : %-16s |%n", r.getName(), r.getStudentId()));
        sb.append(String.format("|  Room ID         : %-30s Hostel Block: Block %-12s |%n", r.getRoomId(), r.getBlock()));
        sb.append(String.format("|  Category        : %-30s Status     : %-16s |%n", cat.getDisplayName(), r.getStatus()));
        sb.append(String.format("|  Base Monthly Fee: %-30s Bill Ref   : #KEC-B%04d           |%n", 
            formatCurrency(r.getBaseFee()), bill.getBillId()));
        sb.append(subDiv).append(System.lineSeparator());

        sb.append("|  FEE COMPONENT BREAKDOWN & WEIGHT ALLOCATION                                 |").append(System.lineSeparator());
        sb.append(subDiv).append(System.lineSeparator());

        // 1. Base Mess Fee
        sb.append(String.format("|  1. Base Mess Fee (Weight: %2.0f%%)                               %18s |%n",
            cat.getBaseMessWeight() * 100, formatCurrency(bill.getBaseMessFee())));

        // 2. Mess Rebate (if applicable)
        if (bill.getMessRebate() > 0.00) {
            sb.append(String.format("|     [-] Mess Attendance Rebate (Attended < 15 days -> 30%%) %18s |%n",
                ("-" + formatCurrency(bill.getMessRebate()))));
            sb.append(String.format("|     [=] Net Mess Payable                                         %18s |%n",
                formatCurrency(bill.getBaseMessFee() - bill.getMessRebate())));
        } else {
            sb.append("|     [+] Mess Attendance: Regular (>= 15 Days, No Rebate applied)             |").append(System.lineSeparator());
        }

        // 3. Special Meals & Coupons
        sb.append(String.format("|  2. Special Meals & Guest Coupons (Phase 2 Mid-Month)            %18s |%n",
            formatCurrency(bill.getSpecialMealsTotal())));

        // 4. Electricity Cost Breakdown
        double baseCap = cat.getElectricityBaseCap();
        double units = bill.getElectricityUnits();
        double extraUnits = Math.max(0.0, units - baseCap);
        double extraRate = cat.getExtraUnitRate();
        double baseElec = r.getBaseFee() * cat.getElectricityWeight();
        double extraSurcharge = extraUnits * extraRate;

        sb.append(String.format("|  3. Electricity & Utility Assessment                              %18s |%n",
            formatCurrency(bill.getElectricityCost())));
        sb.append(String.format("|     - Meter Units Consumed : %-6.1f Units (Base Cap: %-5.1f Units)               |%n",
            units, baseCap));
        sb.append(String.format("|     - Base Electricity (Weight: %2.0f%%) : %-15s                          |%n",
            cat.getElectricityWeight() * 100, formatCurrency(baseElec)));

        if (extraUnits > 0.0) {
            sb.append(String.format("|     - Surcharge (%-4.1f Extra Units @ %s/unit) : %-15s               |%n",
                extraUnits, formatCurrency(extraRate), formatCurrency(extraSurcharge)));
        } else {
            sb.append("|     - Surcharge: None (Consumption within allocated base cap)                |").append(System.lineSeparator());
        }

        sb.append(subDiv).append(System.lineSeparator());
        sb.append(String.format("|  SUB-TOTAL (Net Bill Before Penalty)                             %18s |%n",
            formatCurrency(bill.getNetBeforePenalty())));

        // 5. Payment Standing & Penalty
        String standingStr = bill.getPaymentStanding().name();
        if (bill.getPaymentStanding() == HostelDAO.PaymentStanding.DEFAULTER) {
            sb.append(String.format("|  PAYMENT STANDING: [%-9s] (Unpaid after 20th - Late Fine 10%%)              |%n", standingStr));
            sb.append(String.format("|  [+] Late Defaulter Fine (10%% applied to Net Bill)               %18s |%n",
                formatCurrency(bill.getPenaltyAmount())));
        } else if (bill.getPaymentStanding() == HostelDAO.PaymentStanding.CLEARED) {
            sb.append(String.format("|  PAYMENT STANDING: [%-9s] (Settled on or before 15th - Penalty: %s)      |%n", 
                standingStr, formatCurrency(0.0)));
        } else {
            sb.append(String.format("|  PAYMENT STANDING: [%-9s] (Due window: 16th to 20th - Penalty: %s)       |%n", 
                standingStr, formatCurrency(0.0)));
        }

        sb.append(divider).append(System.lineSeparator());
        sb.append(String.format("|  TOTAL INVOICE AMOUNT PAYABLE                                    %18s |%n",
            formatCurrency(bill.getTotalBill())));
        sb.append(divider).append(System.lineSeparator());

        sb.append("|  PAYMENT & COMPLIANCE NOTES:                                                 |").append(System.lineSeparator());
        sb.append("|  1. Invoices paid on or before 15th receive 'CLEARED' standing.              |").append(System.lineSeparator());
        sb.append("|  2. Grace window is 16th to 20th ('DUE'). Defaulters after 20th incur 10%.   |").append(System.lineSeparator());
        sb.append("|  3. Special AC units exceeding 100 units billed at fixed rate of Rs 8.00.    |").append(System.lineSeparator());
        sb.append("|  4. Generated via KEC Multi-Threaded Engine (OCI VM Distributed Architecture)|").append(System.lineSeparator());
        String stamp = (bill.getGeneratedAt() != null) 
            ? bill.getGeneratedAt().toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            : "N/A";
        sb.append(String.format("|  System Generated Timestamp: %-47s |%n", stamp));
        sb.append(divider).append(System.lineSeparator());

        return sb.toString();
    }

    /**
     * Standalone demonstration main method to test invoice generation directly.
     */
    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {}

        System.out.println("Testing FileExporter ASCII invoice generation...");
        HostelDAO.Resident r = new HostelDAO.Resident(
            101, "22CS101", "Arun Kumar S", "A", "A-101",
            HostelDAO.AccommodationCategory.VEG_MESS, 5500.00
        );

        HostelDAO.MonthlyBill b = new HostelDAO.MonthlyBill();
        b.setBillId(1);
        b.setResidentId(101);
        b.setResident(r);
        b.setBillingMonth("2026-11");
        b.setBaseMessFee(2750.00);
        b.setMessRebate(0.00);
        b.setSpecialMealsTotal(500.00);
        b.setElectricityUnits(65.5);
        b.setElectricityCost(1193.00);
        b.setNetBeforePenalty(4443.00);
        b.setPenaltyAmount(0.00);
        b.setTotalBill(4443.00);
        b.setPaymentStanding(HostelDAO.PaymentStanding.DUE);

        File exported = exportInvoice(b);
        if (exported != null) {
            System.out.println("[SUCCESS] Invoice exported to: " + exported.getAbsolutePath());
            System.out.println("\nSample Generated Invoice Output:\n");
            System.out.println(buildInvoiceText(b));
        } else {
            System.out.println("[ERROR] Failed to export invoice.");
        }
    }
}
