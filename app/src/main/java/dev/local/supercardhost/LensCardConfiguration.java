package dev.local.supercardhost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only projection of the same camera configuration used by the original card/editor. */
final class LensCardConfiguration {
    record Choice(String type, String title, String functionJson) { }

    static List<Choice> parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return List.of();
        JSONArray cards = new JSONObject(raw).getJSONArray("cards");
        ArrayList<Choice> choices = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.getJSONObject(i);
            if (!"camera".equals(card.optString("type"))) continue;
            JSONArray functions = card.optJSONArray("functions");
            if (functions == null) return List.of();
            for (int j = 0; j < functions.length(); j++) {
                JSONObject function = functions.getJSONObject(j);
                String type = function.optString("type");
                if (!function.optBoolean("enable") || !function.optBoolean("support", true) || seen.contains(type)) continue;
                for (ColorOsLensModes.Mode mode : ColorOsLensModes.query(null)) {
                    if (mode.type().equals(type)) {
                        choices.add(new Choice(type, mode.title(), function.toString()));
                        seen.add(type); break;
                    }
                }
            }
            break;
        }
        return List.copyOf(choices);
    }
}
