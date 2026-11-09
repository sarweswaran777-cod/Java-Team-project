-- ============================================================================
-- KEC AUTONOMOUS HOSTEL UTILITY & MESS FEE ENGINE
-- PostgreSQL 14+ / 15+ / 16+ Database Schema & Seed Data Script
-- Target Architecture: Multi-Tier Distributed OCI Deployment
-- Host Role: Student 2 (Database Host) on Port 5432
-- ============================================================================

-- Create database (run separately as postgres superuser if needed):
-- CREATE DATABASE kec_hostel_db WITH ENCODING 'UTF8';
-- \c kec_hostel_db;

-- ----------------------------------------------------------------------------
-- Drop existing tables in reverse dependency order
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS monthly_bills CASCADE;
DROP TABLE IF EXISTS utility_readings CASCADE;
DROP TABLE IF EXISTS mess_logs CASCADE;
DROP TABLE IF EXISTS residents CASCADE;

DROP TYPE IF EXISTS accommodation_cat CASCADE;
DROP TYPE IF EXISTS payment_standing_enum CASCADE;

-- Create custom enum types for PostgreSQL
CREATE TYPE accommodation_cat AS ENUM ('VEG MESS', 'NON-VEG MESS', 'SPECIAL AC ROOM');
CREATE TYPE payment_standing_enum AS ENUM ('CLEARED', 'DUE', 'DEFAULTER');

-- ----------------------------------------------------------------------------
-- Table 1: residents (Phase 1: Room Allocation & Base Fee Profile)
-- ----------------------------------------------------------------------------
CREATE TABLE residents (
    resident_id SERIAL PRIMARY KEY,
    student_id VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    block VARCHAR(10) NOT NULL,
    room_id VARCHAR(10) NOT NULL,
    category accommodation_cat NOT NULL,
    base_fee NUMERIC(10, 2) NOT NULL CHECK (base_fee > 0.00),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_room_format CHECK (room_id ~ '^[A-Z]{1}-[0-9]{3}$')
);

CREATE INDEX idx_resident_room ON residents(room_id);
CREATE INDEX idx_resident_block ON residents(block);

-- ----------------------------------------------------------------------------
-- Table 2: mess_logs (Phase 2 & Phase 3: Special Meals & Attendance Records)
-- ----------------------------------------------------------------------------
CREATE TABLE mess_logs (
    log_id SERIAL PRIMARY KEY,
    resident_id INT NOT NULL REFERENCES residents(resident_id) ON DELETE CASCADE,
    billing_month VARCHAR(7) NOT NULL, -- Format: YYYY-MM
    special_meals_charge NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    guest_coupons_charge NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    mess_days_attended INT NOT NULL DEFAULT 0 CHECK (mess_days_attended >= 0 AND mess_days_attended <= 31),
    logged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resident_mess_month UNIQUE (resident_id, billing_month)
);

CREATE INDEX idx_mess_month ON mess_logs(billing_month);

-- ----------------------------------------------------------------------------
-- Table 3: utility_readings (Phase 3: Electricity Meter Units)
-- ----------------------------------------------------------------------------
CREATE TABLE utility_readings (
    reading_id SERIAL PRIMARY KEY,
    resident_id INT NOT NULL REFERENCES residents(resident_id) ON DELETE CASCADE,
    room_id VARCHAR(10) NOT NULL,
    billing_month VARCHAR(7) NOT NULL,
    units_consumed NUMERIC(8, 2) NOT NULL DEFAULT 0.00 CHECK (units_consumed >= 0.00),
    reading_date DATE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resident_utility_month UNIQUE (resident_id, billing_month)
);

CREATE INDEX idx_utility_month ON utility_readings(billing_month);

-- ----------------------------------------------------------------------------
-- Table 4: monthly_bills (Computed Final Invoices & Payment Standings)
-- ----------------------------------------------------------------------------
CREATE TABLE monthly_bills (
    bill_id SERIAL PRIMARY KEY,
    resident_id INT NOT NULL REFERENCES residents(resident_id) ON DELETE CASCADE,
    billing_month VARCHAR(7) NOT NULL,
    base_mess_fee NUMERIC(10, 2) NOT NULL,
    mess_rebate NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    special_meals_total NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    electricity_units NUMERIC(8, 2) NOT NULL DEFAULT 0.00,
    electricity_cost NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    net_before_penalty NUMERIC(10, 2) NOT NULL,
    penalty_amount NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total_bill NUMERIC(10, 2) NOT NULL,
    payment_standing payment_standing_enum NOT NULL DEFAULT 'DUE',
    payment_date DATE NULL,
    generated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resident_bill_month UNIQUE (resident_id, billing_month)
);

CREATE INDEX idx_bills_month ON monthly_bills(billing_month);
CREATE INDEX idx_bills_standing ON monthly_bills(payment_standing);

-- ============================================================================
-- SEED DATA INSERTION (PostgreSQL Syntax)
-- ============================================================================

INSERT INTO residents (student_id, name, block, room_id, category, base_fee) VALUES
('22CS101', 'Arun Kumar S',      'A', 'A-101', 'VEG MESS',        5500.00),
('22CS102', 'Bavithra Devi R',   'A', 'A-102', 'NON-VEG MESS',    6500.00),
('22EC105', 'Chandru Mohan M',   'A', 'A-201', 'SPECIAL AC ROOM', 9500.00),
('22IT108', 'Dhanush Raj V',     'A', 'A-202', 'VEG MESS',        5500.00),

('22EE201', 'Elango Sundaram K', 'B', 'B-101', 'NON-VEG MESS',    6500.00),
('22ME204', 'Fayaz Ahmed N',     'B', 'B-102', 'SPECIAL AC ROOM', 9500.00),
('22AD209', 'Gayathri Priya S',  'B', 'B-201', 'VEG MESS',        5500.00),
('22CB212', 'Hari Prasath T',    'B', 'B-202', 'NON-VEG MESS',    6500.00),

('22CE301', 'Indhirani R',       'C', 'C-101', 'SPECIAL AC ROOM', 9500.00),
('22CT304', 'Jaganathan P',      'C', 'C-102', 'VEG MESS',        5500.00),
('22CS308', 'Karthick Raja B',   'C', 'C-301', 'NON-VEG MESS',    6500.00),
('22EC311', 'Lavanya Sri M',     'C', 'C-302', 'SPECIAL AC ROOM', 9500.00);

INSERT INTO mess_logs (resident_id, billing_month, special_meals_charge, guest_coupons_charge, mess_days_attended) VALUES
(1,  '2026-11', 350.00, 150.00, 22),
(2,  '2026-11', 500.00, 200.00, 12),
(3,  '2026-11', 750.00, 300.00, 26),
(4,  '2026-11', 200.00,   0.00, 10),
(5,  '2026-11', 450.00, 150.00, 18),
(6,  '2026-11', 800.00, 250.00, 14),
(7,  '2026-11', 150.00,  50.00, 28),
(8,  '2026-11', 600.00, 200.00,  8),
(9,  '2026-11', 900.00, 400.00, 20),
(10, '2026-11', 300.00, 100.00, 25),
(11, '2026-11', 400.00, 150.00, 11),
(12, '2026-11', 850.00, 350.00, 27);

INSERT INTO utility_readings (resident_id, room_id, billing_month, units_consumed, reading_date) VALUES
(1,  'A-101', '2026-11',  65.50, '2026-11-28'),
(2,  'A-102', '2026-11',  42.00, '2026-11-28'),
(3,  'A-201', '2026-11', 115.00, '2026-11-28'),
(4,  'A-202', '2026-11',  48.00, '2026-11-28'),
(5,  'B-101', '2026-11',  55.00, '2026-11-28'),
(6,  'B-102', '2026-11', 140.00, '2026-11-28'),
(7,  'B-201', '2026-11',  38.00, '2026-11-28'),
(8,  'B-202', '2026-11',  52.00, '2026-11-28'),
(9,  'C-101', '2026-11', 125.00, '2026-11-28'),
(10, 'C-102', '2026-11',  50.00, '2026-11-28'),
(11, 'C-301', '2026-11',  62.00, '2026-11-28'),
(12, 'C-302', '2026-11', 110.00, '2026-11-28');

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

