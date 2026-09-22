package com.ultimakingdoms.api.factions.organization;

import net.minecraftforge.eventbus.api.Event;

/** Posted after durable owner commit. Listeners must use owner receipts for irreversible effects. */
public final class OrganizationCommittedEvent extends Event {
    private final OrganizationHistoryEntry entry;
    public OrganizationCommittedEvent(OrganizationHistoryEntry entry) { this.entry=java.util.Objects.requireNonNull(entry); }
    public OrganizationHistoryEntry entry() { return entry; }
}
