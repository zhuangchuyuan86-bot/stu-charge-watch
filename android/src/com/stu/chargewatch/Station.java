package com.stu.chargewatch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Station {
    static final String[] IDS = {"4GR00008492", "4GR00008485", "4GR00008489", "4GR00008490", "4GR00008495", "4GR00008493", "4GR00008491", "4GR00008494", "4GR00008498", "4GR00008484", "4GR00008497", "4GR00008488", "4GR00008480", "4GR00006723"};
    static final String[] NAMES = {"思源书院左一", "思源书院左二", "思源书院左三", "思源左四", "图书馆一", "图书馆二", "图书馆三", "敬一书院", "志诚书院", "文科教学楼", "滑板场一", "滑板场二", "修远书院", "教师宿舍"};
    static String name(String id) { for(int i=0;i<IDS.length;i++)if(IDS[i].equals(id))return NAMES[i];return id; }
    static final String[] LABELS = {"粘连故障", "空闲", "占用", "短路保护", "设备暂停", "高温保护", "过流保护", "过压保护", "欠压保护"};
    final String id;
    final List<Port> ports;
    final long lastSuccess, lastAttempt;
    final String error;
    final boolean cached;

    Station(String id, List<Port> ports, long success, long attempt, String error, boolean cached) {
        this.id = id;
        this.ports = Collections.unmodifiableList(new ArrayList<>(ports));
        this.lastSuccess = success;
        this.lastAttempt = attempt;
        this.error = error;
        this.cached = cached;
    }

    static Station empty(String id) { return new Station(id, Collections.emptyList(), 0, 0, null, false); }
    Station asCached() { return new Station(id, ports, lastSuccess, lastAttempt, error, true); }
    Station failed(long attempt, String message) { return new Station(id, ports, lastSuccess, attempt, message, false); }
    int count(int state) { int count = 0; for (Port port : ports) if (port.state == state) count++; return count; }
    int free() { return count(1); }
    boolean fresh() { return freshAt(System.currentTimeMillis()); }
    boolean freshAt(long now) { return error == null && lastSuccess > 0 && now >= lastSuccess && now - lastSuccess <= 120_000L; }

    static List<String> availableTransitions(List<Station> before, List<Station> after, long now) {
        Map<String, Station> old = new HashMap<>();
        for (Station station : before) old.put(station.id, station);
        List<String> result = new ArrayList<>();
        for (Station station : after) {
            Station previous = old.get(station.id);
            if (previous != null && previous.lastSuccess > 0 && !previous.ports.isEmpty() && previous.free() == 0 && station.freshAt(now) && station.free() > 0 && station.lastSuccess != previous.lastSuccess) result.add(station.id);
        }
        return result;
    }

    JSONObject toJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (Port port : ports) array.put(new JSONObject().put("num", port.num).put("state", port.state));
        return new JSONObject().put("id", id).put("ports", array).put("last_success", lastSuccess).put("last_attempt", lastAttempt).put("error", error == null ? JSONObject.NULL : error);
    }

    static Station fromJson(String id, String text) {
        try {
            JSONObject data = new JSONObject(text);
            if (!id.equals(data.getString("id"))) return empty(id);
            JSONArray array = data.getJSONArray("ports");
            List<Port> ports = array.length() == 0 ? Collections.emptyList() : parsePorts(array);
            return new Station(id, ports, data.optLong("last_success", 0), data.optLong("last_attempt", 0), data.isNull("error") ? null : data.optString("error", null), true);
        } catch (Exception ignored) { return empty(id); }
    }

    static List<Port> parsePorts(JSONArray array) throws JSONException {
        if (array.length() < 1 || array.length() > 100) throw new JSONException("Invalid port count");
        List<Port> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Object number = item.get("num"), value = item.get("state");
            if (!(number instanceof Number) || !(value instanceof Number)) throw new JSONException("Invalid port format");
            int num = ((Number) number).intValue(), state = ((Number) value).intValue();
            if (((Number) number).doubleValue() != num || ((Number) value).doubleValue() != state || num < 1 || num > 100 || !seen.add(num)) throw new JSONException("Invalid port number");
            result.add(new Port(num, state));
        }
        Collections.sort(result, (a, b) -> Integer.compare(a.num, b.num));
        return result;
    }

    static final class Port {
        final int num, state;
        Port(int num, int state) { this.num = num; this.state = state; }
        String label() { return state >= 0 && state < LABELS.length ? LABELS[state] : "未知状态"; }
    }
}
