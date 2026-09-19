package com.baton.slack;

import java.time.Instant;
import java.util.UUID;

public record SlackSubscriptionResponse(
		UUID subscriptionId,
		UUID handoverId,
		UUID connectionId,
		String channelId,
		String channelName,
		boolean enabled,
		boolean backfillComplete,
		long importedMessageCount,
		Instant lastSyncedAt) {

	public static SlackSubscriptionResponse from(SlackSubscription subscription, long importedMessageCount) {
		return new SlackSubscriptionResponse(subscription.getId(), subscription.getHandoverId(),
				subscription.getConnectionId(), subscription.getChannelId(), subscription.getChannelName(),
				subscription.isEnabled(), subscription.isBackfillComplete(), importedMessageCount,
				subscription.getUpdatedAt());
	}
}
