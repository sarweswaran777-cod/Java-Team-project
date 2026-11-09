import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BillingWorker - Concurrently processes and computes fee statements for a specific hostel block.
 * 
 * Implements both java.util.concurrent.Callable and java.lang.Runnable for maximum flexibility
 * when executing within an ExecutorService thread pool or raw Thread instances.
 * 
 * Performs end-to-end calculations according to KEC Autonomous business rules:
 * - Base Mess fee weight per accommodation category (50%, 45%, 35%)
 * - Mess Attendance Rebate (30% rebate on BaseMess if days attended < 15)
 * - Electricity Base Cap & AC excess surcharge (₹8.00 / extra unit above 100 units for AC rooms)
 * - Payment Standing penalty (10% fine on net bill if DEFAULTER)
 * - Transactional database persistence & ASCII invoice generation into ./bills/
 */
public class BillingWorker implements Callable<BillingWorker.WorkerResult>, Runnable {

    private static final Logger LOGGER = Logger.getLogger(BillingWorker.class.getName());

    private final String blockName;
    private final List<HostelDAO.Resident> residents;
    private final String billingMonth;
    private final HostelDAO dao;
    private final int simulatedAssessmentDay; // Day of month for payment standing (<= 0 triggers mixed distribution)

    private volatile WorkerResult lastResult;

    /**
     * Encapsulates the results and metrics returned by a BillingWorker.
     */
    public static class WorkerResult {
        private final String blockName;
        private final String threadName;
        private final int totalResidentsProcessed;
        private final int rebateCount;
        private final int defaulterCount;
        private final double totalGrossBilled;
        private final double totalRebatesGranted;
        private final double totalPenaltiesAssessed;
        private final long executionDurationMs;
        private final List<HostelDAO.MonthlyBill> generatedBills;
        private final List<File> exportedFiles;

        public WorkerResult(String blockName, String threadName, int totalResidentsProcessed,
                            int rebateCount, int defaulterCount, double totalGrossBilled,
                            double totalRebatesGranted, double totalPenaltiesAssessed,
                            long executionDurationMs, List<HostelDAO.MonthlyBill> generatedBills,
                            List<File> exportedFiles) {
            this.blockName = blockName;
            this.threadName = threadName;
            this.totalResidentsProcessed = totalResidentsProcessed;
            this.rebateCount = rebateCount;
            this.defaulterCount = defaulterCount;
            this.totalGrossBilled = totalGrossBilled;
            this.totalRebatesGranted = totalRebatesGranted;
            this.totalPenaltiesAssessed = totalPenaltiesAssessed;
            this.executionDurationMs = executionDurationMs;
            this.generatedBills = (generatedBills != null) ? generatedBills : Collections.emptyList();
            this.exportedFiles = (exportedFiles != null) ? exportedFiles : Collections.emptyList();
        }

        public String getBlockName() { return blockName; }
        public String getThreadName() { return threadName; }
        public int getTotalResidentsProcessed() { return totalResidentsProcessed; }
        public int getRebateCount() { return rebateCount; }
        public int getDefaulterCount() { return defaulterCount; }
        public double getTotalGrossBilled() { return totalGrossBilled; }
        public double getTotalRebatesGranted() { return totalRebatesGranted; }
        public double getTotalPenaltiesAssessed() { return totalPenaltiesAssessed; }
        public long getExecutionDurationMs() { return executionDurationMs; }
        public List<HostelDAO.MonthlyBill> getGeneratedBills() { return generatedBills; }
        public List<File> getExportedFiles() { return exportedFiles; }

        @Override
        public String toString() {
            return String.format(
                "[%s | %s] Processed: %d | Revenue: %s | Rebates: %s (%d) | Penalties: %s (%d) | Time: %dms",
                blockName, threadName, totalResidentsProcessed,
                FileExporter.formatCurrency(totalGrossBilled),
                FileExporter.formatCurrency(totalRebatesGranted), rebateCount,
                FileExporter.formatCurrency(totalPenaltiesAssessed), defaulterCount,
                executionDurationMs
            );
        }
    }

    public BillingWorker(String blockName, List<HostelDAO.Resident> residents, 
                         String billingMonth, HostelDAO dao, int simulatedAssessmentDay) {
        this.blockName = (blockName == null || blockName.trim().isEmpty()) ? "Unknown Block" : blockName.trim();
        this.residents = (residents == null) ? new ArrayList<>() : new ArrayList<>(residents);
        this.billingMonth = (billingMonth == null || billingMonth.trim().isEmpty()) ? "2026-11" : billingMonth.trim();
        this.dao = (dao == null) ? new HostelDAO() : dao;
        this.simulatedAssessmentDay = simulatedAssessmentDay;
    }

    public BillingWorker(String blockName, List<HostelDAO.Resident> residents, 
                         String billingMonth, HostelDAO dao) {
        this(blockName, residents, billingMonth, dao, 0); // 0 indicates realistic mixed simulation
    }

    public BillingWorker(String blockName, List<HostelDAO.Resident> residents) {
        this(blockName, residents, "2026-11", new HostelDAO(), 0);
    }

    /**
     * Runnable execution implementation delegating to call().
     */
    @Override
    public void run() {
        this.lastResult = call();
    }

    public WorkerResult getLastResult() {
        return lastResult;
    }

    public String getBlockName() { return blockName; }
    public List<HostelDAO.Resident> getResidents() { return Collections.unmodifiableList(residents); }
    public String getBillingMonth() { return billingMonth; }

    @Override
    public WorkerResult call() {
        long startTime = System.currentTimeMillis();
        String currentThreadName = Thread.currentThread().getName();

        LOGGER.info(() -> String.format(
            "[%s] Started computing Block %s (%d residents, Cycle: %s)...",
            currentThreadName, blockName, residents.size(), billingMonth
        ));

        int processed = 0;
        int rebateCount = 0;
        int defaulterCount = 0;
        double sumTotalBill = 0.0;
        double sumRebates = 0.0;
        double sumPenalties = 0.0;

        List<HostelDAO.MonthlyBill> bills = new ArrayList<>();
        List<File> files = new ArrayList<>();

        // Parse billing year and month safely
        int billingYear = 2026;
        int billingMonthNum = 11;
        if (billingMonth != null && billingMonth.contains("-")) {
            String[] parts = billingMonth.split("-");
            try {
                billingYear = Integer.parseInt(parts[0].trim());
                billingMonthNum = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {}
        }
        YearMonth ym = YearMonth.of(billingYear, Math.max(1, Math.min(12, billingMonthNum)));
        int daysInMonth = ym.lengthOfMonth();

        for (HostelDAO.Resident resident : residents) {
            try {
                // 1. Fetch Phase 2 Mess Log & Phase 3 Utility Reading
                HostelDAO.MessLog messLog = dao.getMessLog(resident.getResidentId(), billingMonth);
                HostelDAO.UtilityReading utilReading = dao.getUtilityReading(resident.getResidentId(), billingMonth);

                // 2. Extract Category Rules and Weights
                HostelDAO.AccommodationCategory cat = resident.getCategory();
                double baseFee = resident.getBaseFee();

                // 3. Compute Base Mess Fee
                // Veg: 50%, Non-Veg: 45%, Special AC: 35%
                double baseMessFee = baseFee * cat.getBaseMessWeight();

                // 4. Compute Mess Attendance Rebate (MR):
                // If mess_days_attended < 15, apply 30% rebate on BaseMess fee.
                double messRebate = 0.0;
                int daysAttended = messLog.getMessDaysAttended();
                if (daysAttended < 15) {
                    messRebate = baseMessFee * 0.30;
                    rebateCount++;
                    sumRebates += messRebate;
                }

                // 5. Special Meals & Guest Coupons (Phase 2 charges)
                double specialMealsTotal = messLog.getTotalSpecialMeals();

                // 6. Electricity Calculation:
                // Base electricity weight: Veg (20%), Non-Veg (20%), Special AC (40%)
                // Base Cap: Veg (50 units), Non-Veg (50 units), Special AC (100 units)
                // Surcharge: Special AC room units above 100 charged at ₹8.00 per extra unit
                double unitsConsumed = utilReading.getUnitsConsumed();
                double baseCap = cat.getElectricityBaseCap();
                double baseElectricity = baseFee * cat.getElectricityWeight();
                double excessUnits = Math.max(0.0, unitsConsumed - baseCap);
                double excessSurcharge = excessUnits * cat.getExtraUnitRate();
                double totalElectricityCost = baseElectricity + excessSurcharge;

                // 7. Net Bill Before Penalty
                // NetBill = (BaseMess - Rebate) + SpecialMeals + ElectricityCost
                double netBeforePenalty = (baseMessFee - messRebate) + specialMealsTotal + totalElectricityCost;

                // 8. Payment Standing & Penalty Evaluation
                // Use explicit simulatedAssessmentDay if provided (> 0); otherwise use realistic modulo distribution:
                // resident_id % 3 == 0 -> Day 10 (CLEARED)
                // resident_id % 3 == 1 -> Day 18 (DUE)
                // resident_id % 3 == 2 -> Day 24 (DEFAULTER)
                int simDay;
                if (simulatedAssessmentDay > 0) {
                    simDay = simulatedAssessmentDay;
                } else {
                    simDay = (resident.getResidentId() % 3 == 0) ? 10 : (resident.getResidentId() % 3 == 1 ? 18 : 24);
                }

                HostelDAO.PaymentStanding standing = HostelDAO.PaymentStanding.evaluateByDay(simDay);

                double penalty = 0.0;
                LocalDate payDate = null;

                int clampedDay = Math.min(simDay, daysInMonth);

                if (standing == HostelDAO.PaymentStanding.CLEARED) {
                    penalty = 0.0;
                    payDate = LocalDate.of(billingYear, billingMonthNum, clampedDay);
                } else if (standing == HostelDAO.PaymentStanding.DUE) {
                    penalty = 0.0;
                } else if (standing == HostelDAO.PaymentStanding.DEFAULTER) {
                    // Defaulter: Unpaid after the 20th -> 10% late penalty fine applied to net bill before penalty.
                    penalty = netBeforePenalty * 0.10;
                    defaulterCount++;
                    sumPenalties += penalty;
                }

                // Formula: TotalBill = (BaseMess - Rebate) + SpecialMeals + ElectricityCost + Penalty
                double totalBill = netBeforePenalty + penalty;

                // 9. Build MonthlyBill model
                HostelDAO.MonthlyBill bill = new HostelDAO.MonthlyBill();
                bill.setResidentId(resident.getResidentId());
                bill.setResident(resident);
                bill.setBillingMonth(billingMonth);
                bill.setBaseMessFee(roundToTwoDecimals(baseMessFee));
                bill.setMessRebate(roundToTwoDecimals(messRebate));
                bill.setSpecialMealsTotal(roundToTwoDecimals(specialMealsTotal));
                bill.setElectricityUnits(unitsConsumed);
                bill.setElectricityCost(roundToTwoDecimals(totalElectricityCost));
                bill.setNetBeforePenalty(roundToTwoDecimals(netBeforePenalty));
                bill.setPenaltyAmount(roundToTwoDecimals(penalty));
                bill.setTotalBill(roundToTwoDecimals(totalBill));
                bill.setPaymentStanding(standing);
                bill.setPaymentDate(payDate);

                // 10. Persist into Database with Transaction Management
                dao.saveMonthlyBill(bill);

                // 11. Trigger File Export to ./bills/BILL_<RoomID>_<YYYY>_<MM>.txt
                File exported = FileExporter.exportInvoice(bill);
                if (exported != null) {
                    files.add(exported);
                }

                bills.add(bill);
                processed++;
                sumTotalBill += totalBill;

            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, String.format(
                    "[%s] Error processing billing for Room %s (%s): %s",
                    currentThreadName, resident.getRoomId(), resident.getName(), ex.getMessage()
                ), ex);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info(() -> String.format(
            "[%s] Completed Block %s in %dms (%d invoices exported).",
            currentThreadName, blockName, duration, files.size()
        ));

        WorkerResult result = new WorkerResult(
            blockName,
            currentThreadName,
            processed,
            rebateCount,
            defaulterCount,
            sumTotalBill,
            sumRebates,
            sumPenalties,
            duration,
            bills,
            files
        );
        this.lastResult = result;
        return result;
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Standalone main method demonstrating direct execution of a single worker.
     */
    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {}

        System.out.println("Executing BillingWorker standalone test for Block A...");
        HostelDAO testDao = new HostelDAO();
        List<HostelDAO.Resident> blockA = testDao.getResidentsByBlock("A");

        BillingWorker worker = new BillingWorker("Block A", blockA, "2026-11", testDao);
        WorkerResult result = worker.call();

        System.out.println("\nExecution Result Summary:");
        System.out.println("  " + result);
        System.out.printf("  Total Invoices Exported: %d%n", result.getExportedFiles().size());
        for (File f : result.getExportedFiles()) {
            System.out.println("    - " + f.getName());
        }
    }
}
