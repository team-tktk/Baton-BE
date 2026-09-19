package com.baton.slack;
import java.time.Instant; import java.util.UUID; import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="slack_oauth_states") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SlackOAuthState { @Id private String state; @Column(name="owner_id",nullable=false) private UUID ownerId; @Column(name="expires_at",nullable=false) private Instant expiresAt;
 public static SlackOAuthState create(UUID owner){var s=new SlackOAuthState();s.state=UUID.randomUUID().toString();s.ownerId=owner;s.expiresAt=Instant.now().plusSeconds(600);return s;}}
