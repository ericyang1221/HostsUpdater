package com.yang.eric.hostsupdater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int COPY_SUCCESS = 0;
    private static final int CURRENT_HOSTS_IS_NEWER = 1;
    private static final int READ_LOCALHOSTS_COMPLETE = 2;

    private static StringBuffer sb;
    private static StringBuffer localSb;
    private static TextView tv;
    private static TextView localHostsTv;
    private static FloatingActionButton fab;
    private static String url = "https://raw.githubusercontent.com/googlehosts/hosts/master/hosts-files/hosts";
    private static String hostsPath;
    private static SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        localHostsTv = findViewById(R.id.local_host_content);
        tv = findViewById(R.id.host_content);
        TextView urlTv = findViewById(R.id.url);
        if (sp == null){
            sp = getSharedPreferences("sp", Context.MODE_PRIVATE);
        }
        String spUrl = sp.getString("url",null);
        if (!TextUtils.isEmpty(spUrl)){
            url = spUrl;
        }
        urlTv.setText(url);

        fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        File cacheFileDir = getCacheDir();
                        try {
                            downLoadFromUrl(url, "hosts", cacheFileDir.getAbsolutePath());
                            hostsPath = cacheFileDir.getAbsolutePath()+ File.separator+"hosts";
                            InputStream instream = new FileInputStream(hostsPath);
                            InputStreamReader inputreader = new InputStreamReader(instream);
                            BufferedReader buffreader = new BufferedReader(inputreader);
                            String line;
                            int count = 0;
                            do {
                                count++;
                                line = buffreader.readLine();
                                if (sb == null){
                                    sb = new StringBuffer();
                                }
                                sb.append(line).append("\n");
                            } while (line != null && count < 3);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        long newFileTimeInMillions = findTimeInMillions(sb.toString());
                        int what = -1;
                        if (sp != null){
                            long old = sp.getLong("currentFileTimeInMillions",0);
                            Log.d(TAG, "newFileTimeInMillions: "+newFileTimeInMillions+", old: "+old);
                            if (newFileTimeInMillions > old){
                                cpHosts(hostsPath);
                                what = COPY_SUCCESS;
                            }else{
                                what = CURRENT_HOSTS_IS_NEWER;
                            }
                            sp.edit().putLong("currentFileTimeInMillions",newFileTimeInMillions).apply();
                        }
                        handler.sendEmptyMessage(what);
                    }
                }).start();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                rootAccess();
                readLocalHosts();
            }
        }).start();
    }

    private static void readLocalHosts(){
        try{
            InputStream instream = new FileInputStream("/etc/hosts");
            InputStreamReader inputreader = new InputStreamReader(instream);
            BufferedReader buffreader = new BufferedReader(inputreader);
            String line;
            int count = 0;
            do {
                count++;
                line = buffreader.readLine();
                if (localSb == null){
                    localSb = new StringBuffer();
                }
                localSb.append(line).append("\n");
            } while (line != null && count < 3);
            handler.sendEmptyMessage(READ_LOCALHOSTS_COMPLETE);
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private static Handler handler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == READ_LOCALHOSTS_COMPLETE){
                if (localHostsTv != null && localSb != null){
                    localHostsTv.setText(localSb.toString());
                }
            }else{
                String t = sb.toString();
                String msgStr;
                if (TextUtils.isEmpty(t)){
                    sb = null;
                    msgStr = "Download error.";
                }else{
                    tv.setText(t);
                    sb = null;
                    if (msg.what == CURRENT_HOSTS_IS_NEWER){
                        msgStr = "Current hosts is newer, do not copy.";
                    }else if(msg.what == COPY_SUCCESS){
                        msgStr = "Copy success.";
                    }else{
                        msgStr = "Unknow error.";
                    }
                }
                Snackbar.make(fab, msgStr, Snackbar.LENGTH_LONG).setAction("Action", null).show();
            }
        }
    };

    private static void rootAccess(){
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su"); // 切换到root帐号
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                process.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean cpHosts(String filePath) {
        if (TextUtils.isEmpty(filePath)){
            return false;
        }
        Process process = null;
        DataOutputStream os = null;
        try {
            String cpCmd = "cp " + filePath +" /magisk/.core/hosts";
            String chmodCmd = "chmod 755 " + "/magisk/.core/hosts";
            process = Runtime.getRuntime().exec("su"); // 切换到root帐号
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(cpCmd + "\n");
            os.writeBytes(chmodCmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                process.destroy();
            } catch (Exception e) {
            }
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void downLoadFromUrl(String urlStr, String fileName, String savePath) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        //设置超时间为3秒
        conn.setConnectTimeout(3 * 1000);
        //防止屏蔽程序抓取而返回403错误
        conn.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)");
        //得到输入流
        InputStream inputStream = conn.getInputStream();
        //获取自己数组
        byte[] getData = readInputStream(inputStream);
        //文件保存位置
        File saveDir = new File(savePath);
        if (!saveDir.exists()) {
            saveDir.mkdir();
        }
        File file = new File(saveDir + File.separator + fileName);
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(getData);
        if (fos != null) {
            fos.close();
        }
        if (inputStream != null) {
            inputStream.close();
        }
        System.out.println("info:" + url + " download success");
    }

    public byte[] readInputStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int len = 0;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        bos.close();
        return bos.toByteArray();
    }

    private static long findTimeInMillions(String text){
        long ret = 0;
        Pattern pattern = Pattern.compile("\\d+-\\d+-\\d+");
        Matcher matcher = pattern.matcher(text);
        String dateStr = null;
        if(matcher.find()){
            dateStr = matcher.group(0);
            Log.d(TAG, "findTimeInMillions, dateStr: "+dateStr);
            if (!TextUtils.isEmpty(dateStr)){
                String date[] = dateStr.split("-");
                if (date != null && date.length == 3){
                    Calendar calendar = Calendar.getInstance();
                    int year = Integer.valueOf(date[0]);
                    int month = Integer.valueOf(date[1]);
                    int day = Integer.valueOf(date[2]);
                    calendar.set(year,month,day,0,0,0);
                    Log.d(TAG, "findTimeInMillions, calendar: "+calendar.toString());
                    ret = calendar.getTimeInMillis()/1000;
                }
            }
        }
        return ret;
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        System.exit(0);
    }
}
