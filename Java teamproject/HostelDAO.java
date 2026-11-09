import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * HostelDAO - Data Access Object and Domain Models for KEC Autonomous Hostel Engine.
 * 
 * Implements CRUD operations, transaction management, and dual-mode persistence:
 * 1. Live Remote JDBC mode for OCI MySQL / PostgreSQL deployment.
 * 2. High-fidelity in-memory persistence fallback when remote DB is offline for local testing.
 */
public class HostelDAO {

    private static final Logger LOGGER = Logger.getLogger(HostelDAO.class.getName());

    // Regex pattern for strict Room ID validation: ^[A-Z]{1}-\d{3}$ (e.g., A-101, B-205)
    public static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[A-Z]{1}-\\d{3}$");

    // =========================================================================
    // DOMAIN ENUMS AND MODELS
    // =========================================================================

    /**
     * Accommodation categories and their rate structures.
     */
    public enum AccommodationCategory {
        VEG_MESS("VEG MESS", 0.50, 0.30, 0.20, 50.0, 6.00),
        NON_VEG_MESS("NON-VEG MESS", 0.45, 0.35, 0.20, 50.0, 6.00),
        SPECIAL_AC_ROOM("SPECIAL AC ROOM", 0.35, 0.25, 0.40, 100.0, 8.00);

        private final String displayName;
        private final double baseMessWeight;
        private final double specialDietWeight;
        private final double electricityWeight;
        private final double electricityBaseCap;
        private final double extraUnitRate;

        AccommodationCategory(String displayName, double baseMessWeight, double specialDietWeight,
                              double electricityWeight, double electricityBaseCap, double extraUnitRate) {
            this.displayName = displayName;
            this.baseMessWeight = baseMessWeight;
            this.specialDietWeight = specialDietWeight;
            this.electricityWeight = electricityWeight;
            this.electricityBaseCap = electricityBaseCap;
            this.extraUnitRate = extraUnitRate;
        }

        public String getDisplayName() { return displayName; }
        public double getBaseMessWeight() { return baseMessWeight; }
        public double getSpecialDietWeight() { return specialDietWeight; }
        public double getElectricityWeight() { return electricityWeight; }
        public double getElectricityBaseCap() { return electricityBaseCap; }
        public double getExtraUnitRate() { return extraUnitRate; }

        public static AccommodationCategory fromString(String text) {
            if (text == null) return VEG_MESS;
            String normalized = text.trim().toUpperCase().replace("_", " ");
            for (AccommodationCategory cat : values()) {
                if (cat.displayName.equalsIgnoreCase(normalized) || cat.name().equalsIgnoreCase(text.trim())) {
                    return cat;
                }
            }
            throw new IllegalArgumentException("Unknown Accommodation Category: " + text + 
                ". Allowed: 'VEG MESS', 'NON-VEG MESS', 'SPECIAL AC ROOM'");
        }
    }

    /**
     * Payment Standing categorization:
     * - CLEARED: Paid on or before the 15th (Penalty = 0)
     * - DUE: Unpaid between 16th and 20th (Penalty = 0)
     * - DEFAULTER: Unpaid after the 20th -> 10% late penalty fine
     */
    public enum PaymentStanding {
        CLEARED,
        DUE,
        DEFAULTER;

        public static PaymentStanding evaluateByDay(int dayOfMonth) {
            if (dayOfMonth <= 15) {
                return CLEARED;
            } else if (dayOfMonth <= 20) {
                return DUE;
            } else {
                return DEFAULTER;
            }
        }

        public static PaymentStanding fromString(String val) {
            if (val == null) return DUE;
            try {
                return PaymentStanding.valueOf(val.trim().toUpperCase());
            } catch (Exception e) {
                return DUE;
            }
        }
    }

    /**
     * Phase 1 Resident Model
     */
    public static class Resident {
        private int residentId;
        private String studentId;
        private String name;
        private String block;
        private String roomId;
        private AccommodationCategory category;
        private double baseFee;
        private String status;

        public Resident() {
            this.status = "ACTIVE";
        }

        public Resident(int residentId, String studentId, String name, String block, 
                        String roomId, AccommodationCategory category, double baseFee) {
            this.residentId = residentId;
            this.studentId = sanitizeString(studentId);
            this.name = sanitizeName(name);
            this.roomId = validateRoomId(roomId);
            this.block = (block == null || block.trim().isEmpty()) ? roomId.substring(0, 1) : block.trim().toUpperCase();
            this.category = category;
            this.baseFee = baseFee;
            this.status = "ACTIVE";
        }

        public static String sanitizeString(String input) {
            return input == null ? "" : input.trim();
        }

        public static String sanitizeName(String rawName) {
            if (rawName == null) return "";
            String trimmed = rawName.trim();
            // Capitalize first letters of words
            String[] words = trimmed.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" ");
                if (w.length() == 1) {
                    sb.append(w.toUpperCase());
                } else {
                    sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
            }
            return sb.toString();
        }

        public static String validateRoomId(String roomId) {
            if (roomId == null) {
                throw new IllegalArgumentException("Room ID cannot be null.");
            }
            String trimmed = roomId.trim().toUpperCase();
            if (!ROOM_ID_PATTERN.matcher(trimmed).matches()) {
                throw new IllegalArgumentException(String.format(
                    "Invalid Room ID '%s'. Must match Regex pattern '^[A-Z]{1}-\\d{3}$' (e.g., A-101, B-205).", roomId));
            }
            return trimmed;
        }

        // Getters & Setters
        public int getResidentId() { return residentId; }
        public void setResidentId(int residentId) { this.residentId = residentId; }
        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = sanitizeString(studentId); }
        public String getName() { return name; }
        public void setName(String name) { this.name = sanitizeName(name); }
        public String getBlock() { return block; }
        public void setBlock(String block) { this.block = block; }
        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { 
            this.roomId = validateRoomId(roomId);
            this.block = this.roomId.substring(0, 1);
        }
        public AccommodationCategory getCategory() { return category; }
        public void setCategory(AccommodationCategory category) { this.category = category; }
        public double getBaseFee() { return baseFee; }
        public void setBaseFee(double baseFee) { this.baseFee = baseFee; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        @Override
        public String toString() {
            return String.format("[%s] %s | Room: %s (Block %s) | Category: %s | Base Fee: Rs. %.2f",
                studentId, name, roomId, block, category.getDisplayName(), baseFee);
        }
    }

    /**
     * Phase 2 & 3 Mess Log Model
     */
    public static class MessLog {
        private int logId;
        private int residentId;
        private String billingMonth;
        private double specialMealsCharge;
        private double guestCouponsCharge;
        private int messDaysAttended;

        public MessLog() {}

        public MessLog(int residentId, String billingMonth, double specialMealsCharge, 
                       double guestCouponsCharge, int messDaysAttended) {
            this.residentId = residentId;
            this.billingMonth = billingMonth;
            this.specialMealsCharge = specialMealsCharge;
            this.guestCouponsCharge = guestCouponsCharge;
            this.messDaysAttended = messDaysAttended;
        }

        public double getTotalSpecialMeals() {
            return specialMealsCharge + guestCouponsCharge;
        }

        public int getLogId() { return logId; }
        public void setLogId(int logId) { this.logId = logId; }
        public int getResidentId() { return residentId; }
        public void setResidentId(int residentId) { this.residentId = residentId; }
        public String getBillingMonth() { return billingMonth; }
        public void setBillingMonth(String billingMonth) { this.billingMonth = billingMonth; }
        public double getSpecialMealsCharge() { return specialMealsCharge; }
        public void setSpecialMealsCharge(double specialMealsCharge) { this.specialMealsCharge = specialMealsCharge; }
        public double getGuestCouponsCharge() { return guestCouponsCharge; }
        public void setGuestCouponsCharge(double guestCouponsCharge) { this.guestCouponsCharge = guestCouponsCharge; }
        public int getMessDaysAttended() { return messDaysAttended; }
        public void setMessDaysAttended(int messDaysAttended) { this.messDaysAttended = messDaysAttended; }
    }

    /**
     * Phase 3 Utility Reading Model
     */
    public static class UtilityReading {
        private int readingId;
        private int residentId;
        private String roomId;
        private String billingMonth;
        private double unitsConsumed;
        private LocalDate readingDate;

        public UtilityReading() {}

        public UtilityReading(int residentId, String roomId, String billingMonth, 
                              double unitsConsumed, LocalDate readingDate) {
            this.residentId = residentId;
            this.roomId = roomId;
            this.billingMonth = billingMonth;
            this.unitsConsumed = unitsConsumed;
            this.readingDate = readingDate;
        }

        public int getReadingId() { return readingId; }
        public void setReadingId(int readingId) { this.readingId = readingId; }
        public int getResidentId() { return residentId; }
        public void setResidentId(int residentId) { this.residentId = residentId; }
        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public String getBillingMonth() { return billingMonth; }
        public void setBillingMonth(String billingMonth) { this.billingMonth = billingMonth; }
        public double getUnitsConsumed() { return unitsConsumed; }
        public void setUnitsConsumed(double unitsConsumed) { this.unitsConsumed = unitsConsumed; }
        public LocalDate getReadingDate() { return readingDate; }
        public void setReadingDate(LocalDate readingDate) { this.readingDate = readingDate; }
    }

    /**
     * Final Computed Monthly Bill Statement Model
     */
    public static class MonthlyBill {
        private int billId;
        private int residentId;
        private Resident resident; // Transient reference for display & export
        private String billingMonth;
        private double baseMessFee;
        private double messRebate;
        private double specialMealsTotal;
        private double electricityUnits;
        private double electricityCost;
        private double netBeforePenalty;
        private double penaltyAmount;
        private double totalBill;
        private PaymentStanding paymentStanding;
        private LocalDate paymentDate;
        private Timestamp generatedAt;

        public MonthlyBill() {}

        // Getters and setters
        public int getBillId() { return billId; }
        public void setBillId(int billId) { this.billId = billId; }
        public int getResidentId() { return residentId; }
        public void setResidentId(int residentId) { this.residentId = residentId; }
        public Resident getResident() { return resident; }
        public void setResident(Resident resident) { this.resident = resident; }
        public String getBillingMonth() { return billingMonth; }
        public void setBillingMonth(String billingMonth) { this.billingMonth = billingMonth; }
        public double getBaseMessFee() { return baseMessFee; }
        public void setBaseMessFee(double baseMessFee) { this.baseMessFee = baseMessFee; }
        public double getMessRebate() { return messRebate; }
        public void setMessRebate(double messRebate) { this.messRebate = messRebate; }
        public double getSpecialMealsTotal() { return specialMealsTotal; }
        public void setSpecialMealsTotal(double specialMealsTotal) { this.specialMealsTotal = specialMealsTotal; }
        public double getElectricityUnits() { return electricityUnits; }
        public void setElectricityUnits(double electricityUnits) { this.electricityUnits = electricityUnits; }
        public double getElectricityCost() { return electricityCost; }
        public void setElectricityCost(double electricityCost) { this.electricityCost = electricityCost; }
        public double getNetBeforePenalty() { return netBeforePenalty; }
        public void setNetBeforePenalty(double netBeforePenalty) { this.netBeforePenalty = netBeforePenalty; }
        public double getPenaltyAmount() { return penaltyAmount; }
        public void setPenaltyAmount(double penaltyAmount) { this.penaltyAmount = penaltyAmount; }
        public double getTotalBill() { return totalBill; }
        public void setTotalBill(double totalBill) { this.totalBill = totalBill; }
        public PaymentStanding getPaymentStanding() { return paymentStanding; }
        public void setPaymentStanding(PaymentStanding paymentStanding) { this.paymentStanding = paymentStanding; }
        public LocalDate getPaymentDate() { return paymentDate; }
        public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }
        public Timestamp getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(Timestamp generatedAt) { this.generatedAt = generatedAt; }
    }

    // =========================================================================
    // IN-MEMORY RESILIENT DATA STORE (FALLBACK WHEN DB IS OFFLINE)
    // =========================================================================
    private static final Map<Integer, Resident> memResidents = new ConcurrentHashMap<>();
    private static final Map<String, Resident> memResidentsByRoom = new ConcurrentHashMap<>();
    private static final Map<String, MessLog> memMessLogs = new ConcurrentHashMap<>(); // key: residentId + "_" + month
    private static final Map<String, UtilityReading> memUtilityReadings = new ConcurrentHashMap<>(); // key: residentId + "_" + month
    private static final Map<String, MonthlyBill> memBills = new ConcurrentHashMap<>(); // key: residentId + "_" + month
    private static final AtomicInteger idSequence = new AtomicInteger(100);

    static {
        // Initialize mock seed data in case offline simulation is required
        initInMemorySeeds();
    }

    private static void initInMemorySeeds() {
        Resident[] seeds = new Resident[] {
            new Resident(1, "22CS101", "Arun Kumar S", "A", "A-101", AccommodationCategory.VEG_MESS, 5500.00),
            new Resident(2, "22CS102", "Bavithra Devi R", "A", "A-102", AccommodationCategory.NON_VEG_MESS, 6500.00),
            new Resident(3, "22EC105", "Chandru Mohan M", "A", "A-201", AccommodationCategory.SPECIAL_AC_ROOM, 9500.00),
            new Resident(4, "22IT108", "Dhanush Raj V", "A", "A-202", AccommodationCategory.VEG_MESS, 5500.00),

            new Resident(5, "22EE201", "Elango Sundaram K", "B", "B-101", AccommodationCategory.NON_VEG_MESS, 6500.00),
            new Resident(6, "22ME204", "Fayaz Ahmed N", "B", "B-102", AccommodationCategory.SPECIAL_AC_ROOM, 9500.00),
            new Resident(7, "22AD209", "Gayathri Priya S", "B", "B-201", AccommodationCategory.VEG_MESS, 5500.00),
            new Resident(8, "22CB212", "Hari Prasath T", "B", "B-202", AccommodationCategory.NON_VEG_MESS, 6500.00),

            new Resident(9, "22CE301", "Indhirani R", "C", "C-101", AccommodationCategory.SPECIAL_AC_ROOM, 9500.00),
            new Resident(10, "22CT304", "Jaganathan P", "C", "C-102", AccommodationCategory.VEG_MESS, 5500.00),
            new Resident(11, "22CS308", "Karthick Raja B", "C", "C-301", AccommodationCategory.NON_VEG_MESS, 6500.00),
            new Resident(12, "22EC311", "Lavanya Sri M", "C", "C-302", AccommodationCategory.SPECIAL_AC_ROOM, 9500.00)
        };

        for (Resident r : seeds) {
            memResidents.put(r.getResidentId(), r);
            memResidentsByRoom.put(r.getRoomId(), r);
        }

        // Seed Mess Logs (Nov 2026)
        double[][] messSeed = {
            {1, 350, 150, 22}, {2, 500, 200, 12}, {3, 750, 300, 26}, {4, 200, 0, 10},
            {5, 450, 150, 18}, {6, 800, 250, 14}, {7, 150, 50, 28},  {8, 600, 200, 8},
            {9, 900, 400, 20}, {10, 300, 100, 25}, {11, 400, 150, 11}, {12, 850, 350, 27}
        };
        for (double[] row : messSeed) {
            int rId = (int) row[0];
            MessLog m = new MessLog(rId, "2026-11", row[1], row[2], (int) row[3]);
            m.setLogId(rId);
            memMessLogs.put(rId + "_2026-11", m);
        }

        // Seed Utility Readings (Nov 2026)
        double[][] utilSeed = {
            {1, 65.5}, {2, 42.0}, {3, 115.0}, {4, 48.0},
            {5, 55.0}, {6, 140.0}, {7, 38.0},  {8, 52.0},
            {9, 125.0}, {10, 50.0}, {11, 62.0}, {12, 110.0}
        };
        for (double[] row : utilSeed) {
            int rId = (int) row[0];
            Resident r = memResidents.get(rId);
            UtilityReading u = new UtilityReading(rId, r.getRoomId(), "2026-11", row[1], LocalDate.of(2026, 11, 28));
            u.setReadingId(rId);
            memUtilityReadings.put(rId + "_2026-11", u);
        }
    }

    // =========================================================================
    // PHASE 1 OPERATIONS: RESIDENT ALLOCATION & BASE FEES
    // =========================================================================

    public boolean insertResident(Resident r) {
        if (!DBConnection.isRemoteDbAvailable()) {
            int id = idSequence.incrementAndGet();
            r.setResidentId(id);
            memResidents.put(id, r);
            memResidentsByRoom.put(r.getRoomId(), r);
            return true;
        }

        String sql = "INSERT INTO residents (student_id, name, block, room_id, category, base_fee, status) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getStudentId());
            ps.setString(2, r.getName());
            ps.setString(3, r.getBlock());
            ps.setString(4, r.getRoomId());
            ps.setString(5, r.getCategory().getDisplayName());
            ps.setDouble(6, r.getBaseFee());
            ps.setString(7, r.getStatus());

            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        r.setResidentId(rs.getInt(1));
                    }
                }
                return true;
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] insertResident SQL Error: " + e.getMessage(), e);
        }
        return false;
    }

    public List<Resident> getAllResidents() {
        if (!DBConnection.isRemoteDbAvailable()) {
            List<Resident> list = new ArrayList<>(memResidents.values());
            list.sort(Comparator.comparing(Resident::getRoomId));
            return list;
        }

        List<Resident> list = new ArrayList<>();
        String sql = "SELECT resident_id, student_id, name, block, room_id, category, base_fee, status FROM residents ORDER BY room_id";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Resident r = new Resident(
                    rs.getInt("resident_id"),
                    rs.getString("student_id"),
                    rs.getString("name"),
                    rs.getString("block"),
                    rs.getString("room_id"),
                    AccommodationCategory.fromString(rs.getString("category")),
                    rs.getDouble("base_fee")
                );
                r.setStatus(rs.getString("status"));
                list.add(r);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getAllResidents error: " + e.getMessage(), e);
        }
        return list;
    }

    public List<Resident> getResidentsByBlock(String block) {
        if (!DBConnection.isRemoteDbAvailable()) {
            List<Resident> blockList = new ArrayList<>();
            for (Resident r : memResidents.values()) {
                if (r.getBlock().equalsIgnoreCase(block)) {
                    blockList.add(r);
                }
            }
            blockList.sort(Comparator.comparing(Resident::getRoomId));
            return blockList;
        }

        List<Resident> blockList = new ArrayList<>();
        String sql = "SELECT resident_id, student_id, name, block, room_id, category, base_fee, status " +
                     "FROM residents WHERE UPPER(block) = UPPER(?) ORDER BY room_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, block.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Resident r = new Resident(
                        rs.getInt("resident_id"),
                        rs.getString("student_id"),
                        rs.getString("name"),
                        rs.getString("block"),
                        rs.getString("room_id"),
                        AccommodationCategory.fromString(rs.getString("category")),
                        rs.getDouble("base_fee")
                    );
                    r.setStatus(rs.getString("status"));
                    blockList.add(r);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getResidentsByBlock error: " + e.getMessage(), e);
        }
        return blockList;
    }

    public Resident getResidentByRoomId(String roomId) {
        String cleanRoom = Resident.validateRoomId(roomId);
        if (!DBConnection.isRemoteDbAvailable()) {
            return memResidentsByRoom.get(cleanRoom);
        }

        String sql = "SELECT resident_id, student_id, name, block, room_id, category, base_fee, status FROM residents WHERE room_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cleanRoom);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Resident r = new Resident(
                        rs.getInt("resident_id"),
                        rs.getString("student_id"),
                        rs.getString("name"),
                        rs.getString("block"),
                        rs.getString("room_id"),
                        AccommodationCategory.fromString(rs.getString("category")),
                        rs.getDouble("base_fee")
                    );
                    r.setStatus(rs.getString("status"));
                    return r;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getResidentByRoomId error: " + e.getMessage(), e);
        }
        return null;
    }

    public Set<String> getAllBlocks() {
        Set<String> blocks = new TreeSet<>();
        if (!DBConnection.isRemoteDbAvailable()) {
            for (Resident r : memResidents.values()) {
                blocks.add(r.getBlock());
            }
            return blocks;
        }

        String sql = "SELECT DISTINCT block FROM residents ORDER BY block";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                blocks.add(rs.getString("block"));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getAllBlocks error: " + e.getMessage(), e);
        }
        return blocks;
    }

    // =========================================================================
    // PHASE 2 & 3 OPERATIONS: MESS LOGS & UTILITY READINGS
    // =========================================================================

    public boolean saveOrUpdateMessLog(MessLog log) {
        if (!DBConnection.isRemoteDbAvailable()) {
            memMessLogs.put(log.getResidentId() + "_" + log.getBillingMonth(), log);
            return true;
        }

        String sql = "INSERT INTO mess_logs (resident_id, billing_month, special_meals_charge, guest_coupons_charge, mess_days_attended) " +
                     "VALUES (?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE special_meals_charge = VALUES(special_meals_charge), " +
                     "guest_coupons_charge = VALUES(guest_coupons_charge), " +
                     "mess_days_attended = VALUES(mess_days_attended)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, log.getResidentId());
            ps.setString(2, log.getBillingMonth());
            ps.setDouble(3, log.getSpecialMealsCharge());
            ps.setDouble(4, log.getGuestCouponsCharge());
            ps.setInt(5, log.getMessDaysAttended());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] saveOrUpdateMessLog error: " + e.getMessage(), e);
            return false;
        }
    }

    public MessLog getMessLog(int residentId, String billingMonth) {
        if (!DBConnection.isRemoteDbAvailable()) {
            MessLog m = memMessLogs.get(residentId + "_" + billingMonth);
            return (m != null) ? m : new MessLog(residentId, billingMonth, 0.0, 0.0, 30);
        }

        String sql = "SELECT log_id, resident_id, billing_month, special_meals_charge, guest_coupons_charge, mess_days_attended " +
                     "FROM mess_logs WHERE resident_id = ? AND billing_month = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setString(2, billingMonth);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    MessLog m = new MessLog(
                        rs.getInt("resident_id"),
                        rs.getString("billing_month"),
                        rs.getDouble("special_meals_charge"),
                        rs.getDouble("guest_coupons_charge"),
                        rs.getInt("mess_days_attended")
                    );
                    m.setLogId(rs.getInt("log_id"));
                    return m;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getMessLog error: " + e.getMessage(), e);
        }
        return new MessLog(residentId, billingMonth, 0.0, 0.0, 30);
    }

    public boolean saveOrUpdateUtilityReading(UtilityReading u) {
        if (!DBConnection.isRemoteDbAvailable()) {
            memUtilityReadings.put(u.getResidentId() + "_" + u.getBillingMonth(), u);
            return true;
        }

        String sql = "INSERT INTO utility_readings (resident_id, room_id, billing_month, units_consumed, reading_date) " +
                     "VALUES (?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE units_consumed = VALUES(units_consumed), reading_date = VALUES(reading_date)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, u.getResidentId());
            ps.setString(2, u.getRoomId());
            ps.setString(3, u.getBillingMonth());
            ps.setDouble(4, u.getUnitsConsumed());
            ps.setDate(5, java.sql.Date.valueOf(u.getReadingDate()));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] saveOrUpdateUtilityReading error: " + e.getMessage(), e);
            return false;
        }
    }

    public UtilityReading getUtilityReading(int residentId, String billingMonth) {
        if (!DBConnection.isRemoteDbAvailable()) {
            UtilityReading u = memUtilityReadings.get(residentId + "_" + billingMonth);
            return (u != null) ? u : new UtilityReading(residentId, "", billingMonth, 50.0, LocalDate.now());
        }

        String sql = "SELECT reading_id, resident_id, room_id, billing_month, units_consumed, reading_date " +
                     "FROM utility_readings WHERE resident_id = ? AND billing_month = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setString(2, billingMonth);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UtilityReading u = new UtilityReading(
                        rs.getInt("resident_id"),
                        rs.getString("room_id"),
                        rs.getString("billing_month"),
                        rs.getDouble("units_consumed"),
                        rs.getDate("reading_date").toLocalDate()
                    );
                    u.setReadingId(rs.getInt("reading_id"));
                    return u;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getUtilityReading error: " + e.getMessage(), e);
        }
        return new UtilityReading(residentId, "", billingMonth, 50.0, LocalDate.now());
    }

    // =========================================================================
    // TRANSACTION-MANAGED BILLING PERSISTENCE
    // =========================================================================

    /**
     * Persists or updates the calculated monthly bill within an ACID database transaction.
     */
    public boolean saveMonthlyBill(MonthlyBill bill) {
        if (!DBConnection.isRemoteDbAvailable()) {
            bill.setBillId(idSequence.incrementAndGet());
            bill.setGeneratedAt(new Timestamp(System.currentTimeMillis()));
            memBills.put(bill.getResidentId() + "_" + bill.getBillingMonth(), bill);
            return true;
        }

        String sql = "INSERT INTO monthly_bills " +
                     "(resident_id, billing_month, base_mess_fee, mess_rebate, special_meals_total, " +
                     "electricity_units, electricity_cost, net_before_penalty, penalty_amount, " +
                     "total_bill, payment_standing, payment_date, generated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "base_mess_fee = VALUES(base_mess_fee), " +
                     "mess_rebate = VALUES(mess_rebate), " +
                     "special_meals_total = VALUES(special_meals_total), " +
                     "electricity_units = VALUES(electricity_units), " +
                     "electricity_cost = VALUES(electricity_cost), " +
                     "net_before_penalty = VALUES(net_before_penalty), " +
                     "penalty_amount = VALUES(penalty_amount), " +
                     "total_bill = VALUES(total_bill), " +
                     "payment_standing = VALUES(payment_standing), " +
                     "payment_date = VALUES(payment_date), " +
                     "generated_at = CURRENT_TIMESTAMP";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // Begin ACID Transaction

            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, bill.getResidentId());
                ps.setString(2, bill.getBillingMonth());
                ps.setDouble(3, bill.getBaseMessFee());
                ps.setDouble(4, bill.getMessRebate());
                ps.setDouble(5, bill.getSpecialMealsTotal());
                ps.setDouble(6, bill.getElectricityUnits());
                ps.setDouble(7, bill.getElectricityCost());
                ps.setDouble(8, bill.getNetBeforePenalty());
                ps.setDouble(9, bill.getPenaltyAmount());
                ps.setDouble(10, bill.getTotalBill());
                ps.setString(11, bill.getPaymentStanding().name());
                if (bill.getPaymentDate() != null) {
                    ps.setDate(12, java.sql.Date.valueOf(bill.getPaymentDate()));
                } else {
                    ps.setNull(12, Types.DATE);
                }

                ps.executeUpdate();
                conn.commit(); // Commit Transaction
                return true;
            } catch (SQLException ex) {
                conn.rollback(); // Rollback on failure
                LOGGER.log(Level.SEVERE, "[HostelDAO] Transaction rolled back for resident ID " + bill.getResidentId(), ex);
                throw ex;
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] saveMonthlyBill error: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    public List<MonthlyBill> getAllBillsForMonth(String billingMonth) {
        List<MonthlyBill> bills = new ArrayList<>();
        if (!DBConnection.isRemoteDbAvailable()) {
            for (MonthlyBill b : memBills.values()) {
                if (b.getBillingMonth().equals(billingMonth)) {
                    b.setResident(memResidents.get(b.getResidentId()));
                    bills.add(b);
                }
            }
            bills.sort(Comparator.comparing(b -> b.getResident().getRoomId()));
            return bills;
        }

        String sql = "SELECT b.*, r.student_id, r.name, r.block, r.room_id, r.category, r.base_fee, r.status " +
                     "FROM monthly_bills b " +
                     "JOIN residents r ON b.resident_id = r.resident_id " +
                     "WHERE b.billing_month = ? " +
                     "ORDER BY r.block, r.room_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, billingMonth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MonthlyBill b = new MonthlyBill();
                    b.setBillId(rs.getInt("bill_id"));
                    b.setResidentId(rs.getInt("resident_id"));
                    b.setBillingMonth(rs.getString("billing_month"));
                    b.setBaseMessFee(rs.getDouble("base_mess_fee"));
                    b.setMessRebate(rs.getDouble("mess_rebate"));
                    b.setSpecialMealsTotal(rs.getDouble("special_meals_total"));
                    b.setElectricityUnits(rs.getDouble("electricity_units"));
                    b.setElectricityCost(rs.getDouble("electricity_cost"));
                    b.setNetBeforePenalty(rs.getDouble("net_before_penalty"));
                    b.setPenaltyAmount(rs.getDouble("penalty_amount"));
                    b.setTotalBill(rs.getDouble("total_bill"));
                    b.setPaymentStanding(PaymentStanding.fromString(rs.getString("payment_standing")));
                    java.sql.Date payDate = rs.getDate("payment_date");
                    if (payDate != null) b.setPaymentDate(payDate.toLocalDate());
                    b.setGeneratedAt(rs.getTimestamp("generated_at"));

                    Resident r = new Resident(
                        rs.getInt("resident_id"),
                        rs.getString("student_id"),
                        rs.getString("name"),
                        rs.getString("block"),
                        rs.getString("room_id"),
                        AccommodationCategory.fromString(rs.getString("category")),
                        rs.getDouble("base_fee")
                    );
                    b.setResident(r);
                    bills.add(b);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[HostelDAO] getAllBillsForMonth error: " + e.getMessage(), e);
        }
        return bills;
    }

    /**
     * Standalone main method demonstrating direct HostelDAO queries and resident verification.
     */
    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {}

        System.out.println("Testing HostelDAO operations...");
        HostelDAO dao = new HostelDAO();
        List<Resident> residents = dao.getAllResidents();
        System.out.printf("[SUCCESS] Loaded %d residents across blocks %s.%n%n", residents.size(), dao.getAllBlocks());
        for (Resident r : residents) {
            System.out.println("  " + r);
        }
    }
}
