package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Actor-private immutable committed history. This is an observation, never a reward receipt. */
public record OrganizationHistoryEntry(UUID id,UUID player,ResourceLocation organization,String action,
                                       long gameTime,long revision) { }
