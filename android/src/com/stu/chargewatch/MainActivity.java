package com.stu.chargewatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private static final Set<String> FILES=new HashSet<>(Arrays.asList("/index.html","/app.js","/android.js","/styles.css","/assets/icon.svg","/assets/campus-map.json"));
    private static AppBackend backend;
    private WebView web;
    private AlertDialog promptDialog;
    private boolean pageReady;

    private static synchronized AppBackend backend(Context context)throws Exception {
        if(backend==null){
            Context app=context.getApplicationContext();
            JSONObject catalog=new JSONObject(new String(read(app,"web/catalog.json"),StandardCharsets.UTF_8));
            JSONObject snapshot=new JSONObject(new String(read(app,"web/snapshot.json"),StandardCharsets.UTF_8));
            backend=new AppBackend(Provider.get(app),catalog,snapshot,(before,after)->Notifications.report(app,before,after));
        }
        return backend;
    }
    private static byte[] read(Context context,String name)throws Exception {
        try(InputStream input=context.getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count,total=0;
            while((count=input.read(buffer))!=-1){total+=count;if(total>3_000_000)throw new IllegalStateException("Asset too large");output.write(buffer,0,count);}
            return output.toByteArray();
        }
    }
    private static boolean known(String id){for(String code:Station.IDS)if(code.equals(id))return true;return false;}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{backend(this);}catch(Exception error){TextView failure=new TextView(this);failure.setPadding(32,64,32,32);failure.setText("应用资源读取失败，请重新安装最新版汕大充电。");setContentView(failure);return;}
        Notifications.createChannel(this);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(246,247,243));
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((view,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });}
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(246,247,243));
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);settings.setSupportZoom(false);settings.setTextZoom(100);
        web.addJavascriptInterface(new Host(),"StuAndroid");
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return asset(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri url=request.getUrl();if(trusted(url))return !url.getPath().equals("/")&&!url.getPath().equals("/index.html");
                if("https".equals(url.getScheme())&&request.isForMainFrame())try{startActivity(new Intent(Intent.ACTION_VIEW,url));}catch(Exception ignored){}
                return true;
            }
            @Override public void onPageFinished(WebView view,String url){if(trusted(Uri.parse(url))){pageReady=true;openRequestedDevice();}}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){
                if(!trusted(Uri.parse(url))){result.cancel();return true;}
                EditText input=new EditText(MainActivity.this);input.setSingleLine(true);input.setText(initial);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
                promptDialog=new AlertDialog.Builder(MainActivity.this).setTitle("设备备注").setMessage(message).setView(input)
                    .setPositiveButton("保存",(dialog,which)->result.confirm(input.getText().toString())).setNegativeButton("取消",(dialog,which)->result.cancel()).setOnCancelListener(dialog->result.cancel()).create();
                promptDialog.show();return true;
            }
        });
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        if(state==null||web.restoreState(state)==null)web.loadUrl(ORIGIN+"/#map");
        if(Settings.watch(this)&&Notifications.allowed(this))MonitorJobService.schedule(this);
    }
    private static boolean trusted(Uri uri){return "https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443);}
    private WebResourceResponse asset(Uri uri){
        try{
            if(!trusted(uri))return response(403,"text/plain",new byte[0]);
            String path=uri.getPath();if("/".equals(path))path="/index.html";if("/favicon.ico".equals(path))path="/assets/icon.svg";
            if(!FILES.contains(path))return response(404,"text/plain",new byte[0]);
            String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".json")?"application/json":path.endsWith(".svg")?"image/svg+xml":"text/html";
            return response(200,mime,read(this,"web"+path));
        }catch(Exception error){return response(500,"text/plain",new byte[0]);}
    }
    private static WebResourceResponse response(int status,String mime,byte[] data){
        Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");
        headers.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
        return new WebResourceResponse(mime,"UTF-8",status,status==200?"OK":"Unavailable",headers,new ByteArrayInputStream(data));
    }
    public final class Host {
        @JavascriptInterface public String request(String route,String body){return backend.request(route,body);}
        @JavascriptInterface public String watchState(){try{return new JSONObject().put("enabled",Settings.watch(MainActivity.this)).put("allowed",Notifications.allowed(MainActivity.this)).toString();}catch(Exception ignored){return "{}";}}
        @JavascriptInterface public void setWatch(boolean enabled){runOnUiThread(()->toggleWatch(enabled));}
        @JavascriptInterface public String legacyPreferences(){
            try{JSONObject names=new JSONObject();for(String id:Station.IDS){String value=Settings.prefs(MainActivity.this).getString("name_"+id,"");if(!value.isEmpty())names.put(id,value);}
                return new JSONObject().put("names",names).put("auto",Settings.auto(MainActivity.this)).toString();
            }catch(Exception ignored){return "{}";}
        }
    }
    private void emit(String event){if(web!=null&&!isDestroyed())web.evaluateJavascript("window.dispatchEvent(new Event("+JSONObject.quote(event)+"))",null);}
    private void toggleWatch(boolean enabled){
        if(!enabled){Settings.setWatch(this,false);MonitorJobService.cancel(this);emit("charge-watch-change");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);return;}
        if(!Notifications.allowed(this)){Settings.setWatch(this,false);emit("charge-watch-change");
            new AlertDialog.Builder(this).setTitle("允许空位提醒").setMessage("请在系统设置中允许汕大充电发送通知。")
                .setPositiveButton("打开设置",(dialog,which)->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()))).setNegativeButton("取消",null).show();return;}
        boolean scheduled=MonitorJobService.schedule(this);Settings.setWatch(this,scheduled);emit("charge-watch-change");
        Toast.makeText(this,scheduled?"后台提醒已开启，检查时间由系统安排":"系统暂时无法安排后台检查，请稍后重试",Toast.LENGTH_LONG).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==101){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)toggleWatch(true);else{Settings.setWatch(this,false);emit("charge-watch-change");}}}
    private void openRequestedDevice(){
        if(web==null||!pageReady||getIntent()==null)return;String id=getIntent().getStringExtra("device");if(!known(id))return;
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('charge-open-device',{detail:"+JSONObject.quote(id)+"}))",null);getIntent().removeExtra("device");
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openRequestedDevice();}
    @Override protected void onResume(){super.onResume();if(web!=null){web.resumeTimers();web.onResume();emit("charge-resume");}}
    @Override protected void onPause(){if(web!=null){web.onPause();web.pauseTimers();}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){if(web!=null)web.saveState(state);super.onSaveInstanceState(state);}
    @Override public void onBackPressed(){
        if(web==null){super.onBackPressed();return;}
        web.evaluateJavascript("(()=>{const dialog=document.querySelector('dialog[open]');if(dialog){dialog.close();return true;}if(location.hash!=='#map'){document.querySelector('.rail-button[data-page=map]').click();return true;}return false;})()",result->{if(!"true".equals(result))finish();});
    }
    @Override protected void onDestroy(){if(promptDialog!=null)promptDialog.dismiss();if(web!=null){web.removeJavascriptInterface("StuAndroid");web.destroy();web=null;}super.onDestroy();}
}
