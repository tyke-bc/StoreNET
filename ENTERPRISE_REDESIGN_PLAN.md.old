# StoreNET Enterprise Redesign Plan

This document outlines the architectural changes required to transition the StoreNET Backoffice Web application from a prototype utilizing generated mock data to a robust, multi-tenant enterprise system capable of managing multiple store locations.

## 1. Current State Analysis
*   **Architecture:** Monolithic Node.js/Express server (`server.js`) connected to a single, hardcoded MySQL database (`dgpos`).
*   **Data Generation:** The system relies heavily on `generateDailyData()` in `database.js` to fake time punches, messages, tasks, and sales data.
*   **Scope:** All entities (users, inventory, tickets) are global to the single database.

## 2. Target Architecture: Multi-Store Hierarchy

To support an enterprise with multiple physical stores, the database and application layer must be redesigned.

### Option A: Centralized Cloud Database (Recommended)
All stores connect to a single, central cloud database. Every table includes a `store_id` foreign key.
*   **Pros:** Real-time global reporting, single source of truth, easier schema migrations.
*   **Cons:** If internet goes down, the store POS cannot function unless there is an offline-sync mechanism.

### Option B: Hub-and-Spoke (Distributed DBs)
A central "HQ" database exists, but each store has a local MySQL instance (like the current `dgPOS` setup). The Node.js backoffice acts as the HQ and syncs data to/from store IPs.
*   **Pros:** POS continues to work offline. 
*   **Cons:** Complex synchronization logic required.

**Decision:** We will adopt a hybrid approach. The Backoffice Web will connect to a Central Database for global management, and push/pull configurations to Local Store Databases.

## 3. Database Schema Overhaul

### Global vs. Store-Specific Entities
1.  **Global Entities (HQ Database):**
    *   `stores` (id, ip_address, location_name, manager_id)
    *   `global_users` (Corporate employees, Regional Managers)
    *   `master_inventory` (Corporate catalog of all SKUs and base prices)
2.  **Store-Specific Entities (Local DB & HQ Replicas):**
    *   `employees` (Tied to a specific `store_id` or `home_store_id`)
    *   `time_punches`
    *   `sales_data` & `transaction_logs`
    *   `messages` (Real messaging system)

### Real Employee Messaging System
Remove the fake message generation. Implement a real messaging schema:
*   `messages`: `id`, `sender_id`, `receiver_id` (can be a store ID for broadcast, or specific employee ID), `subject`, `body`, `timestamp`, `is_read`.
*   **UI Update:** Add an inbox UI in the dashboard for employees to compose and reply to messages.

## 4. Execution Steps

### Phase 1: Database Migration
1.  Create a `stores` table and insert the initial store (Store #14302).
2.  Add `store_id` column to `users`, `employees`, `inventory`, `sales_data`, `time_punches`, and `tasks`.
3.  Update `database.js` connection logic to support a central connection pool.

### Phase 2: Backoffice API Updates
1.  **Authentication:** Update login to determine user scope (Store Level vs. Corporate Level).
2.  **Context Switching:** Add a dropdown in the Admin UI allowing Corporate users to switch between stores. API routes will need to accept a `?storeId=` query parameter.
3.  **Deprecate Mock Data:** Completely remove `generateDailyData()`. 

### Phase 3: Real-Time Communication
1.  Implement the new Messaging API (`POST /api/messages`, `GET /api/messages/inbox`).
2.  (Optional) Integrate Socket.io for real-time notification badges when a new message or corporate task is dispatched to a store.
