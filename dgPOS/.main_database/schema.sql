-- dgpOS MySQL Database Schema
-- Run this on your separate database PC to initialize the dgpOS database.

CREATE DATABASE IF NOT EXISTS dgpos;
USE dgpos;

-- Item Master Table
CREATE TABLE IF NOT EXISTS items (
    upc VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);

-- Transaction Logging Table
CREATE TABLE IF NOT EXISTS transaction_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    eid VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,
    upc VARCHAR(50),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Initial Dummy Data
INSERT IGNORE INTO items (upc, name, price) VALUES 
('123456789012', 'COCA-COLA 20OZ', 2.25),
('028400047685', 'DORITOS NACHO CHS', 4.50),
('037000874514', 'TIDE PODS 16CT', 6.00),
('000000000000', 'VOIDED ITEM', 0.00);

-- Users Table
CREATE TABLE IF NOT EXISTS users (
    eid VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    pin VARCHAR(50) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'SA'
);

-- Initial Users
INSERT IGNORE INTO users (eid, name, pin, role) VALUES 
('3756772', 'Tyke', '3063', 'SM'),
('3780722', 'Amanda', '2781', 'SA');
