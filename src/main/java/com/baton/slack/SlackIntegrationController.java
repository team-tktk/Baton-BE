package com.baton.slack;
import java.util.*; import org.springframework.security.core.Authentication; import org.springframework.web.bind.annotation.*; import com.baton.auth.AuthService; import com.baton.handover.HandoverAccess; import lombok.RequiredArgsConstructor;
@RestController @RequestMapping("/api/v1/integrations/slack") @RequiredArgsConstructor
public class SlackIntegrationController {
 private final SlackIntegrationService service; private final AuthService auth; private final HandoverAccess access;
 @GetMapping("/install-url") public Map<String,String> install(Authentication a){return Map.of("url",service.installUrl(auth.getByEmail(a.getName()).getId()));}
 @GetMapping("/oauth/callback") public Map<String,Object> callback(@RequestParam String code,@RequestParam String state){var c=service.callback(code,state);return Map.of("connected",true,"connectionId",c.getId(),"teamId",c.getTeamId(),"teamName",c.getTeamName());}
 @GetMapping("/{connectionId}/channels") public List<SlackApiClient.Channel> channels(@PathVariable UUID connectionId,Authentication a){return service.channels(auth.getByEmail(a.getName()).getId(),connectionId);}
 @PostMapping("/{connectionId}/subscriptions") public Map<String,Object> subscribe(@PathVariable UUID connectionId,@RequestBody SubscribeRequest r,Authentication a){UUID owner=access.requireOwner(r.handoverId(),a);var s=service.subscribe(owner,r.handoverId(),connectionId,r.channelId(),r.channelName());return Map.of("subscriptionId",s.getId(),"channelId",s.getChannelId(),"synced",true);}
 public record SubscribeRequest(UUID handoverId,String channelId,String channelName){}
}
