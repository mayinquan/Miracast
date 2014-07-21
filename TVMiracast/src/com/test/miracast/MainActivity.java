
package com.test.miracast;

import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.view.Menu;
import android.widget.MediaController;
import android.widget.VideoView;

import com.mi.bonjour.Bonjour;
import com.mi.bonjour.BonjourListener;
import com.mi.bonjour.serviceinfo.MiLinkServiceInfo;
import com.mi.milink.common.IQ;
import com.mi.milink.server.MiLinkServer;
import com.mi.milink.server.MiLinkServerListener;
import com.mi.milink.contants.*;
import com.mi.milink.contants.miracast.*;
import com.mi.net.util.NetWork;

import java.util.Map;

public class MainActivity extends Activity implements MiLinkServerListener, BonjourListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    private MiLinkServer mServer = null;
    private Bonjour mBonjour = null;
    private int eventId = 0;
    private VideoView mVideoView = null;
    private MediaController mMc = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoView = (VideoView)findViewById(R.id.videoView);
        mMc = new MediaController(this);
        mVideoView.setMediaController(mMc);
        mVideoView.requestFocus();

        // start Server
        mServer = new MiLinkServer(this);
        mServer.start();

        // start Bonjour
        mBonjour = new Bonjour(this, this);
        mBonjour.start();

        // publish Service
        byte[] deviceId = NetWork.getMacAddress();
        String name = "MiTV";
        int port = mServer.getListenPort();
        MiLinkServiceInfo svcInfo = new MiLinkServiceInfo(deviceId, name, port);
        mBonjour.publishService(svcInfo);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    protected void onDestroy() {
        this.publishEvent(com.mi.milink.contants.miracast.Events.STOPPED);
        
        mBonjour.stop();
        mServer.stop();
        super.onDestroy();
    }

    public void publishEvent(String event) {
        String param = "<root/>";
        IQ iq = new IQ(IQ.Type.Event,
                eventId++,
                com.mi.milink.contants.Xmlns.MIRACAST,
                event,
                param.getBytes());

        mServer.publishEvent(iq);
    }

    @Override
    public void onAccept(MiLinkServer server, String ip, int port) {
        Log.d(TAG, String.format("onAccept: %s%d", ip, port));
    }

    @Override
    public void onReceived(MiLinkServer server, String ip, int port, IQ iq) {
        Log.d(TAG, String.format("onReceived: %s%d", ip, port));
        
        if (iq.getXmlns().equalsIgnoreCase(Xmlns.MIRACAST)) {
            iq.setType(IQ.Type.Error);
            server.send(ip, port, iq);
            return;
        }
        
        if (iq.getType() != IQ.Type.Set) {
            iq.setType(IQ.Type.Error);
            server.send(ip, port, iq);
            return;
        }
        
        if (iq.getAction().equalsIgnoreCase(Actions.START)){
            ParamStart param = ParamStart.create(iq.getParam());
            if (param == null){
                iq.setType(IQ.Type.Error);
                server.send(ip, port, iq);
                return;
            }
            
            String url = String.format("wfd://%s:%d", param.getIp(), param.getPort());
            mVideoView.setVideoPath(url);
            mVideoView.start();
            
            this.publishEvent(Events.PLAYING);
        }
        else if (iq.getAction().equalsIgnoreCase(Actions.STOP)){
            mVideoView.stopPlayback();

            this.publishEvent(Events.STOPPED);
        }
    }

    @Override
    public void onConnectionClosed(MiLinkServer server, String ip, int port) {
        Log.d(TAG, String.format("onConnectionClosed: %s%d", ip, port));
    }

    @Override
    public void onServiceFound(String name, String type, String ip, int port,
            Map<String, String> properties) {
        Log.d(TAG, String.format("onServiceFound: %s %s %s:%d", name, type, ip, port));
    }

    @Override
    public void onServiceLost(String name, String type, String ip) {
        Log.d(TAG, String.format("onServiceLost: %s %s %s:%d", name, type, ip));
    }
}