# StoreNET Enterprise Admin Panel - Transformation Plan

This document outlines the architectural and implementation steps required to transform the current `backoffice_web` into a multi-store Enterprise Admin Panel.

## 1. Architectural Overview

The current system is designed for a single store with a hardcoded database connection. The Enterprise version will move to a **Centralized Controller** model:

- **Enterprise Database (`storenet_enterprise`)**: A new central database to store metadata about stores, global users, and enterprise-wide configuration.
- **Store Databases (`dgpos_store_XXXX`)**: Individual databases for each store, containing local inventory, local employee punches, and store-specific transactions.
- **Dynamic Connection Pooling**: The server will maintain a map of database pools, dynamically selecting the correct one based on the user's active store context.

## 2. Phase 1: Enterprise Core & Store Management

### 2.1. Enterprise Database Schema
Create a central database `storenet_enterprise` with the following tables:
- `stores`: `id`, `name`, `store_number`, `db_host`, `db_user`, `db_pass`, `db_name`, `status`.
- `enterprise_users`: `id`, `eid`, `username`, `password`, `role` (Global Admin, District Manager).
- `global_messages`: `id`, `sender_id`, `subject`, `body`, `target_type` (All, Store, District), `target_id`.

### 2.2. Dynamic DB Middleware
Update `server.js` and `database.js` to:
- Load all active stores from the Enterprise DB on startup.
- Create a `Map<storeId, Pool>` to cache connections.
- Implement a middleware that checks `req.session.activeStoreId` and attaches the corresponding pool to `req.dbPool`.

### 2.3. Store Selection UI
- Add a "Store Selector" dropdown to the dashboard header.
- Create an "Enterprise Store Map" view to see the status of all stores (Online/Offline/Syncing).

## 3. Phase 2: Global vs. Store-Level Employees

### 3.1. Employee Hierarchy
- **Global Employees**: Managed in the Enterprise DB (District Managers, Corporate).
- **Store Employees**: Managed in the Store DB (Sales Associates, Store Managers).
- **Cross-Store Support**: Ability to "loan" an employee to another store by temporarily syncing their record to the target Store DB.

### 3.2. Payroll Consolidation
- Implement a reporting engine that iterates through all store databases to aggregate labor hours and payroll exceptions for a district-wide view.

## 4. Phase 3: Real Messaging Hub

- **Corporate Broadcasts**: High-priority "Action Required" messages pushed from the Enterprise Panel to all StoreNET terminals.
- **Read Receipts**: Track which Store Manager has acknowledged a corporate directive.
- **Attachment Support**: Link PDF MAGs (Merchandising Action Guides) directly to messages.

## 5. Phase 4: Integrated Label Printing

### 5.1. Label Printing Service
- Integrate the ESC/POS printing logic (currently in `RecieptApp` and `dgPOS`) into a centralized web-based service.
- **Batch Printing**: Allow managers to select multiple price changes and send them to the store's label printer in one click.
- **Template Engine**: Support different label sizes (Shelf Tags, Clearance Stickers, Large Signage).

## 6. Phase 5: Implementation Steps

1.  **Step 1: Database Migration**
    - Create `storenet_enterprise` database.
    - Migrate existing `users` with 'admin' roles to `enterprise_users`.
2.  **Step 2: Server Refactor**
    - Refactor `database.js` to support multiple pools.
    - Update all API endpoints to use `req.dbPool` instead of a global `pool`.
3.  **Step 3: Enterprise UI Overhaul**
    - Redesign `dashboard.html` to focus on multi-store metrics.
    - Create the "Store Management" module.
4.  **Step 4: Integration & Validation**
    - Test switching between a local "Lab Store" and a "Remote Store".
    - Validate that price changes committed in the Enterprise Panel reflect in the specific store's POS database.

## 7. Security Considerations

- **Credential Encryption**: Store DB passwords in the Enterprise DB should be encrypted at rest.
- **Network Isolation**: Use VPN/VLANs for Store DB connections; the Enterprise Panel acts as the secure gateway.
- **Audit Logging**: All enterprise-level actions (e.g., changing a price across a whole district) must be logged in `enterprise_audit_logs`.
