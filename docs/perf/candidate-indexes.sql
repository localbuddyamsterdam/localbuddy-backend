-- Candidate composite indexes — MEASURE-FIRST (do NOT apply blindly)
-- ---------------------------------------------------------------------------
-- These are performance-audit RECOMMENDATIONS, deliberately kept OUT of
-- src/main/resources/db/migration so Flyway does NOT apply them on deploy.
--
-- Per this repo's performance rules ("Check SQL execution plans before
-- recommending indexes"), validate each one on production-sized data with:
--
--     EXPLAIN (ANALYZE, BUFFERS) <the query the index targets>;
--
-- Confirm the planner switches from a Seq Scan (+ Sort) to an Index Scan and
-- that the win justifies the write-time cost. THEN move the chosen statements
-- into a new numbered migration (highest applied is V45, so V46, V47, ...),
-- one concern per file, and let Flyway apply them.
--
-- On a live table prefer CREATE INDEX CONCURRENTLY (cannot run inside the
-- transactional Flyway wrapper — use a separate non-transactional migration
-- or run it manually during a maintenance window).
-- ---------------------------------------------------------------------------

-- 1) notifications (status, created_at) — hottest scheduler (~10s poll)
--    Query: NotificationRepository.findPendingNotificationIds — WHERE status=? ORDER BY created_at
--    Today: separate idx_notifications_status + idx_notifications_created_at (cannot combine well).
CREATE INDEX idx_notifications_status_created_at
    ON notifications (status, created_at);

-- 2) bookings (status, cancelled_at) — abandoned-booking reminder sweep (~30m)
--    Query: findTop200ByStatusAndCancelledAtBetweenOrderByCancelledAtAsc
--    Today: cancelled_at has NO index; only idx_bookings_status. EXPIRED bucket grows forever.
--    Consider a partial index WHERE status='EXPIRED' for an even tighter scan.
CREATE INDEX idx_bookings_status_cancelled_at
    ON bookings (status, cancelled_at);

-- 3) reviews (experience_id, direction, status, created_at DESC) — public experience page
--    Query: findByExperienceIdAndDirectionAndStatusOrderByCreatedAtDesc
--    Today: only single-col idx_reviews_experience_id.
CREATE INDEX idx_reviews_experience_listing
    ON reviews (experience_id, direction, status, created_at DESC);

-- 4) reviews (local_profile_id, direction, status, created_at DESC) — public host page
--    Query: findByLocalProfileIdAndDirectionAndStatusOrderByCreatedAtDesc
--    Today: only single-col idx_reviews_local_profile_id.
CREATE INDEX idx_reviews_host_listing
    ON reviews (local_profile_id, direction, status, created_at DESC);

-- 5) availability_slots (status, start_time) — underbooked-slot cancellation sweep
--    Query: findUnderbookedSlotsForNotice — WHERE start_time BETWEEN ? AND status=? ...
--    Today: idx_availability_experience_status_start_time leads with experience_id (unusable here,
--    the query has no experience filter); status/start_time only indexed separately.
CREATE INDEX idx_availability_status_start_time
    ON availability_slots (status, start_time);

-- 6) wishlist_items (created_at) — wishlist reminder sweep (~30m)
--    Query: findTop200ByCreatedAtBetweenOrderByCreatedAtAsc
--    Today: only idx_wishlist_items_user (user_id, created_at) — leads with user_id, can't serve
--    a created_at-only range. Table grows forever.
CREATE INDEX idx_wishlist_items_created_at
    ON wishlist_items (created_at);

-- 7) messages (conversation_id, created_at) — LOWER priority
--    Query: findByConversationIdOrderByCreatedAtAsc. Usually few msgs/thread; apply only if
--    EXPLAIN shows a Sort node on hot threads. Today: idx_messages_conversation_id (id only).
CREATE INDEX idx_messages_conversation_created
    ON messages (conversation_id, created_at);
