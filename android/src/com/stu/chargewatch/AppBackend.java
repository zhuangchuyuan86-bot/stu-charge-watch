package com.stu.chargewatch;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Supplies the bundled mobile interface with on-device platform queries. */
final class AppBackend {
    private final Provider provider;
    private final JSONObject catalog;
    private final Map<String,JSONObject> metadata = new HashMap<>(), snapshots = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final BiConsumer<List<Station>,List<Station>> onRefresh;
    private boolean running;
    private int completed, total;
    private long started, finished;
    private List<String> jobIds = new ArrayList<>();

    AppBackend(Provider provider, JSONObject catalog, JSONObject snapshot,
               BiConsumer<List<Station>,List<Station>> onRefresh) throws Exception {
        this.provider=provider;this.catalog=catalog;this.onRefresh=onRefresh;
        JSONArray devices=catalog.getJSONArray("devices"), old=snapshot.getJSONArray("data");
        for(int i=0;i<devices.length();i++){JSONObject item=devices.getJSONObject(i);metadata.put(item.getString("id"),item);}
        for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(metadata.containsKey(item.getString("code")))snapshots.put(item.getString("code"),item);}
        if(metadata.size()!=Station.IDS.length)throw new IllegalStateException("Catalogue mismatch");
        for(String id:Station.IDS)if(!metadata.containsKey(id))throw new IllegalStateException("Catalogue mismatch");
    }

    static String utc(long millis) {
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date(millis));
    }
    private static Object time(long value){return value>0?utc(value):JSONObject.NULL;}

    private JSONObject job() throws Exception {
        synchronized(lock){return new JSONObject().put("running",running).put("completed",completed).put("total",total)
            .put("started_at",time(started)).put("finished_at",time(finished)).put("ids",new JSONArray(jobIds));}
    }

    JSONObject payload() throws Exception {
        long now=System.currentTimeMillis();JSONArray records=new JSONArray();int fresh=0,free=0,busy=0,capacity=0;
        for(Station station:provider.snapshots()){
            JSONObject record=new JSONObject(metadata.get(station.id).toString());JSONArray ports=new JSONArray();
            boolean live=station.lastSuccess>0&&!station.ports.isEmpty(), valid=live&&station.freshAt(now);
            String source=live?"platform":snapshots.containsKey(station.id)?"repository_snapshot":"unqueried";
            if(live){for(Station.Port port:station.ports)ports.put(new JSONObject().put("num",port.num).put("state",port.state).put("label",port.label()));}
            else if(snapshots.containsKey(station.id)){
                JSONArray original=snapshots.get(station.id).getJSONArray("ports");
                for(int i=0;i<original.length();i++){
                    JSONObject p=original.getJSONObject(i);int value=p.getInt("state");
                    ports.put(new JSONObject().put("num",p.getInt("num")).put("state",value)
                        .put("label",value>=0&&value<Station.LABELS.length?Station.LABELS[value]:"未知状态"));
                }
            }
            int available=0,occupied=0;
            for(int i=0;i<ports.length();i++){int state=ports.getJSONObject(i).getInt("state");if(state==1)available++;else if(state==2)occupied++;}
            int expected=ports.length()>0?ports.length():record.optInt("expected_ports",0);capacity+=expected;
            if(valid){fresh++;free+=available;busy+=occupied;}
            record.put("ports",ports).put("counts",new JSONObject().put("total",ports.length()).put("free",available).put("busy",occupied).put("other",ports.length()-available-occupied))
                .put("expected_ports",expected).put("source",source).put("fresh",valid)
                .put("last_success",time(station.lastSuccess)).put("last_attempt",time(station.lastAttempt))
                .put("error",station.error==null?JSONObject.NULL:station.error)
                .put("age_seconds",station.lastSuccess>0?(now-station.lastSuccess)/1000.0:JSONObject.NULL)
                .put("cached",station.cached).put("snapshot_at",source.equals("repository_snapshot")?catalog.getJSONObject("repository").get("snapshot_at"):JSONObject.NULL);
            records.put(record);
        }
        return new JSONObject().put("devices",records).put("locations",catalog.getJSONArray("locations"))
            .put("repository",catalog.getJSONObject("repository")).put("server_time",utc(now)).put("minimum_interval_seconds",60).put("refresh",job())
            .put("stats",new JSONObject().put("registered_devices",records.length()).put("registered_ports",capacity)
                .put("fresh_devices",fresh).put("known_free",free).put("known_busy",busy).put("mapped_locations",catalog.getJSONArray("locations").length()));
    }

    private boolean startRefresh(List<String> ids) {
        synchronized(lock){if(running)return false;running=true;completed=0;total=ids.size();started=System.currentTimeMillis();finished=0;jobIds=new ArrayList<>(ids);}
        executor.execute(()->{
            try{
                List<Station> before=provider.snapshots();
                List<Station> after=provider.refresh(ids,()->{synchronized(lock){completed++;}});
                if(onRefresh!=null)onRefresh.accept(before,after);
            }finally{synchronized(lock){running=false;finished=System.currentTimeMillis();}}
        });
        return true;
    }

    private static String response(int status,JSONObject data) throws Exception {
        return new JSONObject().put("status",status).put("data",data).toString();
    }
    String request(String route,String body) {
        try{
            if("/api/stations".equals(route))return response(200,payload());
            if("/api/catalog".equals(route))return response(200,catalog);
            if(!"/api/refresh".equals(route))return response(404,new JSONObject().put("error","Not found"));
            if(body!=null&&body.length()>4096)return response(413,new JSONObject().put("error","Request too large"));
            JSONObject input=new JSONObject(body==null||body.isEmpty()?"{}":body);LinkedHashSet<String> ids=new LinkedHashSet<>();
            if(!input.has("ids")){for(String id:Station.IDS)ids.add(id);}
            else{
                JSONArray requested=input.getJSONArray("ids");
                if(requested.length()==0||requested.length()>Station.IDS.length)throw new IllegalArgumentException();
                for(int i=0;i<requested.length();i++){
                    Object id=requested.get(i);if(!(id instanceof String)||!metadata.containsKey((String)id))throw new IllegalArgumentException();ids.add((String)id);
                }
            }
            boolean accepted=startRefresh(new ArrayList<>(ids));
            return response(202,new JSONObject().put("started",accepted).put("refresh",job()));
        }catch(Exception error){
            try{return response(400,new JSONObject().put("error","无法读取请求，请重新打开应用后重试。"));}
            catch(Exception ignored){return "{\"status\":500,\"data\":{\"error\":\"Request failed\"}}";}
        }
    }
    void close(){executor.shutdownNow();}
}
