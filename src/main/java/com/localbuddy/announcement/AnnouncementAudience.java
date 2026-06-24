package com.localbuddy.announcement;

/** Who a host (or the platform) is addressing with an announcement. */
public enum AnnouncementAudience {
    /** Travelers who follow the host. */
    MY_FOLLOWERS,
    /** Travelers/guests who have a confirmed or completed booking with the host. */
    MY_GUESTS,
    /** Both followers and guests. */
    BOTH,
    /** Platform announcement to all hosts (admin only). */
    ALL_HOSTS
}
