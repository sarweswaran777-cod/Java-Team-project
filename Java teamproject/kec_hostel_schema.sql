-- ============================================================================
-- KEC AUTONOMOUS HOSTEL UTILITY & MESS FEE ENGINE
-- Database Schema & Seed Data Script
-- Target DBMS: MySQL 8.0+ / MariaDB / Compatible with PostgreSQL
-- Target Architecture: Multi-Tier Distributed OCI Deployment
-- Host Role: Student 2 (Database Host)
-- ============================================================================

-- Create and initialize database
CREATE DATABASE IF NOT EXISTS kec_hostel_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE kec_hostel_db;

-- ----------------------------------------------------------------------------
-- Drop existing tables in reverse dependency order
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS monthly_bills;
DROP TABLE IF EXISTS utility_readings;
DROP TABLE IF EXISTS mess_logs;
DROP TABLE IF EXISTS residents;

-- ----------------------------------------------------------------------------
-- Table 1: residents (Phase 1: Room Allocation & Base Fee Profile)
-- ----------------------------------------------------------------------------
CREATE TABLE residents (
    resident_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    block VARCHAR(10) NOT NULL,
    room_id VARCHAR(10) NOT NULL,
    category ENUM('VEG MESS', 'NON-VEG MESS', 'SPECIAL AC ROOM') NOT NULL,
    base_fee DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_room_format CHECK (room_id REGEXP '^[A-Z]{1}-[0-9]{3}$'),
    CONSTRAINT chk_base_fee CHECK (base_fee > 0.00),
    INDEX idx_resident_room (room_id),
    INDEX idx_resident_block (block)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- Table 2: mess_logs (Phase 2 & Phase 3: Special Meals & Attendance Records)
-- ----------------------------------------------------------------------------
CREATE TABLE mess_logs (
    log_id INT AUTO_INCREMENT PRIMARY KEY,
    resident_id INT NOT NULL,
    billing_month VARCHAR(7) NOT NULL, -- Format: YYYY-MM (e.g. 2026-11)
    special_meals_charge DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    guest_coupons_charge DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    mess_days_attended INT NOT NULL DEFAULT 0,
    logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mess_resident FOREIGN KEY (resident_id) 
        REFERENCES residents(resident_id) ON DELETE CASCADE,
    CONSTRAINT chk_mess_days CHECK (mess_days_attended >= 0 AND mess_days_attended <= 31),
    UNIQUE KEY uq_resident_mess_month (resident_id, billing_month),
    INDEX idx_mess_month (billing_month)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- Table 3: utility_readings (Phase 3: Electricity Meter Units)
-- ----------------------------------------------------------------------------
CREATE TABLE utility_readings (
    reading_id INT AUTO_INCREMENT PRIMARY KEY,
    resident_id INT NOT NULL,
    room_id VARCHAR(10) NOT NULL,
    billing_month VARCHAR(7) NOT NULL, -- Format: YYYY-MM
    units_consumed DECIMAL(8, 2) NOT NULL DEFAULT 0.00,
    reading_date DATE NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_utility_resident FOREIGN KEY (resident_id) 
        REFERENCES residents(resident_id) ON DELETE CASCADE,
    CONSTRAINT chk_units_positive CHECK (units_consumed >= 0.00),
    UNIQUE KEY uq_resident_utility_month (resident_id, billing_month),
    INDEX idx_utility_month (billing_month)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- Table 4: monthly_bills (Computed Final Invoices & Payment Standings)
-- ----------------------------------------------------------------------------
CREATE TABLE monthly_bills (
    bill_id INT AUTO_INCREMENT PRIMARY KEY,
    resident_id INT NOT NULL,
    billing_month VARCHAR(7) NOT NULL, -- Format: YYYY-MM
    base_mess_fee DECIMAL(10, 2) NOT NULL,
    mess_rebate DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    special_meals_total DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    electricity_units DECIMAL(8, 2) NOT NULL DEFAULT 0.00,
    electricity_cost DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    net_before_penalty DECIMAL(10, 2) NOT NULL,
    penalty_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    total_bill DECIMAL(10, 2) NOT NULL,
    payment_standing ENUM('CLEARED', 'DUE', 'DEFAULTER') NOT NULL DEFAULT 'DUE',
    payment_date DATE NULL,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bill_resident FOREIGN KEY (resident_id) 
        REFERENCES residents(resident_id) ON DELETE CASCADE,
    UNIQUE KEY uq_resident_bill_month (resident_id, billing_month),
    INDEX idx_bills_month (billing_month),
    INDEX idx_bills_standing (payment_standing)
) ENGINE=InnoDB;

-- ============================================================================
-- SEED DATA INSERTION
-- Realistic sample entries covering all 3 accommodation categories across Blocks A, B, and C
-- ============================================================================

-- Phase 1 Seed: Residents Allocation
INSERT INTO residents (student_id, name, block, room_id, category, base_fee) VALUES
-- Block A Residents
('22CS101', 'Arun Kumar S',      'A', 'A-101', 'VEG MESS',        5500.00),
('22CS102', 'Bavithra Devi R',   'A', 'A-102', 'NON-VEG MESS',    6500.00),
('22EC105', 'Chandru Mohan M',   'A', 'A-201', 'SPECIAL AC ROOM', 9500.00),
('22IT108', 'Dhanush Raj V',     'A', 'A-202', 'VEG MESS',        5500.00),

-- Block B Residents
('22EE201', 'Elango Sundaram K', 'B', 'B-101', 'NON-VEG MESS',    6500.00),
('22ME204', 'Fayaz Ahmed N',     'B', 'B-102', 'SPECIAL AC ROOM', 9500.00),
('22AD209', 'Gayathri Priya S',  'B', 'B-201', 'VEG MESS',        5500.00),
('22CB212', 'Hari Prasath T',    'B', 'B-202', 'NON-VEG MESS',    6500.00),

-- Block C Residents
('22CE301', 'Indhirani R',       'C', 'C-101', 'SPECIAL AC ROOM', 9500.00),
('22CT304', 'Jaganathan P',      'C', 'C-102', 'VEG MESS',        5500.00),
('22CS308', 'Karthick Raja B',   'C', 'C-301', 'NON-VEG MESS',    6500.00),
('22EC311', 'Lavanya Sri M',     'C', 'C-302', 'SPECIAL AC ROOM', 9500.00);

-- Phase 2 Seed: Mid-Month Special Meals & Guest Coupons for November 2026
INSERT INTO mess_logs (resident_id, billing_month, special_meals_charge, guest_coupons_charge, mess_days_attended) VALUES
(1,  '2026-11', 350.00, 150.00, 22), -- Arun (Veg): Special meal + guest, attended 22 days
(2,  '2026-11', 500.00, 200.00, 12), -- Bavithra (Non-Veg): Attended 12 days (<15 days -> Rebate eligible)
(3,  '2026-11', 750.00, 300.00, 26), -- Chandru (AC): Attended 26 days
(4,  '2026-11', 200.00,   0.00, 10), -- Dhanush (Veg): Attended 10 days (<15 days -> Rebate eligible)
(5,  '2026-11', 450.00, 150.00, 18), -- Elango (Non-Veg): Attended 18 days
(6,  '2026-11', 800.00, 250.00, 14), -- Fayaz (AC): Attended 14 days (<15 days -> Rebate eligible)
(7,  '2026-11', 150.00,  50.00, 28), -- Gayathri (Veg): Attended 28 days
(8,  '2026-11', 600.00, 200.00,  8), -- Hari Prasath (Non-Veg): Attended 8 days (<15 days -> Rebate eligible)
(9,  '2026-11', 900.00, 400.00, 20), -- Indhirani (AC): Attended 20 days
(10, '2026-11', 300.00, 100.00, 25), -- Jaganathan (Veg): Attended 25 days
(11, '2026-11', 400.00, 150.00, 11), -- Karthick Raja (Non-Veg): Attended 11 days (<15 days -> Rebate eligible)
(12, '2026-11', 850.00, 350.00, 27); -- Lavanya Sri (AC): Attended 27 days

-- Phase 3 Seed: Initial Utility Readings for November 2026
INSERT INTO utility_readings (resident_id, room_id, billing_month, units_consumed, reading_date) VALUES
(1,  'A-101', '2026-11',  65.50, '2026-11-28'), -- 15.5 units over 50 base cap
(2,  'A-102', '2026-11',  42.00, '2026-11-28'), -- Within 50 base cap
(3,  'A-201', '2026-11', 115.00, '2026-11-28'), -- AC: 15 units over 100 base cap (@ Rs 8.00/unit)
(4,  'A-202', '2026-11',  48.00, '2026-11-28'), -- Within 50 base cap
(5,  'B-101', '2026-11',  55.00, '2026-11-28'), -- 5 units over 50 base cap
(6,  'B-102', '2026-11', 140.00, '2026-11-28'), -- AC: 40 units over 100 base cap (@ Rs 8.00/unit)
(7,  'B-201', '2026-11',  38.00, '2026-11-28'), -- Within 50 base cap
(8,  'B-202', '2026-11',  52.00, '2026-11-28'), -- 2 units over 50 base cap
(9,  'C-101', '2026-11', 125.00, '2026-11-28'), -- AC: 25 units over 100 base cap (@ Rs 8.00/unit)
(10, 'C-102', '2026-11',  50.00, '2026-11-28'), -- Exactly at 50 base cap
(11, 'C-301', '2026-11',  62.00, '2026-11-28'), -- 12 units over 50 base cap
(12, 'C-302', '2026-11', 110.00, '2026-11-28'); -- AC: 10 units over 100 base cap (@ Rs 8.00/unit)

-- Verification Query
SELECT 
    r.resident_id,
    r.student_id,
    r.name,
    r.block,
    r.room_id,
    r.category,
    r.base_fee,
    m.mess_days_attended,
    (m.special_meals_charge + m.guest_coupons_charge) AS special_diet_total,
    u.units_consumed
FROM residents r
JOIN mess_logs m ON r.resident_id = m.resident_id
JOIN utility_readings u ON r.resident_id = u.resident_id
ORDER BY r.block, r.room_id;

