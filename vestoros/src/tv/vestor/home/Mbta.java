package tv.vestor.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MBTA's live predictions for the two 85 stops, as one current set. Polling replaces the set
 * whole. The live stream (with a free MBTA key) sends "reset" with the whole set and then "add",
 * "update" and "remove" for single predictions the moment they change, and this applies them, so
 * the countdowns move about as fast as the MBTA itself learns where the bus is.
 *
 * Bounded: at most {@link #MAX} predictions are held, and anything that does not parse as a
 * prediction resource is ignored. Thread-safe.
 */
final class Mbta {
    static final int MAX = 400;

    private final Map<String, Object> live = new LinkedHashMap<>();
    private long changed;

    /** A polled answer ({"data":[...]}): the whole set. */
    synchronized void document(String json, long now) {
        List<Object> data = Json.arr(Json.obj(Json.parse(json)), "data");
        if (data == null) throw new IllegalArgumentException("no data");
        replace(data, now);
    }

    /**
     * One event from the stream. Returns whether the set changed. Unknown event types (the API
     * also sends "error") change nothing.
     */
    synchronized boolean event(String type, String data, long now) {
        if ("reset".equals(type)) {
            Object v = Json.parse(data);
            if (!(v instanceof List)) return false;
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) v;
            replace(list, now);
            return true;
        }
        if ("add".equals(type) || "update".equals(type)) {
            Map<String, Object> p = Json.obj(Json.parse(data));
            String id = Json.str(p, "id", null);
            if (id == null || !"prediction".equals(Json.str(p, "type", ""))) return false;
            if (!live.containsKey(id) && live.size() >= MAX) return false;
            live.put(id, p);
            changed = now;
            return true;
        }
        if ("remove".equals(type)) {
            String id = Json.str(Json.obj(Json.parse(data)), "id", null);
            if (id == null || live.remove(id) == null) return false;
            changed = now;
            return true;
        }
        return false;
    }

    private void replace(List<Object> data, long now) {
        live.clear();
        for (Object o : data) {
            if (live.size() >= MAX) break;
            String id = Json.str(Json.obj(o), "id", null);
            if (id != null) live.put(id, o);
        }
        changed = now;
    }

    /** Fills the two 85 lines with minutes from now. */
    synchronized void fill(long now, Model.Line assembly, Model.Line ruggles) {
        Transit.mbta(new ArrayList<Object>(live.values()), now, assembly, ruggles);
    }

    synchronized long changed() {
        return changed;
    }

    synchronized void clear() {
        live.clear();
        changed = 0;
    }
}
