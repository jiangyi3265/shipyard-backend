package com.ruoyi.shipyard;
import java.util.*;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.Assert.*;
import static com.ruoyi.shipyard.ShipyardService.map;
public class ShipyardVisibilityTest {
 private ShipyardAccess access(String... roles){return new ShipyardAccess(new JdbcTemplate()){
  public long uid(){return 10;}
  public void active(){}
  public List<String> roles(){return Arrays.asList(roles);}
 };}
 @Test public void leaderCannotReadAnotherLeadersWorkEvenWithExtraReadGrants(){
  ShipyardAccess leader=access("sy_leader");
  Set<String> grants=new HashSet<>(Arrays.asList("shipyard:finance:view","shipyard:work:approve"));
  for(String kind:Arrays.asList("用工","报销","罚款")){
   assertFalse(leader.canReadAuthorizedProject(map("created_by",11,"kind",kind),grants));
   assertTrue(leader.canReadAuthorizedProject(map("created_by",10,"kind",kind),grants));
  }
 }
 @Test public void managementRolesReadAllKindsWithoutReceivingWritePermissions(){
  for(String role:Arrays.asList("sy_admin","sy_manager","sy_owner")){
   ShipyardAccess a=access(role);assertTrue(a.allProjects());assertFalse(a.ownWorkOnly());
   for(String kind:Arrays.asList("用工","报销","罚款"))assertTrue(a.canReadAuthorizedProject(map("created_by",11,"kind",kind),Collections.emptySet()));
  }
  assertFalse(access("sy_leader","sy_owner").ownWorkOnly());
 }
}
