package com.stu.chargewatch;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class Provider {
    private static final String BASE = "https://wx.isfdz.com/";
    private static volatile Provider instance;
    private final SharedPreferences storage;
    private final Object refreshLock = new Object();
    private final ConcurrentHashMap<String, Station> stations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> types = new ConcurrentHashMap<>();
    private String appId, clientSecret;
    private long configTime;

    static Provider get(Context context) {
        if (instance == null) synchronized (Provider.class) {
            if (instance == null) instance = new Provider(context.getApplicationContext());
        }
        return instance;
    }

    private Provider(Context context) {
        storage = context.getSharedPreferences("charge_snapshots", Context.MODE_PRIVATE);
        for (String id : Station.IDS) stations.put(id, Station.fromJson(id, storage.getString(id, "{}")));
    }

    List<Station> snapshots() {
        List<Station> result = new ArrayList<>();
        for (String id : Station.IDS) result.add(stations.get(id));
        return result;
    }

    List<Station> refreshAll() {
        return refresh(java.util.Arrays.asList(Station.IDS), null);
    }

    List<Station> refresh(List<String> ids, Runnable progress) {
        for(String id:ids)if(!stations.containsKey(id))throw new IllegalArgumentException("Unknown device");
        // Workers acquire the client configuration lock; do not hold that lock
        // while waiting for them. Serialize refreshes using a separate lock.
        synchronized (refreshLock) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<Station>> calls = new ArrayList<>();
            for (String id : ids) calls.add(() -> {try{return refreshOne(id);}finally{if(progress!=null)progress.run();}});
            try {
                List<Future<Station>> results = pool.invokeAll(calls);
                for (Future<Station> result : results) result.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Each device preserves its prior state and reports failures.
            } finally { pool.shutdownNow(); }
            return snapshots();
        }
    }

    private Station refreshOne(String id) {
        Station previous = stations.get(id);
        long now = System.currentTimeMillis(), elapsed = now - previous.lastAttempt;
        if (previous.lastAttempt > 0 && elapsed >= 0 && elapsed < 60_000) {
            Station cached = previous.asCached(); stations.put(id, cached); return cached;
        }
        Station result;
        try {
            if (!types.containsKey(id)) {
                JSONObject params = new JSONObject().put("code", id);
                Object type = query("charge/mobile/getEquipType", params, false);
                if (!(type instanceof Integer)) throw new QueryError("平台返回了无法识别的设备类型。");
                types.put(id, (Integer) type);
            }
            int type = types.get(id);
            String mode;
            if (contains(new int[]{2,6,8,10,11,12,13,14}, type)) mode = "afterProtocol";
            else if (contains(new int[]{1,4,7,3,5,9}, type)) mode = "beforeProtocol";
            else throw new QueryError("该设备类型暂不支持查询。");
            Object raw = query("equi/" + mode + "/getLineInfo", new JSONObject().put("code", id).put("parentCode", ""), true);
            if (!(raw instanceof JSONArray)) throw new QueryError("平台未返回有效的插座列表。");
            List<Station.Port> ports = Station.parsePorts((JSONArray) raw);
            result = new Station(id, ports, System.currentTimeMillis(), now, null, false);
        } catch (QueryError error) {
            result = previous.failed(now, error.getMessage());
        } catch (JSONException error) {
            result = previous.failed(now, "平台返回格式发生变化，暂时无法更新。");
        } catch (Exception error) {
            result = previous.failed(now, "连接充电平台失败，请检查手机网络后重试。");
        }
        stations.put(id, result);
        try { storage.edit().putString(id, result.toJson().toString()).apply(); } catch (JSONException ignored) {}
        return result;
    }

    private static boolean contains(int[] values, int candidate) { for (int value : values) if (value == candidate) return true; return false; }

    private synchronized void ensureClient() throws Exception {
        if (appId != null && System.currentTimeMillis() - configTime < 6 * 60 * 60_000L) return;
        String page = read(new URL(BASE), null);
        Matcher script = Pattern.compile("static/js/index\\.[a-f0-9]+\\.js").matcher(page);
        if (!script.find()) throw new QueryError("平台页面已更新，需要调整连接方式。");
        String source = read(new URL(BASE + script.group()), null);
        Matcher id = Pattern.compile("app_id:\"([^\"\\n]+)\"").matcher(source);
        Matcher secret = Pattern.compile("app_secret:\"([^\"\\n]+)\"").matcher(source);
        if (!id.find() || !secret.find()) throw new QueryError("平台查询格式已变化，需要更新应用。");
        appId = id.group(1); clientSecret = secret.group(1); configTime = System.currentTimeMillis();
    }

    private Object query(String endpoint, JSONObject params, boolean post) throws Exception {
        if (!endpoint.equals("charge/mobile/getEquipType") && !endpoint.equals("equi/afterProtocol/getLineInfo") && !endpoint.equals("equi/beforeProtocol/getLineInfo")) throw new QueryError("不支持该请求。");
        ensureClient();
        TreeMap<String, Object> data = new TreeMap<>();
        java.util.Iterator<String> keys = params.keys();
        while (keys.hasNext()) { String key = keys.next(); data.put(key, params.get(key)); }
        data.put("timestamp", System.currentTimeMillis() / 1000); data.put("app_id", appId);
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) canonical.append(entry.getKey()).append('=').append(entry.getValue());
        canonical.append(clientSecret);
        byte[] digest = MessageDigest.getInstance("MD5").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder signature = new StringBuilder();
        for (byte value : digest) signature.append(String.format(java.util.Locale.US, "%02x", value & 255));
        data.put("sign", signature.toString());
        String url = BASE + "api/" + endpoint;
        byte[] body = null;
        if (post) body = new JSONObject(data).toString().getBytes(StandardCharsets.UTF_8);
        else {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (query.length() > 0) query.append('&');
                query.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(String.valueOf(entry.getValue()), "UTF-8"));
            }
            url += "?" + query;
        }
        JSONObject reply = new JSONObject(read(new URL(url), body));
        if (reply.optInt("code", -1) != 200) throw new QueryError("平台未返回状态；可能需要正常登录或稍后再试。");
        return reply.opt("data");
    }

    private static String read(URL url, byte[] body) throws Exception {
        if (!url.getProtocol().equals("https") || !url.getHost().equals("wx.isfdz.com")) throw new QueryError("平台地址发生变化。");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(12_000); connection.setReadTimeout(12_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept", "application/json,text/html,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new QueryError("平台要求正常登录或限制了访问，暂时无法更新。");
            if (status < 200 || status >= 300) throw new QueryError("平台暂时未能响应，请稍后重试。");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 3_000_000) throw new QueryError("平台返回的数据过大。");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }

    private static final class QueryError extends Exception { private static final long serialVersionUID = 1L; QueryError(String message) { super(message); } }
}
