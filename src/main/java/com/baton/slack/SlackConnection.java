package com.baton.slack;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity @Table(name="slack_connections", uniqueConstraints=@UniqueConstraint(columnNames={"owner_id","team_id"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SlackConnection {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="owner_id",nullable=false) private UUID ownerId;
 @Column(name="team_id",nullable=false) private String teamId;
 @Column(name="team_name",nullable=false) private String teamName;
 @Column(name="user_token",nullable=false,length=2000) private String accessToken;
 @Column(name="slack_user_id",nullable=false) private String slackUserId;
 @Column(nullable=false) private String scopes;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 public static SlackConnection create(UUID ownerId,String teamId,String teamName,String token,String slackUserId,String scopes){var c=new SlackConnection();c.ownerId=ownerId;c.teamId=teamId;c.teamName=teamName;c.accessToken=token;c.slackUserId=slackUserId;c.scopes=scopes;c.createdAt=Instant.now();return c;}
 public void refresh(String name,String token,String slackUserId,String scopes){teamName=name;accessToken=token;this.slackUserId=slackUserId;this.scopes=scopes;}
}
