package com.ruoyi.shipyard;

import java.util.*;
import static com.ruoyi.shipyard.ShipyardService.check;

/** Classification only: never grants roles, project scope or worker access. */
public final class ShipyardTeams {
    private ShipyardTeams() { }
    public static String category(Map<String,Object> body) {
        if (!body.containsKey("teamCategory")) return null; // Older clients preserve the existing assignment.
        Object value=body.get("teamCategory");
        check(value instanceof String && Arrays.asList("", "grinding", "painting").contains(value),"班组分类无效，请选择打磨班组、油漆班组或未分类");
        return (String)value;
    }
}
