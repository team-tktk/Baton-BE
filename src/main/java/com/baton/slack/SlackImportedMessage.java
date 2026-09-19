package com.baton.slack;
import java.util.UUID; import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="slack_imported_messages",uniqueConstraints=@UniqueConstraint(columnNames={"handover_id","connection_id","channel_id","message_ts"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SlackImportedMessage { @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @Column(name="handover_id",nullable=false) private UUID handoverId; @Column(name="connection_id",nullable=false) private UUID connectionId; @Column(name="channel_id",nullable=false) private String channelId; @Column(name="message_ts",nullable=false) private String messageTs; @Column(name="source_id",nullable=false) private UUID sourceId; @Column(name="content_hash",nullable=false) private String contentHash;
 public static SlackImportedMessage create(UUID h,UUID c,String ch,String ts,UUID source,String hash){var m=new SlackImportedMessage();m.handoverId=h;m.connectionId=c;m.channelId=ch;m.messageTs=ts;m.sourceId=source;m.contentHash=hash;return m;} public void changed(String h){contentHash=h;}}
