package com.baton.slack;
import java.time.Instant; import java.util.UUID; import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="slack_subscriptions",uniqueConstraints=@UniqueConstraint(columnNames={"handover_id","connection_id","channel_id"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SlackSubscription {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="handover_id",nullable=false) private UUID handoverId;
 @Column(name="connection_id",nullable=false) private UUID connectionId;
 @Column(name="channel_id",nullable=false) private String channelId;
 @Column(name="channel_name",nullable=false) private String channelName;
 @Column(name="oldest_ts") private String oldestTs;
 @Column(name="backfill_cursor",length=2000) private String backfillCursor;
 @Column(name="backfill_complete",nullable=false) private boolean backfillComplete;
 @Column(nullable=false) private boolean enabled=true;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 public static SlackSubscription create(UUID h,UUID c,String id,String name){var s=new SlackSubscription();s.handoverId=h;s.connectionId=c;s.channelId=id;s.channelName=name;s.updatedAt=Instant.now();return s;}
 public void synced(String ts,String nextCursor){if(ts!=null&&!ts.isBlank()&&(oldestTs==null||Double.parseDouble(ts)>Double.parseDouble(oldestTs)))oldestTs=ts;backfillCursor=nextCursor;if(nextCursor==null||nextCursor.isBlank())backfillComplete=true;updatedAt=Instant.now();}
 public void disable(){enabled=false;updatedAt=Instant.now();}
}
